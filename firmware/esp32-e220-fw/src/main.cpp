#include <Arduino.h>
#include <NimBLEDevice.h>

// ========================= Hardware =========================
static constexpr int E220_RX_PIN = 21;
static constexpr int E220_TX_PIN = 22;
static constexpr int E220_M0_PIN = 25;
static constexpr int E220_M1_PIN = 26;
static constexpr int E220_AUX_PIN = 27;
static constexpr int VBAT_ADC_PIN = 34;

static constexpr uint32_t E220_UART_BAUD = 9600;
static constexpr uint32_t SERIAL_BAUD = 115200;

HardwareSerial E220(2);

// ========================= BLE UUIDs =========================
static const char *BLE_NAME_PREFIX = "E220-BLE";
static const char *SERVICE_UUID = "9f6d0001-6f52-4d94-b43f-2ef6f3ed7a10";
static const char *RX_UUID      = "9f6d0002-6f52-4d94-b43f-2ef6f3ed7a10"; // app->esp write
static const char *TX_UUID      = "9f6d0003-6f52-4d94-b43f-2ef6f3ed7a10"; // esp->app notify
static const char *STATUS_UUID  = "9f6d0004-6f52-4d94-b43f-2ef6f3ed7a10"; // status read/notify
static const char *CONFIG_UUID  = "9f6d0005-6f52-4d94-b43f-2ef6f3ed7a10"; // config read/write

// ========================= Protocol =========================
static constexpr uint8_t FRAME_START = 0xAA;

enum MsgType : uint8_t {
  MSG_TEXT    = 0x01,
  MSG_ACK     = 0x02,
  MSG_STATUS  = 0x03,
  MSG_CONFIG  = 0x04,
  MSG_PROFILE = 0x05,
  MSG_ERROR   = 0x06,
  MSG_WHOIS   = 0x07
};

enum FlowState : uint8_t {
  FLOW_READY = 0,
  FLOW_BUSY = 1,
  FLOW_TX_IN_PROGRESS = 2,
  FLOW_TX_DONE = 3,
  FLOW_TX_FAILED = 4
};

struct Frame {
  uint8_t type;
  uint8_t seq;
  uint8_t len;
  uint8_t payload[255];
  bool requireAck;
};

struct Config {
  uint16_t ackTimeoutMs = 180;
  uint8_t maxRetries = 4;
  uint16_t radioTxIntervalMs = 90;
  uint16_t statusIntervalMs = 1000;
  uint16_t profileIntervalSec = 900;
  uint32_t userId24 = 0x000001;
  char username[20] = "node";
  // E220 radio config
  uint8_t channel = 80;
  uint8_t txpower = 21;
  uint8_t baudIdx = 3;
  uint8_t parity = 0;
  uint8_t airrate = 2;
  uint8_t txmode = 0;
  uint8_t lbt = 0;
  uint8_t subpkt = 0;
  uint8_t rssiNoise = 0;
  uint8_t rssiByte = 0;
  uint8_t urxt = 0;
  uint8_t worCycle = 3;
  uint8_t cryptH = 0;
  uint8_t cryptL = 0;
  uint8_t saveType = 0;
  uint16_t addr = 0x0000;
  uint16_t dest = 0xFFFF;
  uint8_t wifiEnabled = 0;
  uint8_t wifiMode = 0;
};

struct PendingAck {
  bool active = false;
  Frame frame{};
  uint32_t lastSendMs = 0;
  uint8_t retries = 0;
};

struct StatusPayload {
  uint8_t flowState;
  uint16_t batteryMv;
  int8_t lastRssi;
  uint8_t qBleRx;
  uint8_t qRadioTx;
  uint8_t qRadioRx;
  uint8_t qBleTx;
  uint32_t uptimeSec;
  uint8_t fwMaj;
  uint8_t fwMin;
  uint8_t fwPatch;
  uint8_t deviceId[3];
};

template<typename T, size_t N>
class RingQueue {
public:
  bool push(const T &v) {
    if (count_ == N) return false;
    data_[tail_] = v;
    tail_ = (tail_ + 1) % N;
    count_++;
    return true;
  }

