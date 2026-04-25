/**
 * ESP32 E220 LoRa Web Chat & Configuration
 * 
 * A dual-device LoRa messaging system using:
 * - ESP32 DevKit microcontroller
 * - Ebyte E220-900T30D 900MHz LoRa modules
 * - Web UI for chat and configuration
 * - Serial terminal for advanced commands
 * 
 * Features:
 * - LoRa messaging between two devices
 * - Web interface with Chat, Config, WiFi, and Debug tabs
 * - Real-time E220 register configuration
 * - Persistent flash storage
 * - Serial command interface
 * 
 * Hardware Wiring:
 * E220 RX -> GPIO21 (UART2 RX)
 * E220 TX -> GPIO22 (UART2 TX)
 * E220 M0 -> GPIO2 (Mode control)
 * E220 M1 -> GPIO19 (Mode control)
 * E220 AUX -> GPIO4 (Status input)
 * E220 VCC -> 3.3V (with 10µF capacitor)
 * E220 GND -> GND
 */

#include <Arduino.h>
#include <WiFi.h>
#include <AsyncTCP.h>
#include <ESPAsyncWebServer.h>
#include <LittleFS.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <esp_system.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLEAdvertising.h>
#include <BLE2902.h>
#include <esp_now.h>
#include <esp_wifi.h>

// GPIO Pin assignments for E220 module
#define E220_RX_PIN   21              // UART2 RX from E220
#define E220_TX_PIN   22              // UART2 TX to E220
#define E220_M0_PIN   2               // Mode pin 0 (low = normal, high = config/sleep)
#define E220_M1_PIN   19              // Mode pin 1 (low = normal, high = config/sleep)
#define E220_AUX_PIN  4               // Status pin (high = ready, low = busy)

// UART Configuration
#define UART_BAUD_CONFIG  9600        // E220 config mode ONLY supports 9600 8N1
#define UART_BAUD_NORMAL  9600        // Normal mode baud rate (E220 operational)

AsyncWebServer server(80);
Preferences preferences;

static const size_t CHAT_HISTORY_SIZE = 100;
static const uint32_t ADMIN_SESSION_TTL_MS = 30UL * 60UL * 1000UL;
static const uint32_t ESPNOW_CHAT_MAGIC = 0x45323230UL;  // "E220"
static const uint8_t ESPNOW_CHANNEL = 1;
static const size_t ESPNOW_CHAT_MAX_LEN = 190;

enum OperationType {
  OP_NONE,
  OP_APPLY_CONFIG,
  OP_WIFI_SCAN,
  OP_WIFI_CONNECT
};

enum OperationState {
  OP_STATE_IDLE,
  OP_STATE_PENDING,
  OP_STATE_RUNNING,
  OP_STATE_SUCCESS,
  OP_STATE_ERROR
};

struct {
  char ssid[64];
  char password[64];
  char ap_ssid[64];
  char ap_password[64];
} wifi_config = {"", "", "", ""};

HardwareSerial e220Serial(2);
String chatHistory[CHAT_HISTORY_SIZE];
size_t chatHistoryStart = 0;
size_t chatHistoryCount = 0;
uint32_t chatSequence = 0;
String adminSessionToken = "";
uint32_t adminSessionExpiresAt = 0;

struct {
  OperationType type;
  OperationState state;
  String message;
  String resultJson;
  char wifi_ssid[64];
  char wifi_password[64];
} operationState = {OP_NONE, OP_STATE_IDLE, "", "", "", ""};

bool rebootPending = false;
uint32_t rebootRequestedAt = 0;
bool espNowReady = false;
uint32_t espNowSequence = 0;

struct EspNowChatPacket {
  uint32_t magic;
  uint32_t sequence;
  uint8_t length;
  char message[ESPNOW_CHAT_MAX_LEN + 1];
};

/**
 * Validation Helper Functions
 * 
 * These functions validate configuration parameters before applying them
 * to the E220 module. This prevents invalid configurations from being
 * written to flash, which would require hardware reset.
 */

// Validate frequency is in supported range (850.125 - 930.125 MHz, 900MHz band)
bool isValidFrequency(float freq) {
  return freq >= 850.125f && freq <= 930.125f;
}

// Validate TX power is one of the supported hardware values for E220-900T30D (30/27/24/21 dBm)
bool isValidTxPower(int power) {
  return power == 30 || power == 27 || power == 24 || power == 21;
}

// Validate air data rate code (0-7 maps to 2.4k/4.8k/9.6k/19.2k/38.4k/62.5k kbps)
bool isValidAirRate(int rate) {
  return rate >= 0 && rate <= 7;
}

// Validate subpacket size code (0-3 maps to 200/128/64/32 bytes)
bool isValidSubPacketSize(int size) {
  return size >= 0 && size <= 3;
}

// Validate WOR (Wake-On-Radio) cycle code (0-7 maps to 500ms-4000ms)
bool isValidWORCycle(int cycle) {
  return cycle >= 0 && cycle <= 7;
}

// Validate serial baud rate (must be one of 8 supported values)
bool isValidBaud(int baud) {
  static const int baudTable[] = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};
  for (int i = 0; i < 8; i++) {
    if (baudTable[i] == baud) return true;
  }
  return false;
}

// Validate hex address string format (0xHHLL where H,L are hex digits)
bool isValidHexAddress(const char *addr) {
  if (!addr || strlen(addr) != 6) return false;  // "0xHHLL" = 6 chars
  if (addr[0] != '0' || (addr[1] != 'x' && addr[1] != 'X')) return false;
  for (int i = 2; i < 6; i++) {
    char c = addr[i];
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
      return false;
    }
  }
  return true;
}

// Generate dynamic AP SSID from chip ID (last 3 bytes of MAC address)
// Format: "E220-Chat-AABBCC" where AABBCC is the last 3 bytes of MAC in hex
String generateAPSSID() {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char ssid[32];
  snprintf(ssid, sizeof(ssid), "E220-Chat-%02X%02X%02X", 
           mac[3], mac[4], mac[5]);  // Last 3 bytes of MAC
  return String(ssid);
}

String generateDefaultAPPassword() {
  uint8_t mac[6];
  WiFi.macAddress(mac);
  char password[20];
  snprintf(password, sizeof(password), "e220-%02X%02X%02X", mac[3], mac[4], mac[5]);
  return String(password);
}

void clearChatHistory() {
  chatHistoryStart = 0;
  chatHistoryCount = 0;
}

void addChatHistory(const String &message) {
  size_t index = (chatHistoryStart + chatHistoryCount) % CHAT_HISTORY_SIZE;
  if (chatHistoryCount == CHAT_HISTORY_SIZE) {
    index = chatHistoryStart;
    chatHistoryStart = (chatHistoryStart + 1) % CHAT_HISTORY_SIZE;
  } else {
    chatHistoryCount++;
  }
  chatHistory[index] = message;
  chatSequence++;
}

const String &getChatHistoryItem(size_t logicalIndex) {
  return chatHistory[(chatHistoryStart + logicalIndex) % CHAT_HISTORY_SIZE];
}

const char *operationTypeName(OperationType type) {
  switch (type) {
    case OP_APPLY_CONFIG: return "apply_config";
    case OP_WIFI_SCAN: return "wifi_scan";
    case OP_WIFI_CONNECT: return "wifi_connect";
    default: return "none";
  }
}

const char *operationStateName(OperationState state) {
  switch (state) {
    case OP_STATE_PENDING: return "pending";
    case OP_STATE_RUNNING: return "running";
    case OP_STATE_SUCCESS: return "success";
    case OP_STATE_ERROR: return "error";
    default: return "idle";
  }
}

void clearCompletedOperation() {
  if (operationState.state == OP_STATE_SUCCESS || operationState.state == OP_STATE_ERROR) {
    operationState.type = OP_NONE;
    operationState.state = OP_STATE_IDLE;
    operationState.message = "";
    operationState.resultJson = "";
    operationState.wifi_ssid[0] = '\0';
    operationState.wifi_password[0] = '\0';
  }
}

bool queueOperation(OperationType type, const String &message) {
  if (operationState.state == OP_STATE_PENDING || operationState.state == OP_STATE_RUNNING) {
    return false;
  }
  clearCompletedOperation();
  operationState.type = type;
  operationState.state = OP_STATE_PENDING;
  operationState.message = message;
  operationState.resultJson = "";
  return true;
}

bool isAdminSessionValid() {
  return adminSessionToken.length() > 0 && millis() < adminSessionExpiresAt;
}

void resetAdminSession() {
  adminSessionToken = "";
  adminSessionExpiresAt = 0;
}

String generateAdminToken() {
  char token[33];
  uint32_t r1 = esp_random();
  uint32_t r2 = esp_random();
  snprintf(token, sizeof(token), "%08lx%08lx", (unsigned long)r1, (unsigned long)r2);
  return String(token);
}

bool isAdminAuthorized(AsyncWebServerRequest *request) {
  if (!isAdminSessionValid()) {
    resetAdminSession();
    return false;
  }
  if (!request->hasHeader("X-Admin-Token")) {
    return false;
  }
  String token = request->header("X-Admin-Token");
  return token == adminSessionToken;
}

bool requireAdmin(AsyncWebServerRequest *request) {
  if (isAdminAuthorized(request)) {
    adminSessionExpiresAt = millis() + ADMIN_SESSION_TTL_MS;
    return true;
  }
  request->send(401, "application/json", "{\"error\":\"admin authentication required\"}");
  return false;
}

String buildOperationStatusJson() {
  DynamicJsonDocument doc(1024);
  doc["type"] = operationTypeName(operationState.type);
  doc["state"] = operationStateName(operationState.state);
  doc["message"] = operationState.message;
  if (operationState.resultJson.length() > 0) {
    JsonVariant result = doc["result"];
    DeserializationError error = deserializeJson(result, operationState.resultJson);
    if (error) {
      doc["result_raw"] = operationState.resultJson;
    }
  }
  String response;
  serializeJson(doc, response);
  return response;
}

// Debug log ring buffer - captures Serial output for web debug tab
#define DEBUG_LOG_SIZE 4096
char debugLogBuf[DEBUG_LOG_SIZE];
int debugLogHead = 0;
int debugLogTail = 0;
int debugLogReadPos = 0;  // tracks what web client has already read

void debugLogWrite(const char *str) {
  while (*str) {
    debugLogBuf[debugLogHead] = *str++;
    debugLogHead = (debugLogHead + 1) % DEBUG_LOG_SIZE;
    if (debugLogHead == debugLogTail) {
      debugLogTail = (debugLogTail + 1) % DEBUG_LOG_SIZE;  // overwrite oldest
      if (debugLogReadPos == debugLogTail)
        debugLogReadPos = (debugLogReadPos + 1) % DEBUG_LOG_SIZE;
    }
  }
}

// Custom Print class that tees to Serial AND debug buffer
class DebugPrint : public Print {
public:
  size_t write(uint8_t c) override {
    char buf[2] = {(char)c, 0};
    debugLogWrite(buf);
    return Serial.write(c);
  }
  size_t write(const uint8_t *buffer, size_t size) override {
    for (size_t i = 0; i < size; i++) {
      char buf[2] = {(char)buffer[i], 0};
      debugLogWrite(buf);
    }
    return Serial.write(buffer, size);
  }
};

DebugPrint dbg;


void onEspNowSent(const uint8_t *mac_addr, esp_now_send_status_t status) {
  dbg.printf("[ESPNOW] Broadcast send %s\n", status == ESP_NOW_SEND_SUCCESS ? "queued" : "failed");
}

