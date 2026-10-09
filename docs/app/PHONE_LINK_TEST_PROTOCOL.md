# Phone link test protocols

Protocol details: [PHONE_LINK_PROTOCOL.md](PHONE_LINK_PROTOCOL.md). Tool: nRF Connect on Android.
Service `7f3c1a00-5b2e-4f6a-9d1c-3e8b2a4c6d00`; characteristics `…01` live, `…02` status, `…03` log, `…04` command.

Reading status bytes (little-endian numbers, `40 D7 C8 6A` = `0x6AC8D740`):
- Byte 1 flags: `01` SD, `02` clock, `04` bike, `08` WiFi, `10` MQTT, `20` syncing, `40` recording.
- Byte 4 results: `00` ok, `01` unknown command, `02` bad args, `03` no SD, `04` id not found, `05` fell back to oldest, `06` MTU too small.

## 1. Without the bike

Bike off, ESP powered, phone near it. In HA press **eBike Pair phone**, then bond within 5 minutes.
In nRF Connect: connect, bond, request MTU 247.

| # | Action | Expected |
|---|--------|----------|
| 1.1 | Scan | "HA eBike Bridge" visible |
| 1.2 | Bond | Connects, bonds, stays connected 1 min |
| 1.3 | Read `…02` | 41 bytes; byte 0 = `01`; flags `01` if SD ok, `08` WiFi, `10` MQTT; `04` (bike) off; bytes 5‑8 boot id |
| 1.4 | Subscribe `…02` | About 1 update/s; uptime (bytes 33‑36) rises |
| 1.5 | Subscribe `…01` | 56-byte packet each second; sensor values NaN (`00 00 C0 7F`) |
| 1.6 | Write `05` to `…04` | Byte 3 = `05`, byte 4 = `00` |
| 1.7 | Write `01 40 D7 C8 6A` | Byte 3 = `01`, byte 4 = `00`; flag `02` on; epoch (bytes 37‑40) about 1791547200 and rising |
| 1.8 | Write `01 00 00 00 00` | Byte 4 = `02`; clock unchanged |
| 1.9 | Write `7F` | Byte 4 = `01` |
| 1.10 | Subscribe `…03`, write `02 00 00 00 00 00 00 00 00` | With no records: first notification is `00`; flag `20` briefly on then off; byte 4 = `00` |
| 1.11 | Write `02 00` | Byte 4 = `02` |
| 1.12 | Write `03 01 00 00 00 01 00 00 00` | Byte 4 = `04`; unacked unchanged |
| 1.13 | Disconnect, wait 30 s, reconnect | No new pairing; re-subscribe needed |
| 1.14 | Restart the ESP, reconnect | Bond survives |
| 1.15 | Second phone/app without pressing the pair button | Cannot read or write |
| 1.16 | HA | ESP stays online, logger state OK |

Without an SD card, 1.10 and 1.12 return result `03` (expected).

## 2. With the bike

Do protocol 1 first. Pair the phone before the ride.

| # | Action | Expected |
|---|--------|----------|
| 2.1 | Switch bike on | HA "eBike Connected" on within about 30 s. If not, stop and report |
| 2.2 | Phone connected too | Flags show `04` and `40`; both links stay up 5 min |
| 2.3 | Subscribe `…01` while riding | Speed/cadence/power change and roughly match the bike display |
| 2.4 | Ride 10+ min without WiFi | Unacked (bytes 13‑16) rises |
| 2.5 | Phone out of range, then back | Phone reconnects; bike link and recording unaffected |
| 2.6 | Stop | Unacked about the ride's seconds; dropped and write failures `0` |
| 2.7 | Sync `02 00 00 00 00 00 00 00 00`, MTU 247 | Packets of up to 4 records, ending with `00`; seq in order, no gaps; note last boot/seq |
| 2.8 | Count | Records received equal the unacked count from 2.6 (or slightly more if the ride continued) |
| 2.9 | `02` + boot + seq of a middle record | Resumes after that record; result `00` |
| 2.10 | `03` + boot + seq of a middle record | Result `00`; unacked drops by the right amount |
| 2.11 | Same release again | Result `04`; no change |
| 2.12 | Home WiFi back | Rest uploads via MQTT; unacked falls to 0 |
| 2.13 | HA data | Ride present, no duplicates |
| 2.14 | Disconnect phone mid-sync | Unacked unchanged; nothing lost |
| 2.15 | Power-cycle ESP, ride briefly | New boot id; bike reconnects; phone bond kept |

Report pass/fail per row; for failures include the hex (most useful: 1.3 status, 2.1, 2.8).
