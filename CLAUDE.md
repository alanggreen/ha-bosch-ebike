# eBike bridge: project guide for agents

A fork of `ha-bosch-ebike` with: an ESP32 **bridge** that reads the Bosch eBike over BLE and logs every ride sample to an
SD card (nothing lost, nothing doubled), a **Home Assistant** receiver and dashboard, an **Android app** that relays the
card's records through the phone, and a printed **enclosure**.

## Who owns what

| Part | Folder | Agent | Build / test |
|---|---|---|---|
| Firmware (ESP32) | `esphome/`, `tests/ride_log_store_test.cpp` | `firmware` | `scripts/firmware_host_tests.sh`, `scripts/firmware_build.sh`, `scripts/firmware_ota.sh` |
| Android app | `android/` | `android-app` | `scripts/android_build.ps1`, `scripts/android_install.ps1` (Windows PowerShell) |
| Home Assistant | `custom_components/`, `dashboard/`, `blueprints/`, `tests/test_*.py` | `ha-integration` | `scripts/firmware_host_tests.sh` (Python part) |
| Enclosure | `hardware/` | `enclosure` | `scripts/enclosure_build.ps1` |
| Rides and bench tests | `docs/app/PHONE_LINK_TEST_PROTOCOL.md` | `field-test` | read-only: reports, never fixes |

Read the folder's own `CLAUDE.md` before working in it.

## Contracts (change these first, together with their tests)

- BLE link: `docs/app/PHONE_LINK_PROTOCOL.md`
- MQTT records and acknowledgements, exactly-once rules, sensor key order: `docs/contracts/MQTT_RECORDS.md`
- Overview and the change procedure: `docs/contracts/README.md`

A change that crosses two parts (a new status field, a new sensor key) is done by one agent in one session.

## Rules for everyone

- **Be honest about what was tested.** Say whether something was verified on the bench, in a unit test, or not at all.
  Do not claim hardware behaviour you did not see. When a test fails, show the output.
- **One owner per physical resource.** Only `firmware` flashes the ESP32. Only `android-app` installs on the phone. Nobody
  changes Home Assistant's own configuration or users without asking the owner first. Rides are the owner's.
- **Never print or commit secrets.** `esphome/secrets.yaml` is gitignored; the Home Assistant token is in `~/.ha_token` inside WSL; the MQTT
  password lives in `secrets.yaml` and in the phone's settings. Read them inside a script, never echo them.
- **Line endings are LF** (`.gitattributes`). Do not run a global renormalise. Check `git diff --stat` before committing: a change touching dozens
  of files you did not edit is a line-ending accident.
- **Git runs inside WSL.** Windows git rejects this repo ("dubious ownership"). Use
  `wsl.exe -d Ubuntu -- bash -c "cd /home/alan/git/ha-bosch-ebike && git ..."`. Commit small, push to `origin main` after the relevant tests pass, never force-push.
  Commit messages end with the co-author line the harness asks for.
- **Windows tools via scripts.** The Android SDK, Gradle, adb and OpenSCAD are Windows-side. Use the `scripts/*.ps1` files rather than ad-hoc commands, and never run Gradle from a `\\wsl.localhost` path.
- **Upstream files:** this is a fork. Keep edits to upstream files small and focused so `git merge upstream/main` stays easy.
- **Do not disturb the owner's phone:** check it is unlocked before any screenshot or tap; never touch it while locked; do not capture notification content.

## The setup (as of 2026-10)

- ESP32 DevKit V1 at `192.168.1.20` (behind an OpenWrt router at `192.168.1.9` that forwards MQTT port 1883 to Home Assistant), powered from a power bank, often out of WiFi range.
- Home Assistant at `192.168.0.10:8123` (also over Tailscale `100.100.243.5`), Mosquitto add-on, MQTT user `ebike_bridge`.
- Phone: a Pixel (Android 17), debug builds only. Time zone Europe/Amsterdam (UTC+2 in summer).
- The rider's design intent and product truth: `PRODUCT.md`. The Android design decisions: `.impeccable/surfaces/` and `docs/app/ANDROID_APP_DESIGN.md`.