void onEspNowReceived(const uint8_t *mac_addr, const uint8_t *incomingData, int len) {
  if (len != sizeof(EspNowChatPacket)) {
    dbg.printf("[ESPNOW] Ignoring packet with unexpected length %d\n", len);
    return;
  }

  EspNowChatPacket packet;
  memcpy(&packet, incomingData, sizeof(packet));
  if (packet.magic != ESPNOW_CHAT_MAGIC || packet.length == 0 || packet.length > ESPNOW_CHAT_MAX_LEN) {
    dbg.println("[ESPNOW] Ignoring non-chat packet");
    return;
  }

  packet.message[packet.length] = '\0';
  String message(packet.message);
  message.trim();
  if (message.length() == 0) return;

  dbg.printf("[ESPNOW] RX from %02X:%02X:%02X:%02X:%02X:%02X: %s\n",
             mac_addr[0], mac_addr[1], mac_addr[2], mac_addr[3], mac_addr[4], mac_addr[5],
             message.c_str());
  addChatHistory("[RX] " + message);
}

void setupEspNowBridge() {
  WiFi.mode(WIFI_STA);
  WiFi.disconnect(false, false);
  esp_wifi_set_promiscuous(true);
  esp_wifi_set_channel(ESPNOW_CHANNEL, WIFI_SECOND_CHAN_NONE);
  esp_wifi_set_promiscuous(false);

  esp_err_t initResult = esp_now_init();
  if (initResult != ESP_OK) {
    dbg.printf("[ESPNOW] init failed: %d\n", initResult);
    espNowReady = false;
    return;
  }

  esp_now_register_send_cb(onEspNowSent);
  esp_now_register_recv_cb(onEspNowReceived);

  esp_now_peer_info_t peerInfo = {};
  memset(peerInfo.peer_addr, 0xFF, 6);
  peerInfo.channel = ESPNOW_CHANNEL;
  peerInfo.encrypt = false;
  if (!esp_now_is_peer_exist(peerInfo.peer_addr)) {
    esp_err_t peerResult = esp_now_add_peer(&peerInfo);
    if (peerResult != ESP_OK) {
      dbg.printf("[ESPNOW] broadcast peer add failed: %d\n", peerResult);
      espNowReady = false;
      return;
    }
  }

  espNowReady = true;
  dbg.printf("[ESPNOW] Chat bridge ready on channel %u\n", ESPNOW_CHANNEL);
}

void sendEspNowChat(const String &message) {
  if (!espNowReady) return;
  EspNowChatPacket packet = {};
  packet.magic = ESPNOW_CHAT_MAGIC;
  packet.sequence = ++espNowSequence;
  packet.length = min((size_t)message.length(), ESPNOW_CHAT_MAX_LEN);
  memcpy(packet.message, message.c_str(), packet.length);
  packet.message[packet.length] = '\0';

  static const uint8_t broadcastAddress[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};
  esp_err_t result = esp_now_send(broadcastAddress, (const uint8_t *)&packet, sizeof(packet));
  if (result == ESP_OK) {
    dbg.printf("[ESPNOW] TX broadcast seq=%u len=%u\n", packet.sequence, packet.length);
  } else {
    dbg.printf("[ESPNOW] TX broadcast failed: %d\n", result);
  }
}

// TX queue: messages queued from web handler, sent from loop()
// This avoids blocking the async_tcp task and triggering the watchdog
String txQueue = "";
bool txPending = false;

// Diagnostics: track communication errors
uint32_t e220_timeout_count = 0;  // Count of E220 AUX timeouts
uint32_t e220_rx_errors = 0;      // Count of RX protocol errors
uint32_t e220_tx_errors = 0;      // Count of TX protocol errors

static int lastRssi = 0;  // last RSSI value in dBm
static int lastAmbientNoiseRssi = 0;  // last ambient noise RSSI in dBm
static bool hasAmbientNoiseRssi = false;

int readAmbientNoiseRssi();
String buildTxHistoryEntry(const String &message);

/**
 * E220 Configuration Structure
 * 
 * Maps directly to E220-900 LoRa module hardware registers (00h-07h).
 * Matches E220 datasheet Section 6.2-6.3.
 * 
 * When modified, values are written to the E220 in CONFIG mode (M0=1, M1=1).
 * Some values are persistent (written to flash), others are RAM-only.
 */
struct {
  // Frequency (MHz) - derived from REG2 (channel): 850.125 + CH, where CH=0-80 (900MHz band)
  float freq;
  
  // TX Power (dBm) - REG1[1:0]: E220-900T30D uses 30/27/24/21 dBm
  int txpower;
  
  // Serial baud rate (bps) - REG0[7:5]: must match ESP32 UART speed in normal mode
  int baud;
  
  // Module address - ADDH(00h) + ADDL(01h): "0xHHLL" format, used for filtering RX
  char addr[8];
  
  // Destination address - for fixed-point TX mode (not a register, user config only)
  char dest[8];
  
  // Air data rate - REG0[2:0]: 0=2.4k, 1=4.8k, 2=9.6k(default), 3=19.2k, 4=38.4k, 5=62.5k
  int airrate;
  
  // Subpacket size - REG1[7:6]: 0=200B(default), 1=128B, 2=64B, 3=32B
  int subpkt;
  
  // UART parity - REG0[4:3]: 0=8N1(default), 1=8O1, 2=8E1
  int parity;
  
  // TX mode - REG3[6]: 0=transparent(default), 1=fixed-point addressing
  int txmode;
  
  // RSSI ambient noise - REG1[5]: 0=disabled(default), 1=enable ambient noise RSSI reporting
  int rssi_noise;
  
  // RSSI byte in RX - REG3[7]: 0=disabled(default), 1=append RSSI dBm byte to RX data
  int rssi_byte;
  
  // Listen Before Receive RSSI threshold - custom app config field
  int lbr_rssi;
  
  // Listen Before Receive timeout - custom app config field
  int lbr_timeout;
  
  // UART RX timeout setting used by the app
  int urxt;
  
  // Listen Before Talk - REG3[4]: 0=disabled(default), 1=enable LBT to reduce collisions
  int lbt;
  
  // Wake-On-Radio cycle - REG3[2:0]: period = (1+WOR)*500ms, 0=500ms..7=4000ms
  int wor_cycle;
  
  // Encryption key high byte - REG06h (write-only, not readable, key persists in flash)
  int crypt_h;
  
  // Encryption key low byte - REG07h (write-only, not readable, key persists in flash)
  int crypt_l;
  
  // Save type - 0=C2(RAM only), 1=C0(save to flash, survives reboot)
  int savetype;
  
} e220_config = {
  930.125,  // freq: 930.125 MHz (CH80, end of band)
  21,       // txpower: 21 dBm default
  9600,     // baud: 9600 bps
  "0x0000", // addr: default address
  "0xFFFF", // dest: broadcast
  2,        // airrate: 9.6 kbps (good range/speed balance)
  0,        // subpkt: 200 bytes (default)
  0,        // parity: 8N1 (default)
  0,        // txmode: transparent (default)
  0,        // rssi_noise: disabled
  0,        // rssi_byte: disabled
  -55,      // lbr_rssi: app default threshold
  2000,     // lbr_timeout: app default timeout
  3,        // urxt: app default UART RX timeout
  0,        // lbt: disabled
  3,        // wor_cycle: 2000ms period (1+3)*500
  0,        // crypt_h: no encryption
  0,        // crypt_l: no encryption
  0         // savetype: RAM only (0=C2)
};

void setE220Mode(uint8_t mode) {
  if (mode == 1) {
    digitalWrite(E220_M0_PIN, HIGH);
    digitalWrite(E220_M1_PIN, LOW);
  } else {
    digitalWrite(E220_M0_PIN, LOW);
    digitalWrite(E220_M1_PIN, LOW);
  }
  delay(50);
}

// AUX Pin Monitoring (GPIO 4)
// HIGH = Idle/Ready, LOW = Busy (TX/RX/Processing)
// Per E220 Manual Section 5.2: typical delay 3ms when idle
bool isE220Ready() {
  return digitalRead(E220_AUX_PIN) == HIGH;
}

// Wait for E220 to become ready with timeout protection
// timeout_ms: max milliseconds to wait (default 5000ms = 5 seconds)
// returns: true if ready, false if timeout
bool waitE220Ready(uint32_t timeout_ms = 5000) {
  uint32_t start = millis();
  while (millis() - start < timeout_ms) {
    if (isE220Ready()) {
      return true;
    }
    delay(10);  // Poll every 10ms to avoid hogging CPU
  }
  e220_timeout_count++;  // Track timeout for diagnostics
  dbg.printf("[E220] Timeout waiting for AUX ready after %u ms (total timeouts: %u)\n", timeout_ms, e220_timeout_count);
  return false;
}

// Wait for E220 to become BUSY (AUX goes LOW)
// Used to detect when module has accepted data for transmission
// timeout_ms: max milliseconds to wait (default 1000ms)
// returns: true if busy detected, false if timeout
bool waitE220Busy(uint32_t timeout_ms = 1000) {
  uint32_t start = millis();
  while (millis() - start < timeout_ms) {
    if (!isE220Ready()) {
      return true;
    }
    delay(5);
  }
  dbg.printf("[E220] Timeout waiting for AUX busy after %u ms\n", timeout_ms);
  return false;
}

// Change UART baud rate (switches ESP32 serial port only, doesn't change E220 module)
void setE220UARTBaud(int baud) {
  dbg.printf("[E220] Changing UART baud from %d to %d\n", UART_BAUD_CONFIG, baud);
  e220Serial.end();
  delay(50);
  e220Serial.begin(baud, SERIAL_8N1, E220_RX_PIN, E220_TX_PIN);
  delay(50);
}

uint8_t baudToReg(int baud) {
  switch(baud) {
    case 1200:   return 0;
    case 2400:   return 1;
    case 4800:   return 2;
    case 9600:   return 3;
    case 19200:  return 4;
    case 38400:  return 5;
    case 57600:  return 6;
    case 115200: return 7;
    default:     return 3; // 9600
  }
}

uint8_t txpowerToReg(int dbm) {
  switch(dbm) {
    case 30: return 0;
    case 27: return 1;
    case 24: return 2;
    case 21: return 3;
    default: return 3; // 21 dBm default
  }
}

