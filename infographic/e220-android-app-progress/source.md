# Source Material

## README excerpt

# E220 Android App
A lightweight Android chat client for E220-based radio devices. The app connects to a nearby BLE companion device, exchanges JSON requests over GATT, and provides a chat-first UI for sending and receiving messages, viewing device settings, and checking debug/diagnostic status.

The matching ESP32 companion firmware is included in `firmware/esp32-e220-fw/`.

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

## Notes

- The app uses Jetpack Compose and Material 3.
- Connection details are saved locally so the last selected device can be reused.
- The firmware folder contains the BLE/NUS ESP32 project that matches this app.

## Recent commit history

* e8cf1a0 (HEAD -> master, origin/master) Migrate protocol JSON to kotlinx.serialization
* c55982d fix: restore E220 Bluetooth discovery and ESP32 BLE advertising
* aab3724 feat: add slash command composer behavior
* 445ff88 Update e220 android app source and gradlew
* 111f7b3 Add WiFi settings functionality to the E220 Android app - Implement WiFi API calls in Repository layer - Add WiFi state management in ViewModel - Create WiFi settings UI screen with status, control, and station mode sections - Add WiFi network scanning and connection capabilities
* 905cc48 Fix: Android app compilation errors and update firmware with ASCII filtering
* be6dde9 Fix: corrupted chat responses and BLE connection stability
* d8d10e8 Fix BLE connection flapping, JSON truncation, and memory corruption

## Latest change summary

---
e8cf1a0 Migrate protocol JSON to kotlinx.serialization
---
app/build.gradle.kts                               |   3 +-
.../main/java/com/dmahony/e220chat/JsonSupport.kt  |  44 ++++
.../main/java/com/dmahony/e220chat/MainActivity.kt |  87 +-------
app/src/main/java/com/dmahony/e220chat/Models.kt   |  40 +++-
app/src/main/java/com/dmahony/e220chat/Protocol.kt | 246 ++++++++++-----------
.../com/dmahony/e220chat/RadioConfigOptions.kt     |  71 ++++++
.../main/java/com/dmahony/e220chat/Repository.kt   |  36 +--
.../main/java/com/dmahony/e220chat/ViewModel.kt    |  22 +-
.../java/com/dmahony/e220chat/E220ProtocolTest.kt  |  51 ++---
.../dmahony/e220chat/SerializationMigrationTest.kt |  46 ++++
.../java/com/dmahony/e220chat/WifiProtocolTest.kt   |  95 ++++++++
build.gradle.kts                                   |   1 +
12 files changed, 464 insertions(+), 278 deletions(-)
create mode 100644 app/src/main/java/com/dmahony/e220chat/JsonSupport.kt
create mode 100644 app/src/main/java/com/dmahony/e220chat/RadioConfigOptions.kt
create mode 100644 app/src/test/java/com/dmahony/e220chat/SerializationMigrationTest.kt
create mode 100644 app/src/test/java/com/dmahony/e220chat/WifiProtocolTest.kt
