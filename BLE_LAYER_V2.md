BLE Layer V2 (ESP32 NimBLE + Android)

1) High-level architecture

Android app (Kotlin)
- BleUartManager: GATT connect/reconnect, MTU negotiation (247), TX/RX framing, ACK retry, notification handling
- BleMessagingRepository: domain mapping (user_id -> username), message/status streams
- BleChatViewModel: UI-facing example usage

ESP32 firmware (Arduino + NimBLE)
- BLE handler: custom service + RX/TX/STATUS/CONFIG characteristics
- Protocol parser: binary framed protocol (start/type/seq/len/payload/crc)
- Queue manager: BLE RX, BLE TX, Radio TX, Radio RX ring queues
- Radio driver: UART non-blocking stream to E220, rate-limited transmissions
- Reliability engine: ACK timeout + retransmit + max retries

Data path
Android send text -> RX write -> ESP32 BLE RX queue -> Radio TX queue -> E220 UART
E220 RX bytes -> Radio RX queue -> BLE TX queue -> TX notifications -> Android stream

No polling for messages: Android uses notifications only (TX + STATUS).

2) Protocol specification

Frame format (all protocol traffic):
[start:1][type:1][seq:1][len:1][payload:N][crc:1]
- start = 0xAA
- crc = XOR of bytes start..payload
- max payload = 255 bytes

Message types:
0x01 TEXT
0x02 ACK
0x03 STATUS
0x04 CONFIG
0x05 PROFILE
0x06 ERROR
0x07 WHOIS

ACK/reliability
- Every outbound non-ACK frame has seq.
- Receiver returns ACK frame with matching seq.
- Sender retransmits if ACK timeout, up to max retries.
- Timeout/retry configurable via CONFIG payload.

TEXT payload
- App -> ESP32: [dst_user_id:3][utf8_text]
- ESP32 -> App: [src_user_id:3][utf8_text]

PROFILE payload
- [user_id:3][name_len:1][name_utf8]
- Sent on boot, name change, periodic (10-30 min configurable).

STATUS payload (18 bytes)
- flow_state:1 (READY=0, BUSY=1, TX_IN_PROGRESS=2, TX_DONE=3, TX_FAILED=4)
- battery_mv:2
- last_rssi:1 (signed)
- q_ble_rx:1
- q_radio_tx:1
- q_radio_rx:1
- q_ble_tx:1
- uptime_sec:4
- fw_major:1
- fw_minor:1
- fw_patch:1
- device_id_24:3

CONFIG payload
- [ackTimeoutMs:2][maxRetries:1][radioTxIntervalMs:2][statusIntervalMs:2][profileIntervalSec:2][userId:3][nameLen:1][name]
- Supported by CONFIG frame and CONFIG characteristic read/write mirror.

3) Packet diagrams

Generic frame
+--------+------+-----+-----+----------+-----+
| 0xAA   | type | seq | len | payload  | crc |
+--------+------+-----+-----+----------+-----+

TEXT app->esp example
payload = [0x00 0x12 0x34 'H' 'i']
frame = [AA 01 2C 05 00 12 34 48 69 crc]

ACK
frame = [AA 02 <same-seq> 00 crc]

4) Message flows

A) Normal send
1. Android sends TEXT(seq=0x21)
2. ESP32 enqueues radio TX and replies ACK(seq=0x21)
3. ESP32 transmits over E220
4. ESP32 emits STATUS(TX_IN_PROGRESS then TX_DONE)

B) Lost ACK
1. Android sends CONFIG(seq=0x42)
2. ACK lost
3. Android timer expires (ackTimeoutMs)
4. Android retransmits seq=0x42
5. ESP32 ACKs seq=0x42

C) Device discovery/profile
1. Android connects, enables TX+STATUS notifications
2. Android sends WHOIS
3. ESP32 sends PROFILE(user_id, name)
4. Android updates local user_id -> username map

5) Code locations

ESP32 firmware main .ino:
- firmware/esp32-e220-fw/esp32_e220_ble.ino

Android BLE data/domain files:
- app/src/main/java/com/dmahony/e220chat/ble/BleTypes.kt
- app/src/main/java/com/dmahony/e220chat/ble/BleFrameCodec.kt
- app/src/main/java/com/dmahony/e220chat/ble/BleUartManager.kt
- app/src/main/java/com/dmahony/e220chat/ble/BleMessagingRepository.kt
- app/src/main/java/com/dmahony/e220chat/ble/BleChatViewModel.kt

6) MTU and chunking behavior

Android requests MTU=247 on connect.
Each encoded frame is automatically split into write chunks of (mtu - 3) bytes.
ESP32 RX callback receives chunks and reassembles stream into complete frames.

7) Debug logging tips

ESP32 serial logs to print:
- connect/disconnect events
- queue full errors (BLE_RX_QUEUE_FULL, BLE_TX_QUEUE_FULL, RADIO_TX_QUEUE_FULL, RADIO_RX_QUEUE_FULL)
- ACK timeout with type/seq
- flow state transitions

Android logs to print:
- connection state transitions
- discovered service/characteristics
- requested/negotiated MTU
- every outbound frame (type, seq, len)
- inbound ACK timeout + retry count
- inbound STATUS decode values

8) Testing instructions

Firmware
1. Build and flash firmware/esp32-e220-fw/esp32_e220_ble.ino on two ESP32+E220 nodes.
2. Ensure wiring:
   E220 RX->GPIO21, TX->GPIO22, M0->GPIO25, M1->GPIO26, AUX->GPIO27.
3. Verify both nodes advertise BLE name E220-BLE-XXXXXX.

Android
1. Build and install app.
2. Use BleChatViewModel/BleMessagingRepository in app startup path.
3. Connect to node BLE address.
4. Confirm MTU >= 247 in log.
5. Call setProfile(myUserId, name).
6. Send TEXT to peer user ID; verify receipt on other phone.

Reliability tests
- Disable notifications briefly (or move out of range) during send; verify retries and eventual TX_FAILED status.
- Flood 50 small messages quickly; verify BUSY state appears, no crash, queues drain back to READY.
- Disconnect/reconnect BLE repeatedly; verify auto reconnect and continued message delivery.

Bandwidth/latency checks
- Confirm no polling loop for messages.
- STATUS interval defaults to 1s (configurable).
- Radio TX interval defaults to 90ms (configurable).
- Payloads are binary only; no JSON overhead over BLE.