  bool pop(T &out) {
    if (count_ == 0) return false;
    out = data_[head_];
    head_ = (head_ + 1) % N;
    count_--;
    return true;
  }

  size_t size() const { return count_; }
  bool empty() const { return count_ == 0; }

private:
  T data_[N]{};
  size_t head_ = 0;
  size_t tail_ = 0;
  size_t count_ = 0;
};

// ========================= Globals =========================
Config gCfg;
uint8_t gNextSeq = 1;
uint8_t gDeviceId[3]{};
FlowState gFlowState = FLOW_READY;
int8_t gLastRssi = 0;
uint32_t gLastStatusMs = 0;
uint32_t gLastProfileMs = 0;
uint32_t gLastRadioTxMs = 0;
uint32_t gLastFlowNotifyMs = 0;
bool gBleClientConnected = false;

RingQueue<Frame, 32> gBleRxQueue;
RingQueue<Frame, 32> gBleTxQueue;
RingQueue<Frame, 32> gRadioTxQueue;
RingQueue<Frame, 32> gRadioRxQueue;

PendingAck gPendingBleTx;

NimBLEServer *gServer = nullptr;
NimBLECharacteristic *gRxChar = nullptr;
NimBLECharacteristic *gTxChar = nullptr;
NimBLECharacteristic *gStatusChar = nullptr;
NimBLECharacteristic *gConfigChar = nullptr;

// stream parser state for BLE RX and Radio RX
struct StreamParser {
  enum State : uint8_t { WAIT_START, READ_TYPE, READ_SEQ, READ_LEN, READ_PAYLOAD, READ_CRC } state = WAIT_START;
  Frame f{};
  uint8_t idx = 0;
};
StreamParser gBleParser;
StreamParser gRadioParser;

// ========================= Utilities =========================
uint8_t crcXor(const uint8_t *data, size_t len) {
  uint8_t c = 0;
  for (size_t i = 0; i < len; ++i) c ^= data[i];
  return c;
}

uint8_t allocSeq() {
  if (gNextSeq == 0) gNextSeq = 1;
  return gNextSeq++;
}

size_t encodeFrame(const Frame &f, uint8_t *out, size_t outCap) {
  const size_t need = 5 + f.len;
  if (outCap < need) return 0;
  out[0] = FRAME_START;
  out[1] = f.type;
  out[2] = f.seq;
  out[3] = f.len;
  if (f.len > 0) memcpy(out + 4, f.payload, f.len);
  out[4 + f.len] = crcXor(out, 4 + f.len);
  return need;
}

bool decodeByte(StreamParser &p, uint8_t b, Frame &outFrame) {
  switch (p.state) {
    case StreamParser::WAIT_START:
      if (b == FRAME_START) {
        p.state = StreamParser::READ_TYPE;
        p.idx = 0;
      }
      break;
    case StreamParser::READ_TYPE:
      p.f.type = b;
      p.state = StreamParser::READ_SEQ;
      break;
    case StreamParser::READ_SEQ:
      p.f.seq = b;
      p.state = StreamParser::READ_LEN;
      break;
    case StreamParser::READ_LEN:
      p.f.len = b;
      p.idx = 0;
      if (p.f.len == 0) p.state = StreamParser::READ_CRC;
      else p.state = StreamParser::READ_PAYLOAD;
      break;
    case StreamParser::READ_PAYLOAD:
      p.f.payload[p.idx++] = b;
      if (p.idx >= p.f.len) p.state = StreamParser::READ_CRC;
      break;
    case StreamParser::READ_CRC: {
      uint8_t tmp[260];
      tmp[0] = FRAME_START;
      tmp[1] = p.f.type;
      tmp[2] = p.f.seq;
      tmp[3] = p.f.len;
      if (p.f.len > 0) memcpy(tmp + 4, p.f.payload, p.f.len);
      const uint8_t expect = crcXor(tmp, 4 + p.f.len);
      p.state = StreamParser::WAIT_START;
      if (expect == b) {
        outFrame = p.f;
        return true;
      }
      break;
    }
  }
  return false;
}

void enqueueAck(uint8_t seq) {
  Frame ack{};
  ack.type = MSG_ACK;
  ack.seq = seq;
  ack.len = 0;
  ack.requireAck = false;
  gBleTxQueue.push(ack);
}

