# E220 Android App

A lightweight Android chat client for E220-based radio devices. The app connects to a nearby BLE companion device, exchanges JSON requests over GATT, and provides a chat-first UI for sending and receiving messages, viewing device settings, and checking debug/diagnostic status.

## Features

- BLE scan and connect flow
- Chat screen for sending and receiving messages
- Settings screen for reading and saving radio configuration
- Debug and diagnostics views
- Dark and light themes
- Compose-based UI

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

## Project structure

- `app/src/main/java/com/dmahony/e220chat/` - app code
- `app/src/main/res/` - resources and themes
- `app/src/test/` - unit tests

## Notes

- The app uses Jetpack Compose and Material 3.
- Connection details are saved locally so the last selected device can be reused.
