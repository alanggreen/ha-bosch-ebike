# Plan: several bridge units, one rider each

Status: **agreed design, implementation not started** (2026-11-17). Decisions from the owner: 2026-11-17 (questions 1-12 of the
2026-11-10 planning session plus A, B, C below). Question 8 was not answered; the recommendation is used and marked.

## Goal

Every household member who rides gets their own ESP32 bridge unit, phone and Bosch account. All their data lands in the one
Home Assistant, labelled with their name, and stays theirs even after a unit moves to another bike.

## Words

| Word | Meaning |
|---|---|
| **unit** | one ESP32 bridge. `unit_id` = last 3 bytes of its MAC in lower-case hex (e.g. `a1b2c3`), the same suffix ESPHome's `name_add_mac_suffix` uses. Not secret. |
| **rider** | a household member. Identified by the slug of their HA **person** (`person.alan` -> `alan`). The person is a label only, never a login. |
| **assignment** | "unit X belongs to rider R". Numbered per unit: 1, 2, 3, ... The number goes up each time the unit gets a different rider and never goes back. `0` means "recorded before the unit was ever provisioned". |

## Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Who are the riders | Household members on the owner's one HA instance |
| 2 | Units, bikes, riders | Rider A + bike A + unit A, rider B + bike B + unit B. A unit can move to another bike; rides already recorded stay with the old rider |
| 3 | Privacy | One dashboard for everyone; no per-user hiding |
| 4 | Where identity and secrets come from | The phone app writes them to the unit over the bonded BLE link at pairing; the unit keeps them in flash (NVS). **One firmware build for all units** |
| 5 | Who creates MQTT logins | The admin, by hand in the Mosquitto add-on; an add-on restart is acceptable |
| 6 | Wi-Fi and hotspot | Per unit, set through the app at pairing (no longer compiled in) |
| 7 | Phones | One phone per unit |
| 8 | What the app shows | **Not answered; using the recommendation:** a read-only label "Reporting as Alan · unit a1b2c3". The app never logs in to HA |
| 9 | Per-rider data | Yes: per-rider statistics, and per-rider helpers (weight, age, FTP, sex) and VO2max |
| 10 | Existing unit | Becomes unit 1, rider `alan`. The old topic `ebike-bridge-mobile/ride_log` is dropped |
| 11 | Bosch cloud | Each rider adds their own Bosch account (already supported by the integration) |
| 12 | Hardware | Two units planned; the second unit and the bike are **not available now** |
| A | Records recorded before a reassignment but uploaded after | Each record carries its assignment number, so it stays with the rider it was recorded for |
| B | Moving a unit | Like a new unit: forget the old phone, pair the new rider's phone, which provisions the new rider. A unit belongs to a rider and a phone, never to a bike |
| C | Credentials | The admin creates the unit's MQTT login and gives it to the rider; the rider types it into the app, which sends it to the unit. It is stored on the phone and on the unit |
| 13 | Plan or start | Plan first, then start with the contracts |

## Design

### Records and topics (contract: `docs/contracts/MQTT_RECORDS.md`, version 2)
- Record bytes 50-51 (today `reserved`, always 0) become `assignment` (u16). Size, CRC coverage and `(boot, seq)` identity are unchanged.
- MQTT record JSON gains `"assign": N` (left out when 0).
- Topics per unit: `ebike/<unit_id>/ride_log`, `.../ack`, `.../status`, and a new retained `ebike/<unit_id>/assignment`
  `{"assign":N,"rider":"alan"}` published by the unit and by the phone whenever they connect.
- Home Assistant subscribes to `ebike/+/ride_log` and `ebike/+/assignment`, keeps the duplicate filter **per unit**, and remembers every
  `(unit, assign) -> rider` it has seen. Records whose assignment is not known yet are stored and acknowledged as usual (nothing is lost),
  but their statistics wait until the assignment is known; a warning names the unit.
- `assign: 0` resolves through configuration (`legacy_rider` per unit). Unit 1's old records -> `alan`.
- Ack secret: one per unit, provisioned like the MQTT password. The HMAC rule is unchanged.

### Phone link (contract: `docs/app/PHONE_LINK_PROTOCOL.md`, version 2)
- New read-only characteristic `…6d05` **identity**: unit id, assignment, rider, which settings are present. Secrets can never be read back.
- New commands: `06 SET_CONFIG` (one key-value per write, staged in RAM), `07 COMMIT_CONFIG` (checks and writes NVS; a new rider
  raises the assignment; the unit then restarts), `08 UNPAIR` (forget this phone and open the pairing window).