void readE220Config() {
  // Ensure serial is at 9600 for config mode (manual: config mode ONLY supports 9600 8N1)
  e220Serial.end();
  delay(50);
  e220Serial.begin(9600, SERIAL_8N1, E220_RX_PIN, E220_TX_PIN);
  delay(50);
  
  // Enter CONFIG mode (M0=1, M1=1) per manual Section 5.1.3
  digitalWrite(E220_M0_PIN, HIGH);
  digitalWrite(E220_M1_PIN, HIGH);
  
  // Per manual Section 5.2.4: mode switch takes 9-11ms, AUX goes LOW during switch
  // Wait for AUX to go LOW first (indicates switch has started)
  delay(15);  // Ensure mode switch has begun
  
  // Now wait for AUX to go HIGH (module ready for config commands).
  // Some ESP32/E220 carrier wiring does not expose AUX reliably; do not abort
  // the register transaction solely because AUX stayed low. Use a conservative
  // fixed delay fallback so config reads/writes can still reach the module.
  if (!waitE220Ready(2000)) {
    dbg.println("[E220] WARNING: AUX did not report ready in config mode; continuing with timed fallback");
    delay(500);
  }
  
  // Extra settling delay after AUX/fallback wait
  delay(50);
  
  // Flush any stale data
  while(e220Serial.available()) e220Serial.read();
  
  // Read registers 0x00-0x07: CMD(0xC1) + START(0x00) + LEN(0x08)
  // Manual Section 6.1: Read command = C1 + start_addr + length
  // Response = C1 + start_addr + length + data bytes
  uint8_t readCmd[3] = {0xC1, 0x00, 0x06};
  dbg.printf("[E220] Sending read cmd: %02X %02X %02X\n", readCmd[0], readCmd[1], readCmd[2]);
  e220Serial.write(readCmd, 3);
  e220Serial.flush();  // Wait for TX to complete
  
  // Wait for response: 3 header bytes + 6 data bytes = 9 bytes total
  uint32_t timeout = millis() + 1000;
  while (e220Serial.available() < 9 && millis() < timeout) {
    delay(10);
  }
  
  int avail = e220Serial.available();
  dbg.printf("[E220] Got %d bytes in response\n", avail);
  
  // Response: 0xC1 + START + LEN + ADDH + ADDL + REG0 + REG1 + REG2 + REG3
  if (avail >= 9) {
    uint8_t hdr = e220Serial.read(); // 0xC1
    uint8_t start = e220Serial.read(); // 0x00
    uint8_t len = e220Serial.read(); // 0x06
    uint8_t addh = e220Serial.read();
    uint8_t addl = e220Serial.read();
    uint8_t reg0 = e220Serial.read();
    uint8_t reg1 = e220Serial.read();
    uint8_t reg2 = e220Serial.read(); // channel
    uint8_t reg3 = e220Serial.read();
    
    // Update e220_config struct from register values so web UI stays in sync
    snprintf(e220_config.addr, sizeof(e220_config.addr), "0x%02X%02X", addh, addl);
    e220_config.freq = 850.125 + reg2;
    e220_config.airrate = reg0 & 0x07;
    e220_config.parity = (reg0 >> 3) & 0x03;
    
    // Reverse baud from register bits
    static const int baudTable[] = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};
    e220_config.baud = baudTable[(reg0 >> 5) & 0x07];
    
    e220_config.subpkt = (reg1 >> 6) & 0x03;
    e220_config.rssi_noise = (reg1 >> 5) & 0x01;
    // Reverse TX power from register bits for the E220-900T30D
    static const int powerTable30[] = {30, 27, 24, 21};
    e220_config.txpower = powerTable30[reg1 & 0x03];
    
    e220_config.rssi_byte = (reg3 >> 7) & 0x01;
    e220_config.txmode = (reg3 >> 6) & 0x01;
    e220_config.lbt = (reg3 >> 4) & 0x01;
    e220_config.wor_cycle = reg3 & 0x07;
    
    dbg.println("[E220] Read config from module:");
    dbg.printf("  HDR=0x%02X START=0x%02X LEN=0x%02X\n", hdr, start, len);
    dbg.printf("  ADDH=0x%02X ADDL=0x%02X\n", addh, addl);
    dbg.printf("  REG0=0x%02X REG1=0x%02X REG2=0x%02X REG3=0x%02X\n", reg0, reg1, reg2, reg3);
    dbg.printf("  Channel=%d -> Freq=%.3f MHz\n", reg2, e220_config.freq);
    dbg.printf("  TX Power=%d dBm\n", e220_config.txpower);
    dbg.printf("  Air Rate=%d (bits=%d)\n", e220_config.airrate, reg0 & 0x07);
    dbg.printf("  Baud=%d\n", e220_config.baud);
    dbg.printf("  TX Mode=%s\n", e220_config.txmode ? "Fixed" : "Transparent");
  } else {
    dbg.printf("[E220] Read failed, got %d bytes\n", avail);
    while(e220Serial.available()) {
      dbg.printf("  byte: 0x%02X\n", e220Serial.read());
    }
    dbg.println("[E220] Check: M0->GPIO2, M1->GPIO19, AUX->GPIO4, TX->GPIO22, RX->GPIO21");
    dbg.printf("[E220] AUX pin state: %s\n", digitalRead(E220_AUX_PIN) ? "HIGH" : "LOW");
  }
  
  // Return to NORMAL mode and switch ESP32 UART to the module's configured
  // normal-mode baud. Config mode is always 9600, but RF data mode follows REG0.
  setE220Mode(0);
  delay(200);
  setE220UARTBaud(e220_config.baud);
}

void applyE220Config() {
  // Ensure serial is at 9600 for config mode (manual: config mode ONLY supports 9600 8N1)
  e220Serial.end();
  delay(50);
  e220Serial.begin(9600, SERIAL_8N1, E220_RX_PIN, E220_TX_PIN);
  delay(50);
  
  // Enter CONFIG mode (M0=1, M1=1) - serial port MUST be 9600 8N1 in this mode
  digitalWrite(E220_M0_PIN, HIGH);
  digitalWrite(E220_M1_PIN, HIGH);
  
  // Per manual Section 5.2.4: mode switch takes 9-11ms
  delay(15);
  
  // Wait for AUX to go HIGH per manual Section 5.2. If AUX is not wired or is
  // held low, continue after a fixed delay rather than skipping the write; the
  // module can still accept config commands after the mode-settle interval.
  if (!waitE220Ready(2000)) {
    dbg.println("[E220] WARNING: AUX did not report ready before config write; continuing with timed fallback");
    delay(500);
  }
  
  // Extra settling delay
  delay(50);
  
  // Flush any stale data
  while(e220Serial.available()) e220Serial.read();
  
  // Parse address from hex string "0xHHLL" -> ADDH, ADDL
  uint16_t addr = (uint16_t)strtol(e220_config.addr, NULL, 16);
  uint8_t addh = (addr >> 8) & 0xFF;
  uint8_t addl = addr & 0xFF;
  
  // REG0 (02h): [7:5] UART baud, [4:3] parity, [2:0] air data rate
  uint8_t reg0 = (baudToReg(e220_config.baud) << 5) | 
                 ((e220_config.parity & 0x03) << 3) | 
                 (e220_config.airrate & 0x07);
  
  // REG1 (03h): [7:6] subpacket, [5] RSSI ambient noise, [4:3] reserved, [2] soft switch, [1:0] TX power
  uint8_t reg1 = ((e220_config.subpkt & 0x03) << 6) | 
                 ((e220_config.rssi_noise & 0x01) << 5) |
                 (txpowerToReg(e220_config.txpower) & 0x03);
  
  // REG2 (04h): Channel number
  // E220-900T30D: freq = 850.125 + CH (MHz), CH = 0-80
  uint8_t reg2 = (uint8_t)(e220_config.freq - 850.125);
  if (reg2 > 80) reg2 = 80;
  
  // REG3 (05h): [7] RSSI byte, [6] TX method, [5] reserved, [4] LBT, [3] reserved, [2:0] WOR cycle
  uint8_t reg3 = ((e220_config.rssi_byte & 0x01) << 7) |
                 ((e220_config.txmode & 0x01) << 6) |
                 ((e220_config.lbt & 0x01) << 4) |
                 (e220_config.wor_cycle & 0x07);
  
  // CRYPT (06h-07h): Encryption key
  uint8_t crypt_h = (uint8_t)(e220_config.crypt_h & 0xFF);
  uint8_t crypt_l = (uint8_t)(e220_config.crypt_l & 0xFF);
  
  // Command: 0xC0 (save to flash) or 0xC2 (temp/RAM only)
  uint8_t cmd = (e220_config.savetype == 1) ? 0xC0 : 0xC2;
  
  // Write all 8 registers: CMD + START(0x00) + LEN(0x08) + ADDH + ADDL + REG0-REG3 + CRYPT_H + CRYPT_L
  uint8_t packet[11] = {cmd, 0x00, 0x08, addh, addl, reg0, reg1, reg2, reg3, crypt_h, crypt_l};
  
  dbg.println("[E220] Writing config:");
  dbg.printf("  CMD=0x%02X (%s)\n", cmd, cmd == 0xC0 ? "SAVE TO FLASH" : "RAM ONLY");
  dbg.printf("  ADDH=0x%02X ADDL=0x%02X (addr=%s)\n", addh, addl, e220_config.addr);
  dbg.printf("  REG0=0x%02X: baud=%d parity=%d airrate=%d\n", reg0, e220_config.baud, e220_config.parity, e220_config.airrate);
  dbg.printf("  REG1=0x%02X: subpkt=%d rssi_noise=%d txpower=%d dBm\n", reg1, e220_config.subpkt, e220_config.rssi_noise, e220_config.txpower);
  dbg.printf("  REG2=0x%02X: channel=%d freq=%.3f MHz\n", reg2, reg2, 850.125 + reg2);
  dbg.printf("  REG3=0x%02X: rssi_byte=%d txmode=%s lbt=%d wor=%d\n", reg3, e220_config.rssi_byte, e220_config.txmode ? "FIXED" : "TRANSPARENT", e220_config.lbt, e220_config.wor_cycle);
  dbg.printf("  CRYPT=0x%02X%02X\n", crypt_h, crypt_l);
  
  dbg.printf("[E220] Sending %d bytes: ", 11);
  for (int i = 0; i < 11; i++) dbg.printf("%02X ", packet[i]);
  dbg.println();
  
  e220Serial.write(packet, 11);
  e220Serial.flush();
  
  // Wait for AUX to indicate processing, then ready
  delay(100);
  waitE220Ready(2000);
  delay(200);
  
  // Read response: E220 echoes C1 + start + len + data
  uint32_t respTimeout = millis() + 1000;
  while (e220Serial.available() < 3 && millis() < respTimeout) {
    delay(10);
  }
  
  int avail = e220Serial.available();
  if (avail > 0) {
    dbg.printf("[E220] Response (%d bytes):", avail);
    uint8_t first = 0;
    while(e220Serial.available()) {
      uint8_t b = e220Serial.read();
      if (!first) first = b;
      dbg.printf(" 0x%02X", b);
    }
    dbg.println();
    if (first == 0xC1) {
      dbg.println("[E220] Config write SUCCESS (C1 acknowledged)");
    } else if (first == 0xFF) {
      dbg.println("[E220] Config write FAILED (FF FF FF = format error!)");
    }
  } else {
    dbg.println("[E220] WARNING: No response from module!");
  }
  
  delay(100);
  
  // Return to NORMAL mode (M0=0, M1=0)
  // Per manual Section 5.2.4 note 3: switching FROM config mode causes the module
  // to reset user parameters. AUX goes LOW during this reset. MUST wait for it.
  dbg.println("[E220] Switching back to normal mode (module will reset params)...");
  digitalWrite(E220_M0_PIN, LOW);
  digitalWrite(E220_M1_PIN, LOW);
  
  // Wait for mode switch to begin (9-11ms per manual)
  delay(50);
  
  // Wait for AUX HIGH - module has finished resetting with new params
  if (!waitE220Ready(5000)) {
    dbg.println("[E220] WARNING: Module not ready after config apply! May need power cycle.");
  }
  
  // Extra settling time after param reset
  delay(200);
  
  dbg.println("[E220] Config applied, back to normal mode");
  setE220UARTBaud(e220_config.baud);
  
  // Read back to verify
  delay(200);
  readE220Config();
}

void setupE220() {
  pinMode(E220_M0_PIN, OUTPUT);
  pinMode(E220_M1_PIN, OUTPUT);
  pinMode(E220_AUX_PIN, INPUT);  // AUX is a status input (HIGH=ready, LOW=busy)
  // Start with config baud (9600) - will switch to normal baud after config
  e220Serial.begin(UART_BAUD_CONFIG, SERIAL_8N1, E220_RX_PIN, E220_TX_PIN);
  setE220Mode(0);
  delay(100);  // Wait for module startup (T1 = ~16ms per manual)
  if (waitE220Ready(1000)) {
    dbg.println("[E220] Init - AUX ready");
  } else {
    dbg.println("[E220] Init - WARNING: AUX not ready (module may not be responding)");
  }
}

