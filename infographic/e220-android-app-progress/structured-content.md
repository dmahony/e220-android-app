# E220 Android App Progress

## Overview
This infographic shows the current scope of the E220 Android App and the recent implementation milestones from BLE discovery and chat behavior through WiFi settings and kotlinx.serialization migration.

## Learning Objectives
The viewer will understand:
1. what the E220 Android App does and which device features it supports
2. the recent sequence of implementation milestones in the project history
3. what changed in the latest serialization migration

---

## Section 1: Current App Scope

**Key Concept**: The app is a lightweight Android chat client with BLE, chat, settings, and diagnostics features.

**Content**:
- A lightweight Android chat client for E220-based radio devices.
- The app connects to a nearby BLE companion device, exchanges JSON requests over GATT, and provides a chat-first UI for sending and receiving messages, viewing device settings, and checking debug/diagnostic status.
- BLE scan and connect flow
- Chat screen for sending and receiving messages
- Settings screen for reading and saving radio configuration
- Debug and diagnostics views
- Dark and light themes
- Compose-based UI

**Visual Element**: A compact feature cluster that groups the app's primary screens and device connection flow.
- Type: icon cluster
- Subject: BLE device, chat bubble, settings gear, diagnostics panel
- Treatment: clean labels with simple line icons and one highlight for the chat-first UI

**Text Labels**:
- Headline: "E220 Android App"
- Subhead: "A lightweight Android chat client for E220-based radio devices."
- Labels: "BLE scan and connect flow", "Chat screen", "Settings screen", "Debug and diagnostics views"

---

## Section 2: Recent Milestones

**Key Concept**: The project history shows a steady sequence of fixes and feature additions.

**Content**:
- e8cf1a0 (HEAD -> master, origin/master) Migrate protocol JSON to kotlinx.serialization
- c55982d fix: restore E220 Bluetooth discovery and ESP32 BLE advertising
- aab3724 feat: add slash command composer behavior
- 445ff88 Update e220 android app source and gradlew
- 111f7b3 Add WiFi settings functionality to the E220 Android app - Implement WiFi API calls in Repository layer - Add WiFi state management in ViewModel - Create WiFi settings UI screen with status, control, and station mode sections - Add WiFi network scanning and connection capabilities
- 905cc48 Fix: Android app compilation errors and update firmware with ASCII filtering
- be6dde9 Fix: corrupted chat responses and BLE connection stability
- d8d10e8 Fix BLE connection flapping, JSON truncation, and memory corruption

**Visual Element**: A left-to-right timeline with one milestone per node and a stronger emphasis on the latest commit.
- Type: timeline
- Subject: commit history milestones
- Treatment: numbered nodes, arrows between commits, current HEAD highlighted

**Text Labels**:
- Headline: "Recent Progress"
- Subhead: "From BLE stability fixes to serialization migration"
- Labels: "BLE discovery", "Chat behavior", "WiFi settings", "Serialization migration"

---

## Section 3: Latest Change

**Key Concept**: The most recent milestone replaced the protocol JSON path with kotlinx.serialization and touched the protocol, repository, view model, and tests.

**Content**:
- app/build.gradle.kts                               |   3 +-
- .../main/java/com/dmahony/e220chat/JsonSupport.kt  |  44 ++++
- .../main/java/com/dmahony/e220chat/MainActivity.kt |  87 +-------
- app/src/main/java/com/dmahony/e220chat/Models.kt   |  40 +++-
- app/src/main/java/com/dmahony/e220chat/Protocol.kt | 246 ++++++++++-----------
- .../com/dmahony/e220chat/RadioConfigOptions.kt     |  71 ++++++
- .../main/java/com/dmahony/e220chat/Repository.kt   |  36 +--
- .../main/java/com/dmahony/e220chat/ViewModel.kt    |  22 +-
- .../java/com/dmahony/e220chat/E220ProtocolTest.kt  |  51 ++---
- .../dmahony/e220chat/SerializationMigrationTest.kt |  46 ++++
- .../java/com/dmahony/e220chat/WifiProtocolTest.kt   |  95 ++++++++
- build.gradle.kts                                   |   1 +
- 12 files changed, 464 insertions(+), 278 deletions(-)
- create mode 100644 app/src/main/java/com/dmahony/e220chat/JsonSupport.kt
- create mode 100644 app/src/main/java/com/dmahony/e220chat/RadioConfigOptions.kt
- create mode 100644 app/src/test/java/com/dmahony/e220chat/SerializationMigrationTest.kt
- create mode 100644 app/src/test/java/com/dmahony/e220chat/WifiProtocolTest.kt

**Visual Element**: A highlighted change card showing the migration scope and the files touched.
- Type: callout panel
- Subject: serialization migration summary
- Treatment: emphasize the commit title, insertions/deletions, and new files

**Text Labels**:
- Headline: "Latest Change"
- Subhead: "Migrate protocol JSON to kotlinx.serialization"
- Labels: "12 files changed", "464 insertions", "278 deletions", "new tests"

---

## Data Points (Verbatim)

All statistics, numbers, and quotes exactly as they appear in source:

### Statistics
- "Android 8.0+ (minSdk 26)"
- "12 files changed, 464 insertions(+), 278 deletions(-)"

### Quotes
- "A lightweight Android chat client for E220-based radio devices."

### Key Terms
- **kotlinx.serialization**: Migrate protocol JSON to kotlinx.serialization
- **BLE**: BLE scan and connect flow
- **Compose**: Compose-based UI

---

## Design Instructions

Extracted from user's steering prompt:

### Style Preferences
- Technical progress summary
- Status-oriented presentation
- Concise visual narrative

### Layout Preferences
- Timeline-first structure
- Clear current-state summary
- Highlight the latest milestone

### Other Requirements
- Show progress on my e220-android-app
