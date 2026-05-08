# ESP32 E220 Firmware

Bluetooth-only companion firmware for the E220 Android app in this repository. It runs on an ESP32 with an Ebyte E220 LoRa module, exposes a BLE GATT interface to the Android client, and forwards chat and configuration requests to the radio.

## What it does

- Exposes a BLE link for the Android app
- Uses the binary protocol described in `../../BLE_LAYER_V2.md`
- Keeps radio messaging, configuration, WiFi, and diagnostics state in sync with the app
- Stores configuration in ESP32 Preferences
- Uses UART2 to talk to the E220 module

## Hardware

Typical ESP32 wiring to the E220 module:

| E220 Pin | ESP32 Pin | Purpose |
|----------|-----------|---------|
| RX | GPIO21 (RX2) | UART2 RX from module |
| TX | GPIO22 (TX2) | UART2 TX to module |
| M0 | GPIO25 | Mode control |
| M1 | GPIO26 | Mode control |
| AUX | GPIO27 | Status output |
| VCC | 3.3V | Power |
| GND | GND | Ground |

Notes:

- RX and TX stay on GPIO21 and GPIO22
- M0, M1, and AUX are moved off boot-strapping pins
- Use a stable 3.3V supply for the radio
- Keep the antenna connected before powering the module
- The Android app expects a stable BLE device name

## Build

If PlatformIO is installed:

```bash
pio run
```

If you need a temporary local install:

```bash
python3 -m venv /tmp/pio-venv
/tmp/pio-venv/bin/pip install -U pip platformio
PATH=/tmp/pio-venv/bin:$PATH platformio run -e esp32dev
```

## Upload

```bash
pio run -t upload --upload-port /dev/ttyUSB0
```

Replace `/dev/ttyUSB0` with the serial port for your board.

If you want to build and upload in one step:

```bash
PATH=/tmp/pio-venv/bin:$PATH platformio run -e esp32dev -t upload --upload-port /dev/ttyUSB0
```

After upload, the ESP32 should reset automatically. If it does not, press EN/RESET once.

## Pairing flow

1. Flash the firmware to the ESP32
2. Power the board and wait for Bluetooth advertising
3. Open the Android app
4. Scan for the ESP32 device and connect
5. Use the Chat, Radio, WiFi, and Debug tabs

## Notes

- This firmware is Bluetooth-only at runtime
- The radio still uses UART internally
- The canonical implementation lives in `src/main.cpp`
- If you change the BLE device name or protocol, update the Android app to match
- See `../../BLE_LAYER_V2.md` for the protocol layout and message types