void setupWiFi() {
  preferences.begin("wifi", false);

  // Load saved STA credentials (check if key exists first to avoid NVS errors)
  String savedSSID = preferences.isKey("sta_ssid") ? preferences.getString("sta_ssid", "") : "";
  String savedPass = preferences.isKey("sta_pass") ? preferences.getString("sta_pass", "") : "";
  strlcpy(wifi_config.ssid, savedSSID.c_str(), sizeof(wifi_config.ssid));
  strlcpy(wifi_config.password, savedPass.c_str(), sizeof(wifi_config.password));

  // Load saved AP settings with defaults
  // AP SSID is now dynamically generated from chip ID (last 3 bytes of MAC)
  // This ensures each device has a unique SSID based on its hardware
  String apSSID = generateAPSSID();
  String apPass;
  if (preferences.isKey("ap_pass")) {
    apPass = preferences.getString("ap_pass", generateDefaultAPPassword());
  } else {
    apPass = generateDefaultAPPassword();
    preferences.putString("ap_pass", apPass);
    dbg.printf("[WiFi] Generated default AP password: %s\n", apPass.c_str());
  }
  // Note: AP SSID is no longer persisted since it's always derived from chip ID
  strlcpy(wifi_config.ap_ssid, apSSID.c_str(), sizeof(wifi_config.ap_ssid));
  strlcpy(wifi_config.ap_password, apPass.c_str(), sizeof(wifi_config.ap_password));

  bool wifiEnabled = preferences.isKey("wifi_enabled") ? preferences.getBool("wifi_enabled", true) : true;
  if (wifiEnabled) {
    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(wifi_config.ap_ssid, wifi_config.ap_password);
    dbg.print("[WiFi] AP SSID: ");
    dbg.println(wifi_config.ap_ssid);
    dbg.print("[WiFi] AP password: ");
    dbg.println(wifi_config.ap_password);
    dbg.print("[WiFi] AP IP: ");
    dbg.println(WiFi.softAPIP());

    // If saved STA credentials exist, attempt connection
    if (strlen(wifi_config.ssid) > 0) {
      dbg.printf("[WiFi] Connecting to '%s'...\n", wifi_config.ssid);
      WiFi.begin(wifi_config.ssid, wifi_config.password);
      unsigned long start = millis();
      while (WiFi.status() != WL_CONNECTED && millis() - start < 10000) {
        delay(250);
        dbg.print(".");
      }
      dbg.println();
      if (WiFi.status() == WL_CONNECTED) {
        dbg.print("[WiFi] STA connected, IP: ");
        dbg.println(WiFi.localIP());
      } else {
        dbg.println("[WiFi] STA connection failed, staying in AP+STA mode for scanning");
      }
    } else {
      dbg.println("[WiFi] No saved STA credentials, AP+STA mode for scanning");
    }
  } else {
    WiFi.mode(WIFI_OFF);
    dbg.println("[WiFi] Disabled at boot by saved preference");
  }
}

void setWifiEnabled(bool enabled) {
  preferences.putBool("wifi_enabled", enabled);
  if (!enabled) {
    WiFi.disconnect(true);
    WiFi.softAPdisconnect(true);
    WiFi.mode(WIFI_OFF);
    dbg.println("[WiFi] Disabled via API");
    return;
  }

  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(wifi_config.ap_ssid, wifi_config.ap_password);
  dbg.print("[WiFi] AP SSID: ");
  dbg.println(wifi_config.ap_ssid);
  dbg.print("[WiFi] AP password: ");
  dbg.println(wifi_config.ap_password);
  dbg.print("[WiFi] AP IP: ");
  dbg.println(WiFi.softAPIP());

  if (strlen(wifi_config.ssid) > 0) {
    dbg.printf("[WiFi] Connecting to '%s'...\n", wifi_config.ssid);
    WiFi.begin(wifi_config.ssid, wifi_config.password);
  } else {
    dbg.println("[WiFi] No saved STA credentials, AP+STA mode for scanning");
  }
}

int readAmbientNoiseRssi() {
  if (!e220_config.rssi_noise) {
    hasAmbientNoiseRssi = false;
    return 0;
  }

  while (e220Serial.available()) e220Serial.read();

  // Once RSSI ambient noise is enabled, the manual allows runtime register reads in transmit mode.
  uint8_t readCmd[6] = {0xC0, 0xC1, 0xC2, 0xC3, 0x00, 0x01};
  e220Serial.write(readCmd, sizeof(readCmd));
  e220Serial.flush();

  uint32_t timeout = millis() + 250;
  while (e220Serial.available() < 4 && millis() < timeout) {
    delay(5);
  }

  if (e220Serial.available() < 4) {
    hasAmbientNoiseRssi = false;
    dbg.println("[RSSI] Ambient noise read timed out");
    return 0;
  }

  uint8_t hdr = e220Serial.read();
  uint8_t start = e220Serial.read();
  uint8_t len = e220Serial.read();
  uint8_t raw = e220Serial.read();
  if (hdr != 0xC1 || start != 0x00 || len != 0x01) {
    hasAmbientNoiseRssi = false;
    dbg.printf("[RSSI] Ambient noise read invalid header: %02X %02X %02X\n", hdr, start, len);
    while (e220Serial.available()) e220Serial.read();
    return 0;
  }

  lastAmbientNoiseRssi = -(256 - raw);
  hasAmbientNoiseRssi = true;
  dbg.printf("[RSSI] Ambient noise raw=0x%02X -> %d dBm\n", raw, lastAmbientNoiseRssi);
  while (e220Serial.available()) e220Serial.read();
  return lastAmbientNoiseRssi;
}

String buildTxHistoryEntry(const String &message) {
  return "[TX] " + message;
}

#if 0
void setupFS() {
  if (!LittleFS.begin(true)) {
    dbg.println("[FS] Mount failed");
    return;
  }
  dbg.println("[FS] Ready");
  
  // Debug: list files
  File root = LittleFS.open("/");
  File file = root.openNextFile();
  while(file) {
    dbg.print("[FS] Found: ");
    dbg.println(file.name());
    file = root.openNextFile();
  }
}