void enqueueError(uint8_t code, uint8_t originType, const char *text) {
  Frame e{};
  e.type = MSG_ERROR;
  e.seq = allocSeq();
  e.payload[0] = code;
  e.payload[1] = originType;
  uint8_t tl = (uint8_t)min((size_t)252, strlen(text));
  e.payload[2] = tl;
  if (tl > 0) memcpy(e.payload + 3, text, tl);
  e.len = 3 + tl;
  e.requireAck = true;
  gBleTxQueue.push(e);
}

void updateFlowState(FlowState st) {
  gFlowState = st;
}

void pushStatusFrame(bool force) {
  const uint32_t now = millis();
  if (!force && (now - gLastStatusMs) < gCfg.statusIntervalMs) return;
  gLastStatusMs = now;

  StatusPayload sp{};
  sp.flowState = (uint8_t)gFlowState;
  sp.batteryMv = analogReadMilliVolts(VBAT_ADC_PIN);
  sp.lastRssi = gLastRssi;
  sp.qBleRx = (uint8_t)gBleRxQueue.size();
  sp.qRadioTx = (uint8_t)gRadioTxQueue.size();
  sp.qRadioRx = (uint8_t)gRadioRxQueue.size();
  sp.qBleTx = (uint8_t)gBleTxQueue.size();
  sp.uptimeSec = millis() / 1000UL;
  sp.fwMaj = 1;
  sp.fwMin = 0;
  sp.fwPatch = 0;
  memcpy(sp.deviceId, gDeviceId, 3);

  Frame s{};
  s.type = MSG_STATUS;
  s.seq = allocSeq();
  s.len = sizeof(StatusPayload);
  memcpy(s.payload, &sp, sizeof(StatusPayload));
  s.requireAck = false;
  gBleTxQueue.push(s);

  // mirror in STATUS characteristic for read
  gStatusChar->setValue((uint8_t*)&sp, sizeof(StatusPayload));
  if (gBleClientConnected) gStatusChar->notify();
}

void pushProfileFrame() {
  Frame p{};
  p.type = MSG_PROFILE;
  p.seq = allocSeq();
  p.payload[0] = gDeviceId[0];
  p.payload[1] = gDeviceId[1];
  p.payload[2] = gDeviceId[2];
  const uint8_t n = (uint8_t)min((size_t)30, strlen(gCfg.username));
  p.payload[3] = n;
  if (n > 0) memcpy(p.payload + 4, gCfg.username, n);
  p.len = 4 + n;
  p.requireAck = true;
  gBleTxQueue.push(p);
  gLastProfileMs = millis();
}

void applyConfigPayload(const uint8_t *p, uint8_t len);
void emitConfigFrame(uint8_t seqToUse = 0);
void readE220Config();

