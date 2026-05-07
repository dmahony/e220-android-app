# E220 Android App

E220 Chat is a Jetpack Compose Android client for an ESP32 + Ebyte E220 companion firmware. It connects to the ESP32 over BLE, exchanges a binary framed protocol, and provides a chat-first UI for radio messaging, radio configuration, WiFi control, and diagnostics.

This repository includes:

- Android app in `app/`
- ESP32 companion firmware in `firmware/esp32-e220-fw/`
- Protocol notes in `BLE_LAYER_V2.md`

## Features

- Chat tab with BLE scan/connect, reconnect, message history, and a composer that supports slash commands
- Slash commands for `/gps`, `/clear`, and `/name`
- Radio tab with E220 configuration fields backed by the manual and exposed as dropdowns where appropriate
- WiFi tab for viewing and changing ESP32 WiFi state, scanning for networks, AP/STA settings, and connection status
- Debug tab for device diagnostics and runtime status
- Material 3 UI with light and dark theme support
- Binary BLE transport with MTU-aware chunking, ACK retry, and notification-driven updates

## Requirements

- Android 8.0+ (`minSdk 26`)
- A BLE-capable Android phone
- An ESP32 running the matching companion firmware
- Bluetooth permissions on Android 12+
- Location permission if you use `/gps` or scan on older Android versions

## Protocol

The Android app does not talk JSON over BLE. It uses the binary framed protocol described in `BLE_LAYER_V2.md`.

## Build the Android app

```bash
./gradlew assembleDebug
./gradlew test
```

## Release signing

Release signing is enabled only when these environment variables are set:

- `ANDROID_RELEASE_KEYSTORE`
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`
- `ANDROID_RELEASE_KEY_ALIAS`
- `ANDROID_RELEASE_KEY_PASSWORD`

## Run the app

1. Flash the ESP32 firmware from `firmware/esp32-e220-fw/`.
2. Open the Android project in Android Studio or install the debug APK.
3. Grant the requested Bluetooth permissions.
4. Scan for the ESP32 BLE device and connect.
5. Use the Chat, Radio, WiFi, and Debug tabs.

## Firmware build

See `firmware/esp32-e220-fw/README.md` for the ESP32 firmware build and upload steps.

## Project layout

- `app/src/main/java/com/dmahony/e220chat/` - Android app code
- `app/src/main/res/` - resources and themes
- `app/src/test/` - unit tests
- `firmware/esp32-e220-fw/` - ESP32 companion firmware
- `BLE_LAYER_V2.md` - BLE protocol specification