void setupWebRoutes() {
  // Serve index.html (with gzip support)
  server.on("/", HTTP_GET, [](AsyncWebServerRequest *request) {
    dbg.println("[Web] Request for /");
    
    // Check if client accepts gzip
    bool acceptGzip = false;
    if (request->hasHeader("Accept-Encoding")) {
      String encoding = request->header("Accept-Encoding");
      if (encoding.indexOf("gzip") != -1) {
        acceptGzip = true;
      }
    }
    
    // Try to serve compressed version first
    if (acceptGzip && LittleFS.exists("/index.html.gz")) {
      dbg.println("[Web] Serving compressed HTML");
      AsyncWebServerResponse *response = request->beginResponse(LittleFS, "/index.html.gz", "text/html");
      response->addHeader("Content-Encoding", "gzip");
      response->addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
      response->addHeader("Pragma", "no-cache");
      response->addHeader("Expires", "0");
      request->send(response);
    } else if (LittleFS.exists("/index.html")) {
      dbg.println("[Web] Serving uncompressed HTML");
      AsyncWebServerResponse *response = request->beginResponse(LittleFS, "/index.html", "text/html");
      response->addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
      response->addHeader("Pragma", "no-cache");
      response->addHeader("Expires", "0");
      request->send(response);
    } else {
      request->send(404, "text/plain", "index.html not found");
      dbg.println("[Web] HTML not found!");
    }
  });

  // Chat history API - uses ArduinoJson for proper escaping
  server.on("/api/chat", HTTP_GET, [](AsyncWebServerRequest *request) {
    DynamicJsonDocument doc(16384);
    JsonArray history = doc.createNestedArray("history");
    doc["total_messages"] = chatSequence;
    doc["history_size"] = chatHistoryCount;
    for (size_t i = 0; i < chatHistoryCount; i++) {
      history.add(getChatHistoryItem(i));
    }
    String json;
    serializeJson(doc, json);
    request->send(200, "application/json", json);
  });

  // Send message API
  // Body is reassembled then queued for transmission in loop() to avoid
  // blocking the async_tcp task (which triggers watchdog on large messages)
  static String sendBodyBuffer;
  server.on("/api/send", HTTP_POST,
  [](AsyncWebServerRequest *request) {
    if (sendBodyBuffer.length() == 0) {
      request->send(400, "application/json", "{\"error\":\"empty body\"}");
      return;
    }
    
    DynamicJsonDocument doc(sendBodyBuffer.length() + 128);
    DeserializationError error = deserializeJson(doc, sendBodyBuffer);
    sendBodyBuffer = "";
    
    if (error || !doc.containsKey("message")) {
      request->send(400, "application/json", "{\"error\":\"no message\"}");
      return;
    }
    
    String msg = doc["message"].as<String>();
    
    if (txPending) {
      request->send(429, "application/json", "{\"error\":\"TX busy, wait for previous message\"}");
      return;
    }
    
    // Validate message length
    if (msg.length() == 0) {
      request->send(400, "application/json", "{\"error\":\"Message cannot be empty\"}");
      return;
    }
    
    if (msg.length() > 2000) {
      request->send(413, "application/json", "{\"error\":\"Message too large (max 2000 bytes)\"}");
      dbg.printf("[TX] Message rejected: %d bytes exceeds max\n", msg.length());
      return;
    }
    
    // Queue for loop() - no blocking here
    txQueue = msg;
    txPending = true;
    
    // Add to history (ring buffer - wrap around if needed)
    addChatHistory(buildTxHistoryEntry(msg));
    
    request->send(200, "application/json", "{\"status\":\"ok\",\"message\":\"Message queued for transmission\"}");
    dbg.printf("[TX] Queued (%d bytes)\n", msg.length());
  }, NULL,
  [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
    if (index == 0) {
      sendBodyBuffer = "";
      sendBodyBuffer.reserve(total);
    }
    sendBodyBuffer += String((char*)data, len);
  });

  // Get config API
  server.on("/api/config", HTTP_GET, [](AsyncWebServerRequest *request) {
    DynamicJsonDocument doc(512);
    JsonObject config = doc.createNestedObject("config");
    config["freq"] = e220_config.freq;
    config["txpower"] = e220_config.txpower;
    config["baud"] = e220_config.baud;
    config["addr"] = e220_config.addr;
    config["dest"] = e220_config.dest;
    config["airrate"] = e220_config.airrate;
    config["subpkt"] = e220_config.subpkt;
    config["parity"] = e220_config.parity;
    config["txmode"] = e220_config.txmode;
    config["rssi_noise"] = e220_config.rssi_noise;
    config["rssi_byte"] = e220_config.rssi_byte;
    config["lbt"] = e220_config.lbt;
    config["wor_cycle"] = e220_config.wor_cycle;
    config["crypt_h"] = e220_config.crypt_h;
    config["crypt_l"] = e220_config.crypt_l;
    config["savetype"] = e220_config.savetype;
    
    String response;
    serializeJson(doc, response);
    request->send(200, "application/json", response);
  });

  // Save config API - with validation
  server.on("/api/config", HTTP_POST, [](AsyncWebServerRequest *request) {}, NULL,
  [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
    if (!requireAdmin(request)) return;
    DynamicJsonDocument doc(512);
    DeserializationError error = deserializeJson(doc, (const char*)data, len);
    
    if (error) {
      request->send(400, "application/json", "{\"error\":\"JSON parse error\"}");
      return;
    }
    
    // Validate and update config - matches actual E220 registers
    if (doc.containsKey("freq")) {
      float freq = doc["freq"];
      if (!isValidFrequency(freq)) {
        dbg.printf("[CONFIG] Invalid frequency: %.3f (range: 850.125-930.125)\n", freq);
        request->send(400, "application/json", "{\"error\":\"Invalid frequency (850.125-930.125 MHz)\"}");
        return;
      }
      e220_config.freq = freq;
    }
    
    if (doc.containsKey("txpower")) {
      int power = doc["txpower"];
      if (!isValidTxPower(power)) {
        dbg.printf("[CONFIG] Invalid TX power: %d (valid: 30,27,24,21 dBm)\n", power);
        request->send(400, "application/json", "{\"error\":\"Invalid TX power (30,27,24,21 dBm)\"}");
        return;
      }
      e220_config.txpower = power;
    }
    
    if (doc.containsKey("baud")) {
      int baud = doc["baud"];
      if (!isValidBaud(baud)) {
        dbg.printf("[CONFIG] Invalid baud rate: %d\n", baud);
        request->send(400, "application/json", "{\"error\":\"Invalid baud rate\"}");
        return;
      }
      e220_config.baud = baud;
    }
    
    if (doc.containsKey("addr")) {
      const char *addr = doc["addr"];
      if (!isValidHexAddress(addr)) {
        dbg.printf("[CONFIG] Invalid address format: %s (must be 0xHHLL)\n", addr);
        request->send(400, "application/json", "{\"error\":\"Invalid address format (use 0xHHLL)\"}");
        return;
      }
      strlcpy(e220_config.addr, addr, sizeof(e220_config.addr));
    }
    if (doc.containsKey("dest")) {
      const char *dest = doc["dest"];
      if (!isValidHexAddress(dest)) {
        dbg.printf("[CONFIG] Invalid destination format: %s (must be 0xHHLL)\n", dest);
        request->send(400, "application/json", "{\"error\":\"Invalid destination format (use 0xHHLL)\"}");
        return;
      }
      strlcpy(e220_config.dest, dest, sizeof(e220_config.dest));
    }
    
    if (doc.containsKey("airrate")) {
      int rate = doc["airrate"];
      if (!isValidAirRate(rate)) {
        dbg.printf("[CONFIG] Invalid air rate: %d\n", rate);
        request->send(400, "application/json", "{\"error\":\"Invalid air rate (0-7)\"}");
        return;
      }
      e220_config.airrate = rate;
    }
    
    if (doc.containsKey("subpkt")) {
      int subpkt = doc["subpkt"];
      if (!isValidSubPacketSize(subpkt)) {
        dbg.printf("[CONFIG] Invalid subpacket size: %d\n", subpkt);
        request->send(400, "application/json", "{\"error\":\"Invalid subpacket size (0-3)\"}");
        return;
      }
      e220_config.subpkt = subpkt;
    }
    
    if (doc.containsKey("parity")) e220_config.parity = doc["parity"];
    if (doc.containsKey("txmode")) e220_config.txmode = doc["txmode"];
    if (doc.containsKey("rssi_noise")) e220_config.rssi_noise = doc["rssi_noise"];
    if (doc.containsKey("rssi_byte")) e220_config.rssi_byte = doc["rssi_byte"];
    if (doc.containsKey("lbt")) e220_config.lbt = doc["lbt"];
    
    if (doc.containsKey("wor_cycle")) {
      int wor = doc["wor_cycle"];
      if (!isValidWORCycle(wor)) {
        dbg.printf("[CONFIG] Invalid WOR cycle: %d\n", wor);
        request->send(400, "application/json", "{\"error\":\"Invalid WOR cycle (0-7)\"}");
        return;
      }
      e220_config.wor_cycle = wor;
    }
    
    if (doc.containsKey("crypt_h")) e220_config.crypt_h = doc["crypt_h"];
    if (doc.containsKey("crypt_l")) e220_config.crypt_l = doc["crypt_l"];
    if (doc.containsKey("savetype")) e220_config.savetype = doc["savetype"];
    
    dbg.println("[CONFIG] Updated parameters:");
    dbg.printf("  freq=%.3f txpower=%d baud=%d\n", e220_config.freq, e220_config.txpower, e220_config.baud);
    dbg.printf("  addr=%s dest=%s\n", e220_config.addr, e220_config.dest);
    dbg.printf("  airrate=%d subpkt=%d parity=%d txmode=%d\n", e220_config.airrate, e220_config.subpkt, e220_config.parity, e220_config.txmode);
    dbg.printf("  rssi_noise=%d rssi_byte=%d lbt=%d wor=%d\n", e220_config.rssi_noise, e220_config.rssi_byte, e220_config.lbt, e220_config.wor_cycle);
    dbg.printf("  crypt=0x%02X%02X savetype=%d\n", e220_config.crypt_h, e220_config.crypt_l, e220_config.savetype);
    
    if (!queueOperation(OP_APPLY_CONFIG, "Applying E220 configuration")) {
      request->send(409, "application/json", "{\"error\":\"Another operation is already running\"}");
      return;
    }

    request->send(202, "application/json", "{\"status\":\"queued\",\"message\":\"Config update queued\"}");
  });

  server.on("/api/auth/login", HTTP_POST, [](AsyncWebServerRequest *request) {}, NULL,
  [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
    DynamicJsonDocument doc(256);
    if (deserializeJson(doc, (const char*)data, len)) {
      request->send(400, "application/json", "{\"error\":\"JSON parse error\"}");
      return;
    }
    const char *password = doc["password"] | "";
    if (strlen(password) == 0) {
      request->send(400, "application/json", "{\"error\":\"Password required\"}");
      return;
    }
    if (String(password) != String(wifi_config.ap_password)) {
      resetAdminSession();
      request->send(401, "application/json", "{\"error\":\"Invalid password\"}");
      return;
    }
    adminSessionToken = generateAdminToken();
    adminSessionExpiresAt = millis() + ADMIN_SESSION_TTL_MS;
    String response = "{\"status\":\"ok\",\"token\":\"" + adminSessionToken + "\"}";
    request->send(200, "application/json", response);
  });

  server.on("/api/operation", HTTP_GET, [](AsyncWebServerRequest *request) {
    if (!requireAdmin(request)) return;
    request->send(200, "application/json", buildOperationStatusJson());
  });

  // Debug log API - returns new serial output since last poll
  server.on("/api/debug", HTTP_GET, [](AsyncWebServerRequest *request) {
    String out;
    out.reserve(512);
    while (debugLogReadPos != debugLogHead) {
      char c = debugLogBuf[debugLogReadPos];
      out += c;
      debugLogReadPos = (debugLogReadPos + 1) % DEBUG_LOG_SIZE;
      if (out.length() > 2048) break;  // cap per response
    }
    request->send(200, "text/plain", out);
  });

  // Debug log clear
  server.on("/api/debug/clear", HTTP_POST, [](AsyncWebServerRequest *request) {
    debugLogReadPos = debugLogHead;
    request->send(200, "text/plain", "ok");
  });

  // Reboot API
  server.on("/api/reboot", HTTP_POST, [](AsyncWebServerRequest *request) {
    if (!requireAdmin(request)) return;
    rebootPending = true;
    rebootRequestedAt = millis();
    request->send(202, "application/json", "{\"status\":\"queued\",\"message\":\"Reboot scheduled\"}");
    dbg.println("[SYS] Reboot requested via web");
  });

  // WiFi status API
  server.on("/api/wifi/status", HTTP_GET, [](AsyncWebServerRequest *request) {
    request->send(200, "application/json", buildWifiStatusJson());
  });

  server.on("/api/wifi/toggle", HTTP_POST, [](AsyncWebServerRequest *request) {}, NULL,
  [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
    if (!requireAdmin(request)) return;
    DynamicJsonDocument doc(128);
    if (deserializeJson(doc, (const char*)data, len)) {
      request->send(400, "application/json", "{\"error\":\"JSON parse error\"}");
      return;
    }
    bool enabled = doc["enabled"] | false;
    setWifiEnabled(enabled);
    request->send(200, "application/json", buildWifiStatusJson());
  });

  // WiFi scan API - scan for available networks
  // Note: WiFi scan requires STA mode to be active. If in AP-only mode, 
  // we temporarily enable STA mode, perform the scan, then restore mode.
  server.on("/api/wifi/scan", HTTP_GET, [](AsyncWebServerRequest *request) {
    if (!requireAdmin(request)) return;
    if (!queueOperation(OP_WIFI_SCAN, "Scanning WiFi networks")) {
      request->send(409, "application/json", "{\"error\":\"Another operation is already running\"}");
      return;
    }
    request->send(202, "application/json", "{\"status\":\"queued\",\"message\":\"WiFi scan queued\"}");
  });

  // WiFi connect API
  server.on("/api/wifi/connect", HTTP_POST, [](AsyncWebServerRequest *request) {}, NULL,
  [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
    if (!requireAdmin(request)) return;
    DynamicJsonDocument doc(256);
    if (deserializeJson(doc, (const char*)data, len)) {
      request->send(400, "application/json", "{\"error\":\"JSON parse error\"}");
      return;
    }
    const char* ssid = doc["ssid"] | "";
    const char* pass = doc["password"] | "";
    if (strlen(ssid) == 0) {
      request->send(400, "application/json", "{\"error\":\"SSID required\"}");
      return;
    }
    if (!queueOperation(OP_WIFI_CONNECT, "Connecting to WiFi")) {
      request->send(409, "application/json", "{\"error\":\"Another operation is already running\"}");
      return;
    }
    strlcpy(operationState.wifi_ssid, ssid, sizeof(operationState.wifi_ssid));
    strlcpy(operationState.wifi_password, pass, sizeof(operationState.wifi_password));
    request->send(202, "application/json", "{\"status\":\"queued\",\"message\":\"WiFi connect queued\"}");
  });

  // WiFi disconnect API
  server.on("/api/wifi/disconnect", HTTP_POST, [](AsyncWebServerRequest *request) {
    if (!requireAdmin(request)) return;
    WiFi.disconnect(true);
    preferences.remove("sta_ssid");
    preferences.remove("sta_pass");
    wifi_config.ssid[0] = '\0';
    wifi_config.password[0] = '\0';
    dbg.println("[WiFi] Disconnected STA, cleared credentials, staying in AP+STA mode");
    request->send(200, "application/json", "{\"status\":\"disconnected\"}");
  });

  // WiFi AP settings API - password only (SSID is now dynamic based on chip ID)
  // SSID is automatically generated from MAC address and cannot be changed
  server.on("/api/wifi/ap", HTTP_POST, [](AsyncWebServerRequest *request) {}, NULL,
  [](AsyncWebServerRequest *request, uint8_t *data, size_t len, size_t index, size_t total) {
    if (!requireAdmin(request)) return;
    DynamicJsonDocument doc(256);
    if (deserializeJson(doc, (const char*)data, len)) {
      request->send(400, "application/json", "{\"error\":\"JSON parse error\"}");
      return;
    }
    const char* pass = doc["password"] | "";
    
    String errors = "";
    
    if (strlen(pass) == 0) {
      errors += "Password required; ";
    } else if (strlen(pass) < 8) {
      errors += "Password too short (min 8 chars); ";
    } else if (strlen(pass) > 63) {
      errors += "Password too long (max 63 chars); ";
    } else {
      preferences.putString("ap_pass", pass);
      strlcpy(wifi_config.ap_password, pass, sizeof(wifi_config.ap_password));
      resetAdminSession();
      dbg.printf("[WiFi] AP password updated\n");
    }
    
    if (errors.length() > 0) {
      dbg.printf("[WiFi] AP settings validation errors: %s\n", errors.c_str());
      request->send(400, "application/json", "{\"error\":\"" + errors + "\"}");
    } else {
      dbg.printf("[WiFi] AP settings saved: SSID=%s, password updated (reboot required)\n", wifi_config.ap_ssid);
      request->send(200, "application/json", "{\"status\":\"saved\",\"message\":\"Reboot to apply new AP settings\"}");
    }
  });

  // Diagnostics API - returns E220 communication stats
  server.on("/api/diagnostics", HTTP_GET, [](AsyncWebServerRequest *request) {
    DynamicJsonDocument doc(512);
    doc["e220_timeouts"] = e220_timeout_count;
    doc["e220_rx_errors"] = e220_rx_errors;
    doc["e220_tx_errors"] = e220_tx_errors;
    doc["uptime_ms"] = millis();
    uint32_t freeHeap = ESP.getFreeHeap();
    uint32_t maxAllocHeap = ESP.getMaxAllocHeap();
    doc["free_heap"] = freeHeap;
    doc["max_alloc_heap"] = maxAllocHeap;
    // Calculate fragmentation: if max allocatable block is significantly less than free heap, fragmentation is high
    doc["heap_fragmentation"] = (maxAllocHeap < freeHeap) ? ((freeHeap - maxAllocHeap) * 100 / freeHeap) : 0;
    
    String response;
    serializeJson(doc, response);
    request->send(200, "application/json", response);
  });

  server.begin();
  dbg.println("[Web] Server on 192.168.4.1");
}

