# MQTT records and acknowledgements

Both the ESP32 and the Android app publish ride samples to Home Assistant's `offline_backfill` receiver
(`custom_components/ha_bosch_ebike/offline_backfill.py`, logic in `backfill_core.py`). The format is the same for both senders.

## Topics

Default node topic `ebike-bridge-mobile/ride_log` (the ESP32's `<node name>/ride_log`):

| Topic | Direction | QoS | Content |
|---|---|---|---|
| `<topic>` | sender -> HA | 1, not retained | one JSON record per message |
| `<topic>/ack` | HA -> senders | 1 | `{"boot":N,"seq":N}` plus `"mac"` when a secret is set |
| `<topic>/status` | ESP32 -> HA | 0 | logger status JSON (state, unacked, dropped, write_failures, ...) |

## Record

```json
{"boot":248354586,"seq":12,"epoch":1791540000,"uptime_ms":24000,"rider_power":149,"speed":17.8,"connected":true}
```

- `boot` (random per ESP32 power-up) and `seq` (0, 1, 2, ... within one boot) are the record's **identity**, unique for all time.
- `epoch` is Unix seconds at sample time and is **absent** while the ESP32's clock was not set. `uptime_ms` is always present.
  Undated records are held by Home Assistant until a later record of the same boot supplies `epoch`; the offset
  `epoch - uptime` then dates all of them.
- Sensor values are numbers; a missing value is left out (never `null`). Binary values are `true`/`false`.
- Limits enforced on the receiver: at most 32 fields, keys up to 64 characters, `epoch` between 2020 and 2100.

### Keys and slot order (home configuration)

The 56-byte BLE record carries 8 float slots and 7 flag bits in this order. Slot meaning follows the `sensors:` and
`binary_sensors:` lists of `esphome/ha-bosch-ebike-home.yaml`, and the app keeps the same list in `RecordJson.kt` (`SENSOR_KEYS`, `BINARY_KEYS`).

| Slot | Key | | Bit | Key |
|---|---|---|---|---|
| 0 | `speed` (km/h) | | 0 | `connected` |
| 1 | `cadence` (rpm) | | 1 | `light` |
| 2 | `rider_power` (W) | | 2 | `system_locked` |
| 3 | `ambient_brightness` | | 3 | `charger_connected` |
| 4 | `battery_soc` (%) | | 4 | `light_reserve` |
| 5 | `odometer` (km) | | 5 | `diagnosis_active` |
| 6, 7 | unused | | 6 | `in_motion` |

If the ESP32's sensor list changes, change this table, `RecordJson.kt` and the app's `Record` accessors in the same session.

## Acknowledgement

`{"boot":B,"seq":S}` means: **everything up to and including record (B, S), in log order, is stored.** When
`ack_secret` is configured the ack also has `"mac"`: lower-case hex HMAC-SHA256 of the text `"<boot>:<seq>"` with the
secret as key (`ack_mac()` in `backfill_core.py`, checked by the ESP32 and by the app's `RecordJson.ackTrusted`).

## Rules that make delivery exactly-once

1. **Home Assistant drops any record whose `seq` is at or below the highest it has stored for that `boot`.** So a sender
   must publish **in order, from the oldest unacknowledged record, and never skip one**: a skipped record could never be stored later.
2. **Write first, then send.** A record is stored durably (SD card on the ESP32, the phone's queue file) before it is
   published. If the card refuses the write, the sample is lost and counted, and it is not published directly (that
   would arrive ahead of older records and make Home Assistant discard them).
3. **Release only after the ack.** A record leaves a sender's store (and the ESP32 card) only when Home Assistant has
   acknowledged it. The phone forwards an ack to the ESP32 as `ACK_UP_TO(boot, seq)` over BLE.
4. **Everything unacknowledged is simply published again** after a timeout or reconnect. Repeats are harmless.
5. Home Assistant stores the record durably **before** it publishes the ack.

## Version 2: several units, one rider each (agreed 2026-11-17, NOT YET IN FORCE)

Everything above stays in force until the receiver, the firmware and the app ship version 2 together (plan:
[../plans/MULTI_RIDER.md](../plans/MULTI_RIDER.md)). Version 2 changes only what is listed here; the exactly-once rules are unchanged
and apply **per unit**.

### Topics per unit

`<unit_id>` is the last 3 bytes of the unit's MAC in lower-case hex (e.g. `a1b2c3`). The old default topic `ebike-bridge-mobile/ride_log` is dropped.

| Topic | Direction | QoS | Content |
|---|---|---|---|
| `ebike/<unit_id>/ride_log` | sender -> HA | 1, not retained | one JSON record per message |
| `ebike/<unit_id>/ride_log/ack` | HA -> senders | 1 | as in version 1; HMAC with **this unit's** ack secret |
| `ebike/<unit_id>/ride_log/status` | ESP32 -> HA | 0 | as in version 1 |
| `ebike/<unit_id>/assignment` | ESP32 and phone -> HA | 1, **retained** | `{"assign":N,"rider":"<slug>"}`, published on every connect |

### Record

- New field `assign` (integer 1-65535): the assignment the record was recorded under. Left out when 0 ("before the unit was ever provisioned").
  It is a reserved field like `boot` and `seq`, not a sensor value.
- The BLE record carries it in bytes 50-51 (see the phone link protocol, version 2).

### Assignment and rider

- `rider` is the slug of a Home Assistant person (`person.alan` -> `alan`): lower-case `a-z`, `0-9`, `_`, 1-32 characters.
- A unit's assignment number goes up by one each time the unit is given a **different** rider and never goes back. Re-provisioning the same
  rider (a new password, a new Wi-Fi) keeps the number.
- Home Assistant remembers every `(unit_id, assign) -> rider` it has seen, durably, and never rewrites one. A record belongs to the rider of
  its own `(unit_id, assign)`, not to the unit's current rider.
- `assign` 0 resolves through the receiver's configuration (`legacy_rider` per unit); for unit 1 that is `alan`.
- A record whose assignment is not known yet is **still stored and acknowledged** (rule 5 is unchanged); only its rider-level statistics wait until
  the assignment arrives.

### Duplicate filter

The high-water mark of rule 1 is kept per `(unit_id, boot)`. Two units may never block each other, even if their random boot ids collide.
