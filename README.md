# E220 Android App

A lightweight Android chat client for E220-based radio devices. The app connects to a nearby BLE companion device, exchanges JSON requests over GATT, and provides a conversation-first UI for messaging, radio configuration, WiFi control, and diagnostics.

The matching ESP32 companion firmware is included in `firmware/esp32-e220-fw/`.

## Features

- BLE scan and connect flow
- Chat-first messaging UI
- Slash-command composer actions
- Radio configuration controls backed by the E220 manual
- WiFi settings and network scan/connect support
- Debug and diagnostics views
- Dark and light themes
- Jetpack Compose and Material 3 UI

## Requirements

- Android 8.0+ (minSdk 26)
- A compatible BLE companion device running the matching E220 service
- Bluetooth permissions enabled on the phone

## Protocol

The app talks to the device over BLE using JSON messages with endpoints such as:

- `/api/chat`
- `/api/send`
- `/api/config`
- `/api/operation`
- `/api/diagnostics`
- `/api/debug`
- `/api/reboot`

## Building

```bash
./gradlew assembleDebug
```

## Running

1. Open the project in Android Studio, or install the debug APK on a device.
2. Grant the requested Bluetooth permissions.
3. Scan for a nearby E220 BLE device.
4. Connect, then use the Chat, Settings, and Debug tabs.

## Changelog

### Recent updates

- Migrated protocol JSON handling to `kotlinx.serialization`.
- Restored E220 Bluetooth discovery and ESP32 BLE advertising.
- Added slash-command behavior to the composer.
- Added WiFi settings, including scan and connect support.
- Expanded the radio settings UI with manual-backed dropdowns.
- Improved chat and BLE stability across the app and firmware.
- Updated the Gradle wrapper and build tooling.

## Project structure

- `app/src/main/java/com/dmahony/e220chat/` - Android app code
- `app/src/main/res/` - resources and themes
- `app/src/test/` - unit tests
- `firmware/esp32-e220-fw/` - ESP32 companion firmware

## Notes

- The app uses Jetpack Compose and Material 3.
- Connection details are saved locally so the last selected device can be reused.
- The firmware folder contains the BLE/NUS ESP32 project that matches this app.