// RX buffer for reassembling large incoming messages
static uint8_t rxBuf[2048];
static int rxLen = 0;
static unsigned long lastRxTime = 0;
// If no new data for this many ms, flush whatever we have as a complete message
#define RX_FLUSH_TIMEOUT 2000

String buildTxHistoryEntry(const String &message) {
  return "[TX] " + message;
}

// Get sub-packet size in bytes from config value
int getSubPacketSize() {
  switch (e220_config.subpkt) {
    case 0: return 200;
    case 1: return 128;
    case 2: return 64;
    case 3: return 32;
    default: return 200;
  }
}

// Process a complete received packet (strip RSSI bytes if enabled)
// When RSSI byte is enabled, the E220 appends 1 RSSI byte after EACH sub-packet,
// not just at the end. So a 210-byte message with 200B sub-packets arrives as:
//   [200 data bytes] [RSSI] [10 data bytes] [RSSI]
void processRxPacket() {
  if (rxLen == 0) return;
  bool allFf = true;
  for (int i = 0; i < rxLen; i++) {
    if (rxBuf[i] != 0xFF) {
      allFf = false;
      break;
    }
  }
  if (allFf) {
    e220_rx_errors++;
    dbg.println("[E220] Ignoring local FF status/error bytes");
    rxLen = 0;
    return;
  }
  
  int rssiRaw = -1;
  String msg = "";
  
  if (e220_config.rssi_byte) {
    int subPktSize = getSubPacketSize();
    int dataCount = 0;
    
    dbg.printf("[RSSI] Stripping from %d raw bytes (subpkt=%d)\\n", rxLen, subPktSize);
    
    for (int i = 0; i < rxLen; i++) {
      uint8_t b = rxBuf[i];
      dataCount++;
      
      if (dataCount == subPktSize + 1) {
        // This byte is a mandatory RSSI byte after a full sub-packet
        rssiRaw = b;
        lastRssi = -(256 - rssiRaw);
        dataCount = 0;
      } else {
        // This is data. Filter for printable ASCII.
        if ((b >= 0x20 && b <= 0x7E) || b == '\\t') {
          msg += (char)b;
        }
      }
    }
    
    // After the loop, if we have a trailing byte and it wasn't already stripped
    // as a full sub-packet RSSI, the last byte is the final RSSI.
    if (dataCount > 0) {
      // The last byte seen was the RSSI byte for the partial final sub-packet
      uint8_t lastByte = rxBuf[rxLen - 1];
      
      // Only treat as RSSI if we actually have some data before it, 
      // or if it's the only byte (meaning the msg was empty)
      rssiRaw = lastByte;
      lastRssi = -(256 - rssiRaw);
      
      // Remove the last character from msg if it was added as data
      uint8_t b = lastByte;
      if ((b >= 0x20 && b <= 0x7E) || b == '\\t') {
        if (msg.length() > 0) {
          msg.remove(msg.length() - 1);
        }
      }
    }
  } else {
    // No RSSI stripping needed
    for (int i = 0; i < rxLen; i++) {
      uint8_t b = rxBuf[i];
      if ((b >= 0x20 && b <= 0x7E) || b == '\\t') {
        msg += (char)b;
      }
    }
  }
  
  msg.trim();
  
  if (msg.length() > 0 || rssiRaw >= 0) {
    String display = "[RX] " + msg;
    if (rssiRaw >= 0) {
      display += " [RSSI:" + String(lastRssi) + "dBm]";
    }
    addChatHistory(display);
    dbg.printf("[RX] (%d bytes)", msg.length());
    if (rssiRaw >= 0) dbg.printf(" [RSSI:%d dBm]", lastRssi);
    dbg.println();
  }
  
  rxLen = 0;
}

void handleE220Serial() {
  while (e220Serial.available()) {
    uint8_t b = e220Serial.read();
    lastRxTime = millis();
    
    if ((char)b == '\n') {
      // Newline = end of message
      processRxPacket();
    } else {
      if (rxLen < (int)sizeof(rxBuf) - 1) {
        rxBuf[rxLen++] = b;
      }
    }
  }
  
  // Flush partial buffer after timeout (in case sender didn't send newline)
  if (rxLen > 0 && (millis() - lastRxTime) > RX_FLUSH_TIMEOUT) {
    processRxPacket();
  }
}

void handleUSBSerial() {
  while (Serial.available()) {
    String input = Serial.readStringUntil('\n');
    input.trim();
    
    if (input.length() == 0) return;
    
    // Slash commands for config/admin, everything else is a message
    if (input == "/config") {
      dbg.println("[CONFIG] Current settings:");
      dbg.printf("  freq=%.3f MHz (CH=%d)\n", e220_config.freq, (int)(e220_config.freq - 850.125));
      dbg.printf("  txpower=%d dBm\n", e220_config.txpower);
      dbg.printf("  baud=%d\n", e220_config.baud);
      dbg.printf("  addr=%s  dest=%s\n", e220_config.addr, e220_config.dest);
      dbg.printf("  airrate=%d  subpkt=%d  parity=%d\n", e220_config.airrate, e220_config.subpkt, e220_config.parity);
      dbg.printf("  txmode=%s\n", e220_config.txmode ? "FIXED" : "TRANSPARENT");
      dbg.printf("  rssi_noise=%d  rssi_byte=%d\n", e220_config.rssi_noise, e220_config.rssi_byte);
      dbg.printf("  lbt=%d  wor_cycle=%d\n", e220_config.lbt, e220_config.wor_cycle);
      dbg.printf("  crypt=0x%02X%02X  savetype=%d\n", e220_config.crypt_h, e220_config.crypt_l, e220_config.savetype);
    }
    else if (input == "/read") {
      dbg.println("[E220] Reading module registers...");
      readE220Config();
    }
    else if (input == "/history") {
      dbg.printf("[HISTORY] %d stored messages:\n", (int)chatHistoryCount);
      for (size_t i = 0; i < chatHistoryCount; i++) {
        dbg.printf("  %d: %s\n", (int)i, getChatHistoryItem(i).c_str());
      }
    }
    else if (input == "/clear") {
      clearChatHistory();
      dbg.println("[OK] History cleared");
    }
    else if (input == "/factory") {
      dbg.println("[E220] Restoring factory defaults (900MHz: addr=0, ch=80, 9600 8N1, air 2.4k, 21dBm)...");
      e220_config.freq = 930.125;
      e220_config.txpower = 21;
      e220_config.baud = 9600;
      strlcpy(e220_config.addr, "0x0000", sizeof(e220_config.addr));
      strlcpy(e220_config.dest, "0xFFFF", sizeof(e220_config.dest));
      e220_config.airrate = 2;
      e220_config.subpkt = 0;
      e220_config.parity = 0;
      e220_config.txmode = 0;
      e220_config.rssi_noise = 0;
      e220_config.rssi_byte = 0;
      e220_config.lbt = 0;
      e220_config.wor_cycle = 3;
      e220_config.crypt_h = 0;
      e220_config.crypt_l = 0;
      e220_config.savetype = 1;
      applyE220Config();
      delay(500);
      readE220Config();
    }
    else if (input == "/help") {
      dbg.println("Type anything to send it via E220.");
      dbg.println("Slash commands:");
      dbg.println("  /config   - Show E220 config");
      dbg.println("  /read     - Read module registers");
      dbg.println("  /factory  - Restore factory defaults");
      dbg.println("  /history  - Show chat history");
      dbg.println("  /clear    - Clear history");
      dbg.println("  /help     - This help");
    }
    else {
      // Everything else is a message - send it (chunked for large messages)
      const int CHUNK_SIZE = 190;
      int inputLen = input.length();
      
      if (inputLen <= CHUNK_SIZE) {
        e220Serial.print(input);
        e220Serial.print('\n');
      } else {
        for (int i = 0; i < inputLen; i += CHUNK_SIZE) {
          int end = min(i + CHUNK_SIZE, inputLen);
          String chunk = input.substring(i, end);
          e220Serial.print(chunk);
          e220Serial.flush();
          waitE220Ready(3000);
          delay(50);
        }
        e220Serial.print('\n');
      }
      e220Serial.flush();
      
      addChatHistory(buildTxHistoryEntry(input));
      dbg.printf("[TX] (%d bytes) %s\n", inputLen, input.c_str());
    }
  }
}

#endif