void applyConfigPayload(const uint8_t *p, uint8_t len) {
  // binary config payload (matches BleConfig.toPayload()):
  // [ackTimeout:2][maxRetries:1][radioTxMs:2][statusMs:2][profileSec:2][userId:3]
  // [channel:1][txpower:1][baud:1][parity:1][airrate:1][txmode:1][lbt:1][subpkt:1]
  // [rssiNoise:1][rssiByte:1][urxt:1][worCycle:1][cryptH:1][cryptL:1][saveType:1]
  // [addr:2][dest:2][wifiEnabled:1][wifiMode:1]
  // [nameLen:1][name:N][apSsidLen:1][apSsid:N][apPwdLen:1][apPwd:N]...
  if (len < 33) return;
  uint16_t ack = ((uint16_t)p[0] << 8) | p[1];
  uint8_t retries = p[2];
  uint16_t radioInt = ((uint16_t)p[3] << 8) | p[4];
  uint16_t statusInt = ((uint16_t)p[5] << 8) | p[6];
  uint16_t profileSec = ((uint16_t)p[7] << 8) | p[8];

  gCfg.ackTimeoutMs = constrain(ack, (uint16_t)60, (uint16_t)2000);
  gCfg.maxRetries = constrain(retries, (uint8_t)1, (uint8_t)10);
  gCfg.radioTxIntervalMs = constrain(radioInt, (uint16_t)20, (uint16_t)2000);
  gCfg.statusIntervalMs = constrain(statusInt, (uint16_t)200, (uint16_t)5000);
  gCfg.profileIntervalSec = constrain(profileSec, (uint16_t)60, (uint16_t)3600);

  gCfg.userId24 = ((uint32_t)p[9] << 16) | ((uint32_t)p[10] << 8) | p[11];
  gDeviceId[0] = p[9];
  gDeviceId[1] = p[10];
  gDeviceId[2] = p[11];

  // Radio config fields (bytes 12-32)
  gCfg.channel = p[12];
  gCfg.txpower = p[13];
  gCfg.baudIdx = p[14];
  gCfg.parity = p[15];
  gCfg.airrate = p[16];
  gCfg.txmode = p[17];
  gCfg.lbt = p[18];
  gCfg.subpkt = p[19];
  gCfg.rssiNoise = p[20];
  gCfg.rssiByte = p[21];
  gCfg.urxt = p[22];
  gCfg.worCycle = p[23];
  gCfg.cryptH = p[24];
  gCfg.cryptL = p[25];
  gCfg.saveType = p[26];
  gCfg.addr = ((uint16_t)p[27] << 8) | p[28];
  gCfg.dest = ((uint16_t)p[29] << 8) | p[30];
  gCfg.wifiEnabled = p[31];
  gCfg.wifiMode = p[32];

  // Username (byte 33+)
  if (len > 33) {
    uint8_t nameLen = p[33];
    if (34 + nameLen <= len) {
      nameLen = min((uint8_t)19, nameLen);
      memcpy(gCfg.username, p + 34, nameLen);
      gCfg.username[nameLen] = '\0';
    }
  }

  // mirror config characteristic
  emitConfigFrame(); // re-emit with updated values
}

void emitConfigFrame(uint8_t seqToUse) {
  uint8_t payload[128];
  size_t idx = 0;
  payload[idx++] = (uint8_t)(gCfg.ackTimeoutMs >> 8);
  payload[idx++] = (uint8_t)(gCfg.ackTimeoutMs & 0xFF);
  payload[idx++] = gCfg.maxRetries;
  payload[idx++] = (uint8_t)(gCfg.radioTxIntervalMs >> 8);
  payload[idx++] = (uint8_t)(gCfg.radioTxIntervalMs & 0xFF);
  payload[idx++] = (uint8_t)(gCfg.statusIntervalMs >> 8);
  payload[idx++] = (uint8_t)(gCfg.statusIntervalMs & 0xFF);
  payload[idx++] = (uint8_t)(gCfg.profileIntervalSec >> 8);
  payload[idx++] = (uint8_t)(gCfg.profileIntervalSec & 0xFF);
  payload[idx++] = gDeviceId[0];
  payload[idx++] = gDeviceId[1];
  payload[idx++] = gDeviceId[2];
  // Radio config (bytes 12-32, matches BleConfig.toPayload)
  payload[idx++] = gCfg.channel;
  payload[idx++] = gCfg.txpower;
  payload[idx++] = gCfg.baudIdx;
  payload[idx++] = gCfg.parity;
  payload[idx++] = gCfg.airrate;
  payload[idx++] = gCfg.txmode;
  payload[idx++] = gCfg.lbt;
  payload[idx++] = gCfg.subpkt;
  payload[idx++] = gCfg.rssiNoise;
  payload[idx++] = gCfg.rssiByte;
  payload[idx++] = gCfg.urxt;
  payload[idx++] = gCfg.worCycle;
  payload[idx++] = gCfg.cryptH;
  payload[idx++] = gCfg.cryptL;
  payload[idx++] = gCfg.saveType;
  payload[idx++] = (uint8_t)(gCfg.addr >> 8);
  payload[idx++] = (uint8_t)(gCfg.addr & 0xFF);
  payload[idx++] = (uint8_t)(gCfg.dest >> 8);
  payload[idx++] = (uint8_t)(gCfg.dest & 0xFF);
  payload[idx++] = gCfg.wifiEnabled;
  payload[idx++] = gCfg.wifiMode;
  // Username
  const uint8_t n = (uint8_t)min((size_t)19, strlen(gCfg.username));
  payload[idx++] = n;
  if (n > 0) memcpy(payload + idx, gCfg.username, n);
  idx += n;

  gConfigChar->setValue(payload, idx);

  Frame c{};
  c.type = MSG_CONFIG;
  c.seq = seqToUse == 0 ? allocSeq() : seqToUse;
  c.len = (uint8_t)idx;
  memcpy(c.payload, payload, idx);
  c.requireAck = true;
  gBleTxQueue.push(c);
}

