# Firmware (ESP32) guide

Read `../CLAUDE.md` first.

## What is here
- `components/bosch_ebike_ldi/`: BLE central link to the bike's LDI service (eb20/eb21), plus the **phone link**: a protected GATT service
  (live, status, log stream, commands) on a second connection. NimBLE, `CONFIG_BT_NIMBLE_MAX_CONNECTIONS` is 2 when `phone_link: true`.
- `components/ride_data_logger/`: samples to the SD card first, then MQTT with acknowledgements; the phone-sync handler; status JSON.
  `ride_log_store.h` is the host-testable store (segments, meta slots, ack window, `seek_after`, `read_batch`, `ack_through`).
- `ha-bosch-ebike-home.yaml` (the real device) and `example-bridge-mobile.yaml`. `secrets.yaml` is gitignored; copy `secrets.yaml.example`.

## Invariants (do not break; they are what make the data safe)
- **Write first, ack after store.** A record is on the card before it is sent; it leaves the card only when acknowledged (MQTT ack or the phone's `ACK_UP_TO`).
- A failed card write loses that sample and counts it (`write_failures`); it is not published directly, because it would arrive ahead of older records.
- Record identity is `(boot, seq)`. The 56-byte record layout is on-card and on-air format: changing it means updating `docs/app/PHONE_LINK_PROTOCOL.md`, the app's `Protocol.kt` and both sides' tests.
- **The bike path must stay untouched.** Phone events are routed in `phone_handle_gap_event`; the bike must never be classified as the phone.
  After any BLE change confirm `eBike Connected` still turns on with the real bike (field test).
- The SD card is checked **once at boot**. "SD OK" means it mounted; logger state 2 means writes are refused.

## Workflow
1. `scripts/firmware_host_tests.sh` after every logic change (C++ store test and the Python tests).
2. `scripts/firmware_build.sh` must say `BUILD OK` (about 4 minutes). Fix warnings you introduced.
3. `scripts/firmware_ota.sh` pushes to the ESP32. The owner allowed OTA without asking, but **tell them each time**, and do not flash while a ride is in progress or the ESP32 is out of range. After flashing, check it came back (ping, `eBike Logger State`, reset reason).
4. Commit and push; note in the commit what was tested on hardware and what was not.

Useful reads: the ESP32's web log `http://192.168.1.20/events` (basic auth user `ebike`, password `web_password` in `secrets.yaml`), HA sensors `ebike_log_*`.