static const char *BLE_DEVICE_PREFIX = "E220-Chat-";
static const BLEUUID BLE_UART_SERVICE_UUID("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
static const BLEUUID BLE_UART_RX_UUID("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
static const BLEUUID BLE_UART_TX_UUID("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
static const BLEUUID BLE_CLIENT_CHAR_CONFIG_UUID("00002902-0000-1000-8000-00805F9B34FB");

BLEServer *bleServer = nullptr;
BLEService *bleService = nullptr;
BLECharacteristic *bleTxCharacteristic = nullptr;
BLECharacteristic *bleRxCharacteristic = nullptr;
BLEAdvertising *bleAdvertising = nullptr;
String bleIncomingBuffer;
String bleDeviceName;
bool bleClientConnected = false;
uint32_t bleRequestCount = 0;
uint32_t bleParseErrors = 0;
uint32_t bleRawMessageCount = 0;

String jsonWrapError(const String &message) {
  DynamicJsonDocument doc(256);
  doc["ok"] = false;
  doc["error"] = message;
  String out;
  serializeJson(doc, out);
  return out;
}

String jsonWrapOkMessage(const String &message) {
  DynamicJsonDocument doc(256);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["message"] = message;
  String out;
  serializeJson(doc, out);
  return out;
}

String buildBleChatResponse() {
  DynamicJsonDocument doc(16384);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["sequence"] = chatSequence;
  JsonArray messages = data.createNestedArray("messages");
  // Limit to last 10 messages to avoid BLE buffer overflow
  size_t start = 0;
  if (chatHistoryCount > 10) {
    start = chatHistoryCount - 10;
  }
  for (size_t i = start; i < chatHistoryCount; i++) {
    messages.add(getChatHistoryItem(i));
  }
  String out;
  serializeJson(doc, out);
  return out;
}

String buildBleConfigResponse() {
  DynamicJsonDocument doc(1024);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["freq"] = e220_config.freq;
  data["txpower"] = e220_config.txpower;
  data["baud"] = e220_config.baud;
  data["addr"] = e220_config.addr;
  data["dest"] = e220_config.dest;
  data["airrate"] = e220_config.airrate;
  data["subpkt"] = e220_config.subpkt;
  data["parity"] = e220_config.parity;
  data["txmode"] = e220_config.txmode;
  data["rssi_noise"] = e220_config.rssi_noise;
  data["rssi_byte"] = e220_config.rssi_byte;
  data["lbt"] = e220_config.lbt;
  data["lbr_rssi"] = e220_config.lbr_rssi;
  data["lbr_timeout"] = e220_config.lbr_timeout;
  data["urxt"] = e220_config.urxt;
  data["wor_cycle"] = e220_config.wor_cycle;
  data["crypt_h"] = e220_config.crypt_h;
  data["crypt_l"] = e220_config.crypt_l;
  data["savetype"] = e220_config.savetype;
  String out;
  serializeJson(doc, out);
  return out;
}

String buildBleOperationResponse() {
  DynamicJsonDocument doc(1024);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["type"] = operationTypeName(operationState.type);
  data["state"] = operationStateName(operationState.state);
  data["message"] = operationState.message;
  data["updated_at_ms"] = millis();
  if (operationState.resultJson.length() > 0) {
    data["result_raw"] = operationState.resultJson;
  }
  String out;
  serializeJson(doc, out);
  return out;
}

String buildBleDebugResponse() {
  String outLog;
  outLog.reserve(1024);
  while (debugLogReadPos != debugLogHead) {
    char c = debugLogBuf[debugLogReadPos];
    outLog += c;
    debugLogReadPos = (debugLogReadPos + 1) % DEBUG_LOG_SIZE;
    if (outLog.length() > 4096) break;
  }
  DynamicJsonDocument doc(8192);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["log"] = outLog;
  String out;
  serializeJson(doc, out);
  return out;
}

String buildBleDiagnosticsResponse() {
  DynamicJsonDocument doc(1024);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["e220_timeout_count"] = e220_timeout_count;
  data["e220_rx_errors"] = e220_rx_errors;
  data["e220_tx_errors"] = e220_tx_errors;
  data["uptime_ms"] = (uint64_t)millis();
  data["free_heap"] = (uint64_t)ESP.getFreeHeap();
  data["min_free_heap"] = (uint64_t)ESP.getMinFreeHeap();
  data["bt_name"] = bleDeviceName;
  data["bt_has_client"] = bleClientConnected;
  data["bt_request_count"] = bleRequestCount;
  data["bt_parse_errors"] = bleParseErrors;
  data["bt_raw_message_count"] = bleRawMessageCount;
  data["last_rssi"] = lastRssi;
  data["ambient_noise_rssi"] = hasAmbientNoiseRssi ? lastAmbientNoiseRssi : 0;
  data["e220_aux_pin"] = digitalRead(E220_AUX_PIN);
  data["e220_m0_pin"] = digitalRead(E220_M0_PIN);
  data["e220_m1_pin"] = digitalRead(E220_M1_PIN);
  data["e220_rx_gpio"] = E220_RX_PIN;
  data["e220_tx_gpio"] = E220_TX_PIN;
  data["espnow_ready"] = espNowReady;
  data["espnow_channel"] = ESPNOW_CHANNEL;
  String out;
  serializeJson(doc, out);
  return out;
}

void fillWifiStatusJson(JsonObject data) {
  wifi_mode_t mode = WiFi.getMode();
  bool enabled = mode != WIFI_OFF;
  data["enabled"] = enabled;
  data["mode"] = (mode == WIFI_AP) ? "AP" : (mode == WIFI_STA) ? "STA" : (mode == WIFI_AP_STA) ? "AP_STA" : "OFF";
  data["ap_ssid"] = String(wifi_config.ap_ssid);
  data["ap_password"] = String(wifi_config.ap_password);
  data["ap_ip"] = WiFi.softAPIP().toString();
  data["sta_ssid"] = String(wifi_config.ssid);
  data["sta_password"] = String(wifi_config.password);
  data["sta_connected"] = (WiFi.status() == WL_CONNECTED);
  if (WiFi.status() == WL_CONNECTED) {
    data["sta_ip"] = WiFi.localIP().toString();
  } else {
    data["sta_ip"] = "";
  }
}

String buildWifiStatusJson() {
  DynamicJsonDocument doc(768);
  fillWifiStatusJson(doc.to<JsonObject>());
  String response;
  serializeJson(doc, response);
  return response;
}

String buildBleWifiStatusResponse() {
  DynamicJsonDocument doc(896);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  fillWifiStatusJson(data);
  String response;
  serializeJson(doc, response);
  return response;
}

String buildBleRebootResponse() {
  DynamicJsonDocument doc(256);
  doc["ok"] = true;
  JsonObject data = doc.createNestedObject("data");
  data["status"] = "queued";
  data["message"] = "Reboot scheduled";
  String out;
  serializeJson(doc, out);
  return out;
}

String buildBleError(const String &message) {
  return jsonWrapError(message);
}

void bleSendResponse(const String &response) {
  if (!bleTxCharacteristic) return;
  const size_t chunkSize = 20;
  String payload = response + "\n";
  
  dbg.printf("[BLE] Sending response (%d bytes)\n", payload.length());
  
  for (size_t i = 0; i < payload.length(); i += chunkSize) {
    size_t len = min(chunkSize, payload.length() - i);
    String chunk = payload.substring(i, i + len);
    bleTxCharacteristic->setValue(chunk.c_str());
    bleTxCharacteristic->notify();
    delay(15); // Increased delay to prevent buffer overflow on Android side
  }
}

String applyBleConfigFromJson(JsonObjectConst config) {
  if (config.containsKey("freq")) {
    float freq = config["freq"];
    if (!isValidFrequency(freq)) return jsonWrapError("Invalid frequency (850.125-930.125 MHz)");
    e220_config.freq = freq;
  }
  if (config.containsKey("txpower")) {
    int power = config["txpower"];
    if (!isValidTxPower(power)) return jsonWrapError("Invalid TX power (30,27,24,21 dBm)");
    e220_config.txpower = power;
  }
  if (config.containsKey("baud")) {
    int baud = config["baud"];
    if (!isValidBaud(baud)) return jsonWrapError("Invalid baud rate");
    e220_config.baud = baud;
  }
  if (config.containsKey("addr")) {
    const char *addr = config["addr"];
    if (!isValidHexAddress(addr)) return jsonWrapError("Invalid address format (use 0xHHLL)");
    strlcpy(e220_config.addr, addr, sizeof(e220_config.addr));
  }
  if (config.containsKey("dest")) {
    const char *dest = config["dest"];
    if (!isValidHexAddress(dest)) return jsonWrapError("Invalid destination format (use 0xHHLL)");
    strlcpy(e220_config.dest, dest, sizeof(e220_config.dest));
  }
  if (config.containsKey("airrate")) {
    int rate = config["airrate"];
    if (!isValidAirRate(rate)) return jsonWrapError("Invalid air rate (0-7)");
    e220_config.airrate = rate;
  }
  if (config.containsKey("subpkt")) {
    int subpkt = config["subpkt"];
    if (!isValidSubPacketSize(subpkt)) return jsonWrapError("Invalid subpacket size (0-3)");
    e220_config.subpkt = subpkt;
  }
  if (config.containsKey("parity")) e220_config.parity = config["parity"];
  if (config.containsKey("txmode")) e220_config.txmode = config["txmode"];
  if (config.containsKey("rssi_noise")) e220_config.rssi_noise = config["rssi_noise"];
  if (config.containsKey("rssi_byte")) e220_config.rssi_byte = config["rssi_byte"];
  if (config.containsKey("lbt")) e220_config.lbt = config["lbt"];
  if (config.containsKey("lbr_rssi")) e220_config.lbr_rssi = config["lbr_rssi"];
  if (config.containsKey("lbr_timeout")) e220_config.lbr_timeout = config["lbr_timeout"];
  if (config.containsKey("urxt")) e220_config.urxt = config["urxt"];
  if (config.containsKey("wor_cycle")) {
    int wor = config["wor_cycle"];
    if (!isValidWORCycle(wor)) return jsonWrapError("Invalid WOR cycle (0-7)");
    e220_config.wor_cycle = wor;
  }
  if (config.containsKey("crypt_h")) e220_config.crypt_h = config["crypt_h"];
  if (config.containsKey("crypt_l")) e220_config.crypt_l = config["crypt_l"];
  if (config.containsKey("savetype")) e220_config.savetype = config["savetype"];

  if (!queueOperation(OP_APPLY_CONFIG, "Applying E220 configuration")) {
    return jsonWrapError("Another operation is already running");
  }
  dbg.println("[BLE] Configuration update queued");
  return buildBleConfigResponse();
}

String handleBleApiRequest(const String &line) {
  DynamicJsonDocument req(1024);
  DeserializationError error = deserializeJson(req, line);
  if (error) {
    bleParseErrors++;
    return buildBleError("JSON parse error");
  }
  const char *path = req["path"] | "";
  const char *method = req["method"] | "GET";
  bleRequestCount++;

  if (strcmp(path, "/api/chat") == 0 && strcmp(method, "GET") == 0) {
    return buildBleChatResponse();
  }
  if (strcmp(path, "/api/send") == 0 && strcmp(method, "POST") == 0) {
    String message = req["message"] | "";
    if (message.length() == 0 && req.containsKey("body")) {
      JsonObject body = req["body"].as<JsonObject>();
      message = body["message"] | "";
    }
    message.trim();
    if (message.length() == 0) return buildBleError("Message cannot be empty");
    if (txPending) return buildBleError("TX busy, wait for previous message");
    if (message.length() > 2000) return buildBleError("Message too large (max 2000 bytes)");
    txQueue = message;
    txPending = true;
    addChatHistory(buildTxHistoryEntry(message));
    dbg.printf("[BLE] Queued %d-byte message\n", message.length());
    return jsonWrapOkMessage("Message queued for transmission");
  }
  if (strcmp(path, "/api/config") == 0 && strcmp(method, "GET") == 0) {
    return buildBleConfigResponse();
  }
  if (strcmp(path, "/api/config") == 0 && strcmp(method, "POST") == 0) {
    if (!req.containsKey("config")) return buildBleError("Missing config object");
    JsonObjectConst config = req["config"].as<JsonObjectConst>();
    String response = applyBleConfigFromJson(config);
    if (!response.startsWith("{\"ok\":true")) {
      return response;
    }
    return response;
  }
  if (strcmp(path, "/api/wifi/status") == 0 && strcmp(method, "GET") == 0) {
    return buildBleWifiStatusResponse();
  }
  if (strcmp(path, "/api/wifi/toggle") == 0 && strcmp(method, "POST") == 0) {
    bool enabled = false;
    if (req.containsKey("body")) {
      JsonObject body = req["body"].as<JsonObject>();
      enabled = body["enabled"] | false;
    } else {
      enabled = req["enabled"] | false;
    }
    setWifiEnabled(enabled);
    return buildBleWifiStatusResponse();
  }
  if (strcmp(path, "/api/wifi/scan") == 0 && strcmp(method, "GET") == 0) {
    if (!queueOperation(OP_WIFI_SCAN, "Scanning WiFi networks")) {
      return buildBleError("Another operation is already running");
    }
    dbg.println("[BLE] WiFi scan queued");
    return jsonWrapOkMessage("WiFi scan queued");
  }
  if (strcmp(path, "/api/wifi/connect") == 0 && strcmp(method, "POST") == 0) {
    String ssid;
    String pass;
    if (req.containsKey("body")) {
      JsonObject body = req["body"].as<JsonObject>();
      ssid = body["ssid"] | "";
      pass = body["password"] | "";
    } else {
      ssid = req["ssid"] | "";
      pass = req["password"] | "";
    }
    ssid.trim();
    if (ssid.length() == 0) return buildBleError("SSID required");
    if (!queueOperation(OP_WIFI_CONNECT, "Connecting to WiFi")) {
      return buildBleError("Another operation is already running");
    }
    strlcpy(operationState.wifi_ssid, ssid.c_str(), sizeof(operationState.wifi_ssid));
    strlcpy(operationState.wifi_password, pass.c_str(), sizeof(operationState.wifi_password));
    dbg.printf("[BLE] WiFi connect queued for %s\n", ssid.c_str());
    return jsonWrapOkMessage("WiFi connect queued");
  }
  if (strcmp(path, "/api/wifi/disconnect") == 0 && strcmp(method, "POST") == 0) {
    WiFi.disconnect(true);
    preferences.remove("sta_ssid");
    preferences.remove("sta_pass");
    wifi_config.ssid[0] = '\0';
    wifi_config.password[0] = '\0';
    dbg.println("[BLE] Disconnected STA, cleared credentials, staying in AP+STA mode");
    return jsonWrapOkMessage("WiFi disconnected");
  }
  if (strcmp(path, "/api/wifi/ap") == 0 && strcmp(method, "POST") == 0) {
    String pass;
    if (req.containsKey("body")) {
      JsonObject body = req["body"].as<JsonObject>();
      pass = body["password"] | "";
    } else {
      pass = req["password"] | "";
    }
    pass.trim();
    if (pass.length() == 0) return buildBleError("Password required");
    if (pass.length() < 8) return buildBleError("Password too short (min 8 chars)");
    if (pass.length() > 63) return buildBleError("Password too long (max 63 chars)");
    preferences.putString("ap_pass", pass);
    strlcpy(wifi_config.ap_password, pass.c_str(), sizeof(wifi_config.ap_password));
    resetAdminSession();
    dbg.println("[BLE] AP password updated");
    return jsonWrapOkMessage("Reboot to apply new AP settings");
  }
  if (strcmp(path, "/api/operation") == 0 && strcmp(method, "GET") == 0) {
    return buildBleOperationResponse();
  }
  if (strcmp(path, "/api/debug") == 0 && strcmp(method, "GET") == 0) {
    return buildBleDebugResponse();
  }
  if (strcmp(path, "/api/debug/clear") == 0 && strcmp(method, "POST") == 0) {
    debugLogReadPos = debugLogHead;
    return jsonWrapOkMessage("ok");
  }
  if (strcmp(path, "/api/diagnostics") == 0 && strcmp(method, "GET") == 0) {
    return buildBleDiagnosticsResponse();
  }
  if (strcmp(path, "/api/reboot") == 0 && strcmp(method, "POST") == 0) {
    rebootPending = true;
    rebootRequestedAt = millis();
    return buildBleRebootResponse();
  }
  return buildBleError("Unknown BLE API request");
}

class BleServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    bleClientConnected = true;
    dbg.println("[BLE] Client connected");
  }

  void onDisconnect(BLEServer *server) override {
    bleClientConnected = false;
    dbg.println("[BLE] Client disconnected");
    delay(100);
    if (bleAdvertising) bleAdvertising->start();
  }
};

class BleRxCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    bleRawMessageCount++;
    String chunk = characteristic->getValue().c_str();
    if (chunk.length() == 0) return;
    bleIncomingBuffer += chunk;
    while (true) {
      int newline = bleIncomingBuffer.indexOf('\n');
      if (newline < 0) break;
      String line = bleIncomingBuffer.substring(0, newline);
      bleIncomingBuffer = bleIncomingBuffer.substring(newline + 1);
      line.trim();
      if (line.length() == 0) continue;
      String response = handleBleApiRequest(line);
      bleSendResponse(response);
    }
  }
};