// ========================= BLE callbacks =========================
class ServerCallbacks : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer *pServer) override {
    gBleClientConnected = true;
    updateFlowState(FLOW_READY);
    pushStatusFrame(true);
    pushProfileFrame();
  }

  void onDisconnect(NimBLEServer *pServer) override {
    gBleClientConnected = false;
    gPendingBleTx.active = false;
    NimBLEDevice::startAdvertising();
  }
};

class RxCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c) override {
    std::string v = c->getValue();
    Frame f{};
    for (size_t i = 0; i < v.size(); ++i) {
      if (decodeByte(gBleParser, (uint8_t)v[i], f)) {
        if (!gBleRxQueue.push(f)) enqueueError(0x01, f.type, "BLE_RX_QUEUE_FULL");
      }
    }
  }
};

class ConfigCallbacks : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic *c) override {
    std::string v = c->getValue();
    applyConfigPayload((const uint8_t*)v.data(), (uint8_t)v.size());
    pushProfileFrame();
  }
};

// ========================= Processing =========================
void setupBle() {
  char name[24];
  snprintf(name, sizeof(name), "%s-%02X%02X%02X", BLE_NAME_PREFIX, gDeviceId[0], gDeviceId[1], gDeviceId[2]);

  NimBLEDevice::init(name);
  NimBLEDevice::setPower(ESP_PWR_LVL_P9);
  NimBLEDevice::setMTU(247);

  gServer = NimBLEDevice::createServer();
  gServer->setCallbacks(new ServerCallbacks());

  NimBLEService *svc = gServer->createService(SERVICE_UUID);
  gRxChar = svc->createCharacteristic(RX_UUID, NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_NR);
  gTxChar = svc->createCharacteristic(TX_UUID, NIMBLE_PROPERTY::NOTIFY);
  gStatusChar = svc->createCharacteristic(STATUS_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::NOTIFY);
  gConfigChar = svc->createCharacteristic(CONFIG_UUID, NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::WRITE);

  gRxChar->setCallbacks(new RxCallbacks());
  gConfigChar->setCallbacks(new ConfigCallbacks());

  svc->start();

  NimBLEAdvertising *adv = NimBLEDevice::getAdvertising();
  adv->addServiceUUID(SERVICE_UUID);
  adv->setScanResponse(true);
  adv->start();
}

void setupRadio() {
  pinMode(E220_M0_PIN, OUTPUT);
  pinMode(E220_M1_PIN, OUTPUT);
  pinMode(E220_AUX_PIN, INPUT_PULLUP);
  digitalWrite(E220_M0_PIN, LOW);
  digitalWrite(E220_M1_PIN, LOW);

  E220.begin(E220_UART_BAUD, SERIAL_8N1, E220_RX_PIN, E220_TX_PIN);
  readE220Config();
}

