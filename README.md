# E220 Android App

A private, off-grid messaging system that works **without mobile towers, internet access, cloud services, or central servers**.

This project connects Android phones to low-power LoRa radios using Bluetooth, allowing people to send messages directly over radio instead of relying on traditional communication networks.

Designed for privacy, independence, experimentation, and emergency communication, the system gives users control over their own network.

---

## Why This Exists

Most modern communication platforms depend on large companies and centralized infrastructure:

- Mobile phone towers
- Internet providers
- Cloud servers
- Online accounts
- Subscription services

If those systems fail, become unavailable, are censored, or simply don’t exist where you are, communication stops.

The E220 Android App takes a different approach.

Messages are sent directly between radios using LoRa technology, creating a decentralized communication network that can operate:

- In remote areas
- During internet outages
- Off-grid
- At events and festivals
- In emergency situations
- Without requiring any provider or account

There is no central server controlling the network.

You own the hardware.  
You control the communication.

---

## Privacy Focused

The project is designed around the idea that communication should remain private and under the user’s control.

The system does not require:

- Phone numbers
- SIM cards
- Internet connectivity
- User accounts
- Cloud synchronization
- External infrastructure

Messages travel directly between devices over radio.

---

## What It Can Do

The app allows Android devices to connect to E220 LoRa radio modules and:

- Send and receive text messages
- Operate over long distances using LoRa
- Create decentralized radio-based chat networks
- Work in areas with poor or no internet coverage
- Run on portable battery-powered hardware

---

## Hardware

The project uses E220 LoRa radio modules connected to ESP32-based hardware.

The Android app communicates with the ESP32 over Bluetooth, while the ESP32 handles radio communication through the E220 module.

---

## Who Is This For?

This project may be useful for:

- Off-grid communication enthusiasts
- Makers and hackers
- Emergency preparedness
- Rural and remote communication
- Festivals and events
- Mesh networking experimentation
- Privacy-conscious users
- LoRa and radio hobbyists

---

## Open Source

This project is open source and intended for experimentation, learning, and community improvement.

You are free to modify, extend, and adapt it to your own hardware and use cases.

## ESP32 Firmware

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
- `web-app/` - separate Chrome-installable Web Bluetooth PWA version

## Notes

- The app uses Jetpack Compose and Material 3.
- Connection details are saved locally so the last selected device can be reused.
- The firmware folder contains the BLE/NUS ESP32 project that matches this app.