void setupBleTransport() {
  uint64_t mac = ESP.getEfuseMac();
  uint8_t b3 = (mac >> 16) & 0xFF;
  uint8_t b4 = (mac >> 8) & 0xFF;
  uint8_t b5 = mac & 0xFF;
  char name[32];
  snprintf(name, sizeof(name), "%s%02X%02X%02X", BLE_DEVICE_PREFIX, b3, b4, b5);
  bleDeviceName = name;

  BLEDevice::init(bleDeviceName.c_str());
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new BleServerCallbacks());
  bleService = bleServer->createService(BLE_UART_SERVICE_UUID);

  bleTxCharacteristic = bleService->createCharacteristic(
    BLE_UART_TX_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  bleTxCharacteristic->addDescriptor(new BLE2902());

  bleRxCharacteristic = bleService->createCharacteristic(
    BLE_UART_RX_UUID,
    BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
  );
  bleRxCharacteristic->setCallbacks(new BleRxCallbacks());

  bleService->start();
  bleAdvertising = BLEDevice::getAdvertising();
  BLEAdvertisementData advertisementData;
  advertisementData.setFlags(0x06);
  advertisementData.setCompleteServices(BLE_UART_SERVICE_UUID);
  BLEAdvertisementData scanResponseData;
  scanResponseData.setName(bleDeviceName.c_str());
  bleAdvertising->setAdvertisementData(advertisementData);
  bleAdvertising->setScanResponseData(scanResponseData);
  bleAdvertising->setScanResponse(true);
  bleAdvertising->setMinPreferred(0x06);
  bleAdvertising->setMaxPreferred(0x12);
  BLEDevice::startAdvertising();

  dbg.print("[BLE] Advertising as ");
  dbg.println(bleDeviceName);
}

void setup() {
  Serial.begin(115200);
  delay(1000);
  dbg.println("\n\n[BOOT] E220 Chat + Config (BLE)");
  
  setupE220();
  setupBleTransport();
  setupEspNowBridge();
  readE220Config();
  
  dbg.println("[BOOT] Ready!");
}

// Drain TX queue from loop() context where blocking is safe

void handleQueuedOperations() {
  if (operationState.state != OP_STATE_PENDING) return;

  operationState.state = OP_STATE_RUNNING;

  if (operationState.type == OP_APPLY_CONFIG) {
    dbg.println("[OP] Applying queued E220 config");
    applyE220Config();
    operationState.resultJson = "{\"status\":\"ok\"}";
    operationState.message = "E220 configuration applied";
    operationState.state = OP_STATE_SUCCESS;
    return;
  }

  if (operationState.type == OP_WIFI_SCAN) {
    dbg.println("[OP] Starting queued WiFi scan...");
    DynamicJsonDocument doc(2048);
    JsonArray networks = doc.createNestedArray("networks");
    int n = WiFi.scanNetworks(false);

    if (n < 0) {
      dbg.printf("[WiFi] Scan failed with error %d\n", n);
      operationState.resultJson = "{\"error\":\"scan failed\"}";
      operationState.message = "WiFi scan failed";
      operationState.state = OP_STATE_ERROR;
      return;
    }

    dbg.printf("[WiFi] Found %d networks\n", n);
    for (int i = 0; i < n && i < 20; i++) {
      JsonObject net = networks.createNestedObject();
      net["ssid"] = WiFi.SSID(i);
      net["rssi"] = WiFi.RSSI(i);
      net["encryption"] = (WiFi.encryptionType(i) == WIFI_AUTH_OPEN) ? "Open" : "Encrypted";
      net["channel"] = WiFi.channel(i);
    }
    WiFi.scanDelete();
    serializeJson(doc, operationState.resultJson);
    operationState.message = "WiFi scan complete";
    operationState.state = OP_STATE_SUCCESS;
    return;
  }

  if (operationState.type == OP_WIFI_CONNECT) {
    preferences.putString("sta_ssid", operationState.wifi_ssid);
    preferences.putString("sta_pass", operationState.wifi_password);
    strlcpy(wifi_config.ssid, operationState.wifi_ssid, sizeof(wifi_config.ssid));
    strlcpy(wifi_config.password, operationState.wifi_password, sizeof(wifi_config.password));

    WiFi.mode(WIFI_AP_STA);
    WiFi.softAP(wifi_config.ap_ssid, wifi_config.ap_password);
    WiFi.begin(operationState.wifi_ssid, operationState.wifi_password);

    unsigned long start = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - start < 10000) {
      delay(250);
    }

    if (WiFi.status() == WL_CONNECTED) {
      dbg.printf("[WiFi] Connected to %s, IP: %s\n", operationState.wifi_ssid, WiFi.localIP().toString().c_str());
      operationState.resultJson = "{\"status\":\"connected\",\"ip\":\"" + WiFi.localIP().toString() + "\"}";
      operationState.message = "WiFi connected";
      operationState.state = OP_STATE_SUCCESS;
    } else {
      dbg.printf("[WiFi] Failed to connect to %s\n", operationState.wifi_ssid);
      operationState.resultJson = "{\"status\":\"failed\"}";
      operationState.message = "WiFi connection failed";
      operationState.state = OP_STATE_ERROR;
    }
    return;
  }

  operationState.type = OP_NONE;
  operationState.state = OP_STATE_IDLE;
}

void handlePendingReboot() {
  if (rebootPending && millis() - rebootRequestedAt >= 250) {
    dbg.println("[SYS] Rebooting now");
    ESP.restart();
  }
}

void handleTxQueue() {
  if (!txPending) return;
  
  const int CHUNK_SIZE = 190;
  int msgLen = txQueue.length();
  
  dbg.printf("[TX] Sending (%d bytes)...\n", msgLen);
  
  if (msgLen <= CHUNK_SIZE) {
    e220Serial.print(txQueue);
    e220Serial.print('\n');
  } else {
    for (int i = 0; i < msgLen; i += CHUNK_SIZE) {
      int chunkEnd = min(i + CHUNK_SIZE, msgLen);
      String chunk = txQueue.substring(i, chunkEnd);
      e220Serial.print(chunk);
      e220Serial.flush();
      waitE220Ready(3000);
      delay(50);
    }
    e220Serial.print('\n');
  }
  e220Serial.flush();
  sendEspNowChat(txQueue);
  
  dbg.printf("[TX] Sent %d bytes\n", msgLen);
  txQueue = "";
  txPending = false;
}

void handleE220Serial() {
  while (e220Serial.available()) {
    String line = e220Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;

    bool printable = true;
    bool allFf = true;
    for (size_t i = 0; i < line.length(); i++) {
      uint8_t b = (uint8_t)line.charAt(i);
      if (b != 0xFF) allFf = false;
      if (!((b >= 0x20 && b <= 0x7E) || b == '\t')) printable = false;
    }

    if (printable) {
      dbg.printf("[E220] RX: %s\n", line.c_str());
      addChatHistory("[RX] " + line);
    } else {
      e220_rx_errors++;
      dbg.print("[E220] RX ignored HEX:");
      for (size_t i = 0; i < line.length(); i++) {
        dbg.printf(" %02X", (uint8_t)line.charAt(i));
      }
      dbg.println(allFf ? " (all FF)" : " (non-printable)");
    }
  }
}

void handleUSBSerial() {
  while (Serial.available()) {
    String line = Serial.readStringUntil('\n');
    line.trim();
    if (line.length() == 0) continue;
    dbg.printf("[USB] %s\n", line.c_str());
    if (line.startsWith("/send ")) {
      txQueue = line.substring(6);
      txPending = true;
      addChatHistory(buildTxHistoryEntry(txQueue));
    } else if (line == "/status") {
      dbg.println(buildBleDiagnosticsResponse());
    }
  }
}

void loop() {
  handleE220Serial();
  handleUSBSerial();
  handleTxQueue();
  handleQueuedOperations();
  handlePendingReboot();
  delay(10);
}
