# Phone link protocol (ESP32 ⇄ Android app)

Enabled with `phone_link: true` on `bosch_ebike_ldi` and `phone_link: <id>` on `ride_data_logger`.
The ESP keeps its connection to the bike and accepts **one** more BLE connection: your phone.

All numbers are little-endian. Reads and writes require an **encrypted (bonded)** link; notifications
are only sent to the registered phone over an encrypted link.

## Service

Service UUID `7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d00`

| UUID suffix | Name    | Properties    | Content |
|-------------|---------|---------------|---------|
| `…6d01`     | live    | notify        | one 56-byte record per second (same layout as the log) |
| `…6d02`     | status  | read, notify  | 41-byte status block |
| `…6d03`     | log     | notify        | record stream, see below |
| `…6d04`     | command | write         | commands, see below |

Characteristic UUIDs: `7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d01` to `…6d04` (same as the service UUID except for the last byte).

## Registering the phone (once)

1. In Home Assistant press **eBike Pair phone** (this also closes the bike pairing window).
2. Within 5 minutes connect from the phone and bond. The first device that completes an encrypted
   link becomes *the* phone and survives reboots. **eBike Clear Bonding** forgets it again.

## Record (56 bytes)

| Offset | Type | Field |
|--------|------|-------|
| 0 | u32 | boot id (random per power-up) |
| 4 | u32 | seq (increments per record) |
| 8 | u32 | epoch seconds (0 = clock not set yet) |
| 12 | u32 | uptime ms |
| 16 | f32 × 8 | sensor values (NaN = unknown) |
| 48 | u8 | binary state mask (bit i = binary sensor i) |
| 49 | u8 | binary known mask |
| 50 | u16 | reserved |
| 52 | u32 | CRC32 over bytes 0‑51 |

`(boot, seq)` is the record id. The live characteristic uses the *next* seq, so live samples
are not log entries.

## Status (41 bytes)

| Offset | Type | Field |
|--------|------|-------|
| 0 | u8 | format version (1) |
| 1 | u8 | flags: 0x01 SD ok, 0x02 clock valid, 0x04 bike connected, 0x08 WiFi, 0x10 MQTT, 0x20 phone sync running, 0x40 recording |
| 2 | u8 | logger state (the status LED code) |
| 3 | u8 | last command opcode |
| 4 | u8 | last command result |
| 5 | u32 | boot id |
| 9 | u32 | next seq |
| 13 | u32 | unacked records on the card |
| 17 | u32 | dropped records (card overflow) |
| 21 | u32 | write failures |
| 25 | u32 | slowest append (ms) |
| 29 | u32 | bytes used on the card |
| 33 | u32 | uptime ms |
| 37 | u32 | epoch seconds (0 = not set) |

## Commands (write to `…04`)

| Opcode | Payload | Meaning |
|--------|---------|---------|
| `01` | u32 epoch | **Set clock.** Accepted 2020‑01‑01 … 2100‑01‑01. |
| `02` | u32 boot, u32 seq | **Send records after (boot, seq).** `(0,0)` = from the oldest unreleased record. Unknown id → result 5 and the stream starts at the oldest unreleased record (safe: only causes a resend). |
| `03` | u32 boot, u32 seq | **Release up to and including (boot, seq).** Frees that part of the card. Unknown/already released id → result 4, nothing changes. |
| `04` | – | Stop streaming. |
| `05` | – | Refresh status. |

Results: 0 ok, 1 unknown command, 2 bad arguments, 3 no SD card, 4 id not found (ack), 5 fell back to
oldest (sync), 6 MTU too small (request ≥ 63, ideally 247).

## Record stream (`…03`)

Each notification: `[u8 count][count × 56-byte record]`. `count` is 0 when the card is caught up
(the end marker). Records per packet = `(MTU − 3 − 1) / 56`, max 4. Reading does **not** release
anything: only command `03` does, so a phone that disconnects mid-sync loses nothing. The MQTT upload
and the phone sync use independent cursors; releasing through either path frees the same records, and
Home Assistant drops duplicates by `(boot, seq)`.

## Test with nRF Connect

1. HA: press **eBike Pair phone**.
2. nRF Connect → scan → **HA eBike Bridge** → Connect → Bond. Request MTU **247**.
3. Open service `7f3c1a00…`. Subscribe (triple‑arrow) to `…01`, `…02`, `…03`.
4. `…02` should update about once a second. Check byte 1 flags, bytes 33‑36 uptime.
5. Set clock: write `01` + epoch LE. Example for 2026‑10‑09 12:00:00 UTC (1791547200 = `0x6AC8D740`):
   `01 40 D7 C8 6A`. Status flag 0x02 turns on.
6. Sync: write `02 00 00 00 00 00 00 00 00`. Records arrive on `…03`; last notification has first
   byte `00`.
7. Release: write `03` + boot (u32) + seq (u32) of the last record you received. Status unacked drops
   and the result byte reads 0.
8. Check Home Assistant still receives the live data/rides over MQTT during all this.

## Version 2: provisioning a unit for a rider (agreed 2026-11-17, NOT YET IN FORCE)

Everything above stays in force until firmware and app ship version 2 together (plan: [../plans/MULTI_RIDER.md](../plans/MULTI_RIDER.md)).
MQTT side: [../contracts/MQTT_RECORDS.md](../contracts/MQTT_RECORDS.md), version 2.

### Record

| Offset | Type | Field |
|--------|------|-------|
| 50 | u16 | **assignment** (was `reserved`, always 0 in version 1). 0 = recorded before the unit was ever provisioned |

Size (56), CRC coverage (bytes 0-51) and `(boot, seq)` identity are unchanged. Old records on the card read as assignment 0.

### Identity characteristic `…6d05` (read, encrypted)

| Offset | Type | Field |
|--------|------|-------|
| 0 | u8 | format version (1) |
| 1 | u8 | flags: 0x01 rider set, 0x02 MQTT login set, 0x04 home Wi-Fi set, 0x08 hotspot Wi-Fi set, 0x10 ack secret set |
| 2 | u8 × 6 | MAC; `unit_id` is the lower-case hex of bytes 5-7 of this block (the last 3 MAC bytes) |
| 8 | u16 | assignment (0 = never provisioned) |
| 10 | u8 | rider length L (0-32) |
| 11 | u8 × L | rider slug, ASCII |

Passwords and secrets are write-only: no read ever returns them.

### Commands (added)

| Opcode | Payload | Meaning |
|--------|---------|---------|
| `06` | u8 key, u8 len, len bytes | **Set config** (staged in RAM, not yet saved). Keys: 1 rider slug, 2 MQTT user, 3 MQTT password, 4 ack secret, 5 home SSID, 6 home Wi-Fi password, 7 hotspot SSID, 8 hotspot password, 9 broker host, 10 broker port (u16). Max 64 bytes per value. `len` 0 clears a value. |
| `07` | – | **Commit config.** Needs rider, MQTT user and MQTT password (staged or already saved). Writes flash; a **different** rider raises the assignment by one. Result 0, then the unit restarts about 1 s later to use the new settings. |
| `08` | – | **Unpair.** Forgets this phone and opens the pairing window. |

Results (added): 7 config incomplete or invalid, 8 flash write failed.

### Pairing window

The 5-minute window in which the first device to bond becomes *the* phone opens: at power-up while no phone is bonded; on a 5-second press
of the BOOT button; after `08`; and from the HA button **eBike Pair phone** when the unit is online. Moving a unit to another rider = unpair,
pair the new rider's phone, provision with the new rider.
