# BLE Layer V2

This document describes the binary BLE protocol used between the Android app in this repository and the ESP32 companion firmware.

## Overview

The app connects to the ESP32 over BLE, writes framed binary messages, and listens for notifications. The transport is notification-driven; there is no polling loop for chat data.

## Security model

- Android 12+ uses bonded/encrypted BLE links before the app can read or write the protected STATUS and CONFIG characteristics.
- RX writes are also encrypted on the ESP32 side, preventing unauthenticated local writes to the radio bridge.
- Android 11 / API 30 is intentionally unsupported for the hardened BLE path because the ESP32 NimBLE bonding flow prevented service discovery on LineageOS 18.1 during prior validation.
- The Android client now gates hardened BLE connection setup on API 31+ instead of silently weakening the protections for newer releases.

Threats mitigated:

- Unencrypted local BLE reads of device/config state
- Unauthenticated writes to the ESP32 radio bridge
- Reintroduction of the legacy insecure BLE fallback that was previously used to work around Android 11

## Components

### Android

- `BleUartManager` handles GATT connect/reconnect, MTU negotiation, write chunking, and notification handling
- `BleMessagingRepository` maps protocol messages into app-facing state and message streams
- `BleChatViewModel` exposes the protocol to the UI

### ESP32 firmware

- Custom BLE GATT service with RX, TX, STATUS, and CONFIG characteristics
- Frame parser for the binary protocol
- Queueing for BLE RX, BLE TX, radio TX, and radio RX
- UART bridge to the E220 module
- ACK timeout, retry, and status reporting

## Data flow

- Android send message -> BLE RX write -> ESP32 queue -> E220 UART
- E220 receive data -> ESP32 queue -> BLE TX notification -> Android stream
- STATUS updates are delivered through BLE notifications

## Frame format

All protocol traffic uses this frame layout:

```text
[start:1][type:1][seq:1][len:1][payload:N][crc:1]
```

- `start` is `0xAA`
- `type` is the message type
- `seq` is the sequence number
- `len` is the payload length in bytes
- `payload` is the message body
- `crc` is an XOR of bytes from `start` through `payload`
- maximum payload size is 255 bytes

## Message types

- `0x01` TEXT
- `0x02` ACK
- `0x03` STATUS
- `0x04` CONFIG
- `0x05` PROFILE
- `0x06` ERROR
- `0x07` WHOIS

## Payloads

### TEXT

- App to ESP32: `[dst_user_id:3][utf8_text]`
- ESP32 to app: `[src_user_id:3][utf8_text]`

### ACK

- Empty payload
- Returned with the matching `seq`

### PROFILE

- `[user_id:3][name_len:1][name_utf8]`
- Sent on boot, name change, and periodically

### STATUS

Current STATUS payload fields:

- `flow_state:1`
- `battery_mv:2`
- `last_rssi:1`
- `q_ble_rx:1`
- `q_radio_tx:1`
- `q_radio_rx:1`
- `q_ble_tx:1`
- `uptime_sec:4`
- `fw_major:1`
- `fw_minor:1`
- `fw_patch:1`
- `device_id_24:3`

### CONFIG

Current CONFIG payload fields:

- `ackTimeoutMs:2`
- `maxRetries:1`
- `radioTxIntervalMs:2`
- `statusIntervalMs:2`
- `profileIntervalSec:2`
- `userId:3`
- `nameLen:1`
- `name`

## Reliability

- Every non-ACK outbound frame carries a sequence number
- The receiver returns an ACK with the same sequence number
- The sender retries on ACK timeout up to the configured retry limit
- Timeout and retry settings are part of CONFIG

## MTU and chunking

- The Android client requests MTU 247 on connect
- Encoded frames are split into BLE write chunks of `mtu - 3` bytes
- The ESP32 reassembles chunks into full frames before parsing

## Message flow examples

### Normal send

1. Android sends `TEXT(seq=0x21)`
2. ESP32 enqueues the radio transmit and replies with `ACK(seq=0x21)`
3. ESP32 transmits the message over E220
4. ESP32 emits STATUS updates for transmit progress

### Lost ACK

1. Android sends `CONFIG(seq=0x42)`
2. ACK is lost
3. Android timeout expires
4. Android retries `seq=0x42`
5. ESP32 ACKs the retried frame

### Discovery and profile sync

1. Android connects and enables TX plus STATUS notifications
2. Android sends `WHOIS`
3. ESP32 sends `PROFILE(user_id, name)`
4. Android updates the local user map

## Debugging

### ESP32 logs

- connect and disconnect events
- queue full errors
- ACK timeouts
- flow state transitions

### Android logs

- connection state transitions
- discovered services and characteristics
- requested and negotiated MTU
- outbound frame type, sequence, and length
- ACK timeouts and retry counts
- decoded STATUS values

## Code locations

### ESP32 firmware

- `firmware/esp32-e220-fw/src/main.cpp`

### Android BLE code

- `app/src/main/java/com/dmahony/e220chat/ble/BleTypes.kt`
- `app/src/main/java/com/dmahony/e220chat/ble/BleFrameCodec.kt`
- `app/src/main/java/com/dmahony/e220chat/ble/BleUartManager.kt`
- `app/src/main/java/com/dmahony/e220chat/ble/BleMessagingRepository.kt`
- `app/src/main/java/com/dmahony/e220chat/ble/BleChatViewModel.kt`

## Testing

### Firmware

1. Build and flash `firmware/esp32-e220-fw` on an ESP32 board
2. Verify the BLE device advertises as expected
3. Confirm radio TX/RX and BLE notifications work end to end

### Android

1. Build and install the app
2. Connect to the ESP32 BLE device
3. Confirm MTU negotiation reaches 247
4. Send a TEXT message and verify delivery
5. Test retry behavior by disrupting notifications or range temporarily