- Pairing window (5 minutes) opens: at power-up when no phone is bonded; on a 5-second press of the BOOT button; from `08 UNPAIR`; from the
  existing HA button when the unit is online. Physical access to the unit is enough to take it over; that is accepted for a household.
- The 41-byte status block is unchanged.

### Broker (admin, by hand)
- One Mosquitto login per unit, `ebike_<unit_id>`, with an ACL limited to `ebike/<unit_id>/#` plus the unit's own discovery topics.
- Replaces the HA user "eBike Bridge" for the bridge; that settles the open `local_only` question.

### Home Assistant
- Raw log per unit: `<config>/ha_bosch_ebike_ride_log/<unit_id>/YYYY-MM-DD.jsonl`, every line with its `rider`. Existing files stay where
  they are and count as unit 1 / `alan`.
- Statistics per rider. `alan` keeps today's statistic ids (`sensor.ebike_rider_power`, ...) so the history continues; other riders get
  `sensor.ebike_<rider>_<key>` by default, overridable per rider (to verify in step 2: importing statistics for an entity id that has no entity;
  fall back to external statistics `ha_bosch_ebike:<rider>_<key>`).
- Live values: per unit (MQTT discovery, MAC-suffixed device), shown with the unit's current rider.
- Per-rider helpers (weight, age, FTP, sex, VO2max estimate) from a small generator script, one package file per rider.
- Bosch account per rider: a config entry each; the dashboard locks each rider's cards to their `account_id`.

## Steps (contracts first, then one owner per part)

| Step | Owner | What | Can be verified without the bike and unit 2 |
|---|---|---|---|
| 0 | this session | `PRODUCT.md`, this plan, contracts version 2 | yes (review) |
| 1 | ha-integration | Merge `upstream/main` (one known conflict in `custom_components/ha_bosch_ebike/__init__.py`) before touching HA code | yes, host tests |
| 2 | ha-integration | Receiver v2: per-unit topics and dedupe, assignment map, `legacy_rider`, per-rider statistics, migration of today's state to unit 1 | yes, host tests with two synthetic units |
| 3 | firmware | Spikes first (see risks R1-R3), then: unit id, NVS settings, identity characteristic, commands 06-08, assignment stamped into records, pairing window and BOOT button, per-unit topics | build and host tests; bench on unit 1 only |
| 4 | android-app | Provisioning screen (rider, MQTT login, ack secret, home and hotspot Wi-Fi), identity read, per-unit topic, "Reporting as" label | unit tests; phone with unit 1 |
| 5 | owner (guided) | Mosquitto login and ACL for unit 1, `configuration.yaml` update, add-on restart | yes |
| 6 | firmware + field-test | Migrate unit 1: ideally drain the backlog, flash, provision as `alan`, check records land under `alan` with continuous history; a reassignment round trip to a test rider and back | bench, unit 1 |
| 7 | ha-integration | Dashboard per rider, helper generator, Bosch account per rider | yes |
| 8 | field-test | Bike-path regression ("eBike Connected" with the real bike); unit 2 end to end | **no: waits for the bike and unit 2** |

**Rollout is safe in any order.** A sender talking to the wrong topic gets no ack, so its records stay on the card or in the phone queue
until the matching side is updated. Nothing is lost; data just waits.

## Risks and things to verify first

- **R1 Wi-Fi from flash (ESPHome 2026.7.2).** ESPHome can save one network at run time; the unit needs two (home and hotspot). Spike:
  add both networks from NVS before Wi-Fi starts. If that cannot be done cleanly, fall back to one saved network plus the hotspot compiled in.
- **R2 MQTT login and topics at run time.** Credentials must be set before the MQTT client connects, and the topic prefix and discovery
  names must carry the MAC suffix at run time. Spike before writing the rest.
- **R3 Renaming unit 1's live entities.** The MAC suffix gives unit 1 a new device name, so MQTT discovery creates new entities. The old ones
  must be removed or renamed once (admin step); statistics history is unaffected because it is imported under fixed ids.
- **R4 WireGuard.** Its keys are compiled in per unit, and the 2026-11-10 review found its route probably cannot reach the broker anyway.
  **Open question for the owner:** drop WireGuard from the shared build (away from home, the phone relays) or keep it per unit.
- **R5 Testing limits.** With no bike and no second unit, the bike path and two real units cannot be checked now. Two-unit behaviour is
  covered by host tests with synthetic units only, until step 8.
- The VO2 result contract (`docs/contracts/VO2_RESULT.md`, draft) will need the rider slug when it is implemented.
