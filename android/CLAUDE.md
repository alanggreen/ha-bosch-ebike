# Android app guide

Read `../CLAUDE.md` first.

## What is here
Kotlin + Jetpack Compose + Material 3, package `app.ebikecompanion`, minSdk 31.
- `ble/`: `Protocol.kt` (parsing, CRC check, command builders), `BridgeLink.kt` (scan, pair, connect, notifications, command queue).
- `sync/`: `RecordStore` (durable queue file), `RecordJson` (JSON and ack MAC), `MqttRelay` (Paho), `SyncEngine` (the exactly-once rules), `Settings`.
- `ui/`: `Board.kt` (pure logic for the screens, unit tested), `Theme.kt`, `Components.kt`, `StatusScreen.kt`, `RideScreen.kt`, `SettingsScreen.kt`.
- `BridgeService.kt`: foreground service (connectedDevice) that keeps the link and the upload alive.

## Rules
- The contracts are `../docs/app/PHONE_LINK_PROTOCOL.md` and `../docs/contracts/MQTT_RECORDS.md`. Test fixtures are **real captured bytes**; add new ones from real captures when you can.
- `SyncEngine` rules (do not weaken): store before publishing; publish strictly in order from the head of the queue, never skip; drop from the queue and send `ACK_UP_TO` only after Home Assistant's (signed, if configured) ack.
- Sensor slot order and JSON keys come from the firmware config: `RecordJson.SENSOR_KEYS` / `BINARY_KEYS`.
- Design: the **departure board** (rows sorted by urgency, ruled bands, flip cells, bone/navy, Barlow, alert red `#b3261e`, square corners).
  Source of truth: `../PRODUCT.md`, `../.impeccable/surfaces/`, `../docs/app/mockups/board.html`. A failing SD card or lost bridge must be visible on every screen.
  `DESIGN.md` is not written yet; the impeccable documenter writes it from the built app.
- Status wording must tell the truth: "SD card OK" only if the card mounts **and** writes work (`Board.rows` uses logger state 2).

## Workflow (Windows PowerShell)
1. `powershell -File scripts\android_build.ps1` runs the unit tests and builds the APK (4 to 5 minutes the first time).
2. `powershell -File scripts\android_install.ps1` installs on the attached phone and opens the app.
   Check the phone is unlocked first; never screenshot or tap a locked phone. Delete screenshots that show anything private.
3. Drive the UI with `adb shell input tap` only after taking a screenshot to find the control; the nav bar is near y = 2200 on the Pixel.
4. Commit and push; say what was verified on the phone and what was not.