// E220 register read (C1 00 06)
static const int kE220BaudTable[] = {1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200};
void readE220Config() {
  digitalWrite(E220_M0_PIN, HIGH);
  digitalWrite(E220_M1_PIN, HIGH);
  delay(50);
  // Wait for AUX HIGH (ready)
  uint32_t start = millis();
  while (digitalRead(E220_AUX_PIN) == LOW && millis() - start < 2000) delay(10);
  if (digitalRead(E220_AUX_PIN) == LOW) {
    Serial.println("[E220] AUX timeout - using defaults");
    digitalWrite(E220_M0_PIN, LOW);
    digitalWrite(E220_M1_PIN, LOW);
    return;
  }
  // Flush and send read command
  while (E220.available()) E220.read();
  uint8_t cmd[] = {0xC1, 0x00, 0x06};
  E220.write(cmd, 3);
  E220.flush();
  // Wait for 9-byte response
  start = millis();
  while (E220.available() < 9 && millis() - start < 1000) delay(10);
  if (E220.available() >= 9) {
    uint8_t hdr=E220.read(), st=E220.read(), ln=E220.read();
    uint8_t addh=E220.read(), addl=E220.read();
    uint8_t r0=E220.read(), r1=E220.read(), r2=E220.read(), r3=E220.read();
    if (hdr==0xC1 && st==0x00 && ln==0x06) {
      gCfg.channel = r2;
      static const int pt[]={30,27,24,21};
      gCfg.txpower = pt[r1 & 3];
      gCfg.baudIdx = (r0 >> 5) & 7;
      gCfg.parity = (r0 >> 3) & 3;
      gCfg.airrate = r0 & 7;
      gCfg.txmode = (r3 >> 6) & 1;
      gCfg.lbt = (r3 >> 4) & 1;
      gCfg.subpkt = (r1 >> 6) & 3;
      gCfg.rssiNoise = (r1 >> 5) & 1;
      gCfg.rssiByte = (r3 >> 7) & 1;
      gCfg.worCycle = r3 & 7;
      gCfg.addr = ((uint16_t)addh << 8) | addl;
      Serial.printf("[E220] Read OK: ch=%d tx=%d baud=%d\n", r2, gCfg.txpower, kE220BaudTable[gCfg.baudIdx]);
    }
  } else {
    Serial.printf("[E220] No response (%d bytes)\n", E220.available());
  }
  digitalWrite(E220_M0_PIN, LOW);
  digitalWrite(E220_M1_PIN, LOW);
}

void sendBleFrame(const Frame &f) {
  uint8_t raw[260];
  const size_t n = encodeFrame(f, raw, sizeof(raw));
  if (n == 0 || !gBleClientConnected) return;

  gTxChar->setValue(raw, n);
  gTxChar->notify();

  if (f.requireAck) {
    gPendingBleTx.active = true;
    gPendingBleTx.frame = f;
    gPendingBleTx.lastSendMs = millis();
    gPendingBleTx.retries = 0;
  }
}

void processBleTxQueue() {
  if (gPendingBleTx.active || gBleTxQueue.empty()) return;
  Frame out{};
  if (gBleTxQueue.pop(out)) sendBleFrame(out);
}

void handlePendingAckTimeout() {
  if (!gPendingBleTx.active) return;
  const uint32_t now = millis();
  if ((now - gPendingBleTx.lastSendMs) < gCfg.ackTimeoutMs) return;

  if (gPendingBleTx.retries >= gCfg.maxRetries) {
    gPendingBleTx.active = false;
    updateFlowState(FLOW_TX_FAILED);
    pushStatusFrame(true);
    enqueueError(0x02, gPendingBleTx.frame.type, "ACK_TIMEOUT");
    return;
  }

  gPendingBleTx.retries++;
  gPendingBleTx.lastSendMs = now;
  uint8_t raw[260];
  const size_t n = encodeFrame(gPendingBleTx.frame, raw, sizeof(raw));
  gTxChar->setValue(raw, n);
  gTxChar->notify();
}

void queueRadioTextFromBle(const Frame &in) {
  // TEXT payload app->esp: [dstId:3][utf8...]
  if (in.len < 4) {
    enqueueError(0x10, MSG_TEXT, "TEXT_PAYLOAD_TOO_SHORT");
    return;
  }
  Frame out{};
  out.type = MSG_TEXT;
  out.seq = in.seq;
  out.len = in.len;
  memcpy(out.payload, in.payload, in.len);
  out.requireAck = false;

  if (!gRadioTxQueue.push(out)) {
    enqueueError(0x11, MSG_TEXT, "RADIO_TX_QUEUE_FULL");
    updateFlowState(FLOW_BUSY);
    pushStatusFrame(true);
    return;
  }
  updateFlowState(FLOW_TX_IN_PROGRESS);
  pushStatusFrame(true);
}

