# Home Assistant integration and dashboard guide

Read `../../CLAUDE.md` first.

## What is here
- `offline_backfill.py` and `backfill_core.py`: the receiver for ride records (MQTT in, durable store, acknowledgements out, hourly statistics import).
  The contract is `../../docs/contracts/MQTT_RECORDS.md`; `backfill_core.py` is pure Python and has plain-script tests in `../../tests/test_backfill_core.py`.
- `../../dashboard/`: the Lovelace dashboard (`ebike-dashboard.yaml`), helpers (`ebike_package.yaml`) and the theme. Headline values are rider power and a VO2max **estimate**.
- This is a fork of the upstream integration. Keep changes to upstream files small; our additions are the backfill modules, the `__init__.py` hook, and the dashboard.

## Rules
- Home Assistant stores a record durably **before** it acknowledges it. A failed batch rolls back the duplicate filter. Do not weaken this.
- The receiver drops any record at or below the stored high-water mark for its boot. Two senders (ESP32 and phone) are safe only because each sends in order without gaps.
- Never present an estimate as a measurement: the VO2max sensor is a labelled lower-bound estimate. Every dashboard number traces to a real entity or a labelled helper.
- Do **not** change Home Assistant's own configuration, users, add-ons or the broker without asking the owner. Reading through the REST API with the token in WSL `~/.ha_token` is fine
  (`curl -H "Authorization: Bearer $(tr -d '\r\n ' < ~/.ha_token)" http://192.168.0.10:8123/api/states`); do not print the token.
- Python tests are dependency-free scripts, run as in CI: `scripts/firmware_host_tests.sh` (the Python section) or `python3 tests/test_backfill_core.py`.
- Card translations: `node tests/check_card_i18n.mjs` when you touch card strings.

## Useful entities
`sensor.ebike_log_samples_lost`, `sensor.ebike_log_waiting_to_upload`, `binary_sensor.ebike_bridge_mobile_ebike_connected`, `sensor.ebike_bridge_mobile_ebike_logger_state`,
`sensor.ebike_bridge_mobile_reset_reason`. Raw JSONL of backfilled samples and the pending-undated count are under the integration's storage.
