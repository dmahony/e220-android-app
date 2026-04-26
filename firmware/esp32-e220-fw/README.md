# ESP32 E220 Bluetooth Firmware

Bluetooth-only companion firmware for the E220 Android app in this repo.
It runs on an ESP32 + Ebyte E220 LoRa module, exposes a BLE GATT link to the Android app, and forwards chat/config requests to the radio.

## What it does

- Pairs with the Android app over Bluetooth LE (BLE) GATT
- Sends and receives newline-delimited JSON messages
- Keeps the E220 chat/config behavior from the original project
- Stores configuration in ESP32 Preferences
- Uses UART2 to talk to the E220 module

## Bluetooth protocol

The Android app talks to the firmware over a simple JSON request/response stream.
Example request:

```json
{"path":"/api/config","method":"GET"}
```

Typical endpoints handled by the firmware:

- `/api/chat`
- `/api/send`
- `/api/config`
- `/api/operation`
- `/api/debug`
- `/api/debug/clear`
- `/api/diagnostics`
- `/api/reboot`

Responses are returned as JSON strings over Bluetooth.

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
- RX/TX are unchanged.
- M0, M1, and AUX were moved off the boot-strapping pins to avoid startup issues.
- Use a stable 3.3V supply for the E220 module.
- Keep the antenna connected before powering the radio.
- The Android app expects the ESP32 to advertise a stable device name.

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

## Connect the ESP32 for flashing

Use a USB-to-serial connection to the ESP32 board.

Typical connections:

- ESP32 5V or 3.3V power from the USB serial adapter or dev board
- GND to GND
- TX on the adapter to RX0 on the ESP32
- RX on the adapter to TX0 on the ESP32

If the board does not auto-enter flash mode, hold BOOT while pressing EN/RESET, then release EN/RESET first and BOOT second.

If your board already has a built-in USB interface, just connect the USB cable and select the resulting serial port.

## Upload

```bash
pio run -t upload --upload-port /dev/ttyUSB0
```

Replace `/dev/ttyUSB0` with the port for your board. Common alternatives are `/dev/ttyUSB1` or `/dev/serial/by-id/...`.

If you want to build and upload in one step:

```bash
PATH=/tmp/pio-venv/bin:$PATH platformio run -e esp32dev -t upload --upload-port /dev/ttyUSB0
```

After upload, the ESP32 should reset automatically. If it does not, press EN/RESET once.

## Android app pairing flow

1. Flash the firmware to the ESP32.
2. Power the board and wait for Bluetooth advertising.
3. Open the Android app.
4. Scan for the ESP32 device and connect.
5. Use the Chat, Settings, and Debug tabs in the app.

## Notes

- This firmware is Bluetooth-only; it does not expose an HTTP interface at runtime.
- The E220 radio link still uses the module's UART interface internally.
- If you change the Bluetooth device name or protocol, update the Android app to match.