void processBleRxQueue() {
  Frame in{};
  while (gBleRxQueue.pop(in)) {
    if (in.type != MSG_ACK) enqueueAck(in.seq);

    switch (in.type) {
      case MSG_ACK:
        if (gPendingBleTx.active && in.seq == gPendingBleTx.frame.seq) {
          gPendingBleTx.active = false;
          updateFlowState(FLOW_READY);
        }
        break;

      case MSG_TEXT:
        queueRadioTextFromBle(in);
        break;

      case MSG_CONFIG:
        applyConfigPayload(in.payload, in.len);
        emitConfigFrame(in.seq);
        pushStatusFrame(true);
        break;

      case MSG_PROFILE:
        // app announced profile; keep last profile only by emitting own profile back
        pushProfileFrame();
        break;

      case MSG_WHOIS:
        pushProfileFrame();
        break;

      default:
        enqueueError(0x20, in.type, "UNKNOWN_TYPE");
        break;
    }
  }
}

void sendRadioFrame(const Frame &f) {
  uint8_t raw[260];
  const size_t n = encodeFrame(f, raw, sizeof(raw));
  if (n == 0) return;
  E220.write(raw, n);
}

void processRadioTxQueue() {
  const uint32_t now = millis();
  if ((now - gLastRadioTxMs) < gCfg.radioTxIntervalMs) return;
  Frame out{};
  if (!gRadioTxQueue.pop(out)) return;

  sendRadioFrame(out);
  gLastRadioTxMs = now;
  updateFlowState(FLOW_TX_DONE);
  pushStatusFrame(true);
}

void pumpRadioRxBytes() {
  Frame f{};
  while (E220.available()) {
    uint8_t b = (uint8_t)E220.read();
    if (decodeByte(gRadioParser, b, f)) {
      if (!gRadioRxQueue.push(f)) {
        enqueueError(0x30, f.type, "RADIO_RX_QUEUE_FULL");
      }
    }
  }
}

void processRadioRxQueue() {
  Frame in{};
  while (gRadioRxQueue.pop(in)) {
    if (in.type == MSG_TEXT || in.type == MSG_PROFILE) {
      Frame out{};
      out.type = in.type;
      out.seq = allocSeq();
      out.len = in.len;
      memcpy(out.payload, in.payload, in.len);
      out.requireAck = true;
      if (!gBleTxQueue.push(out)) enqueueError(0x31, in.type, "BLE_TX_QUEUE_FULL");
    }
  }
}

void periodicProfile() {
  const uint32_t now = millis();
  if ((now - gLastProfileMs) >= (uint32_t)gCfg.profileIntervalSec * 1000UL) {
    pushProfileFrame();
  }
}

void periodicFlowState() {
  const uint32_t now = millis();
  if ((now - gLastFlowNotifyMs) < 500) return;
  gLastFlowNotifyMs = now;

  if (gBleRxQueue.size() > 24 || gBleTxQueue.size() > 24 || gRadioTxQueue.size() > 24 || gRadioRxQueue.size() > 24) {
    updateFlowState(FLOW_BUSY);
  } else if (!gPendingBleTx.active && gFlowState == FLOW_BUSY) {
    updateFlowState(FLOW_READY);
  }
}

// ========================= Arduino =========================
void setup() {
  Serial.begin(SERIAL_BAUD);
  delay(200);

  uint64_t ef = ESP.getEfuseMac();
  gDeviceId[0] = (uint8_t)((ef >> 16) & 0xFF);
  gDeviceId[1] = (uint8_t)((ef >> 8) & 0xFF);
  gDeviceId[2] = (uint8_t)(ef & 0xFF);
  gCfg.userId24 = ((uint32_t)gDeviceId[0] << 16) | ((uint32_t)gDeviceId[1] << 8) | gDeviceId[2];
  snprintf(gCfg.username, sizeof(gCfg.username), "u%02X%02X%02X", gDeviceId[0], gDeviceId[1], gDeviceId[2]);

  analogReadResolution(12);
  setupRadio();
  setupBle();

  emitConfigFrame();
  pushStatusFrame(true);
  pushProfileFrame();

  Serial.printf("BLE ready, deviceId=%02X%02X%02X\n", gDeviceId[0], gDeviceId[1], gDeviceId[2]);
}

void loop() {
  pumpRadioRxBytes();
  processBleRxQueue();
  processRadioTxQueue();
  processRadioRxQueue();
  processBleTxQueue();
  handlePendingAckTimeout();
  periodicProfile();
  periodicFlowState();
  pushStatusFrame(false);
}
