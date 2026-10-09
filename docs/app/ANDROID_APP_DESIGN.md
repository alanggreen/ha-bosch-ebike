# eBike Companion (Android) - design

Status: design draft, nothing built yet. Written for the owner of this repo (one rider, one bike, one ESP32 bridge, Home Assistant at home).

## 1. What the app is for

During a ride the phone is the **gateway and the cockpit**:

```
Bosch bike --BLE (LDI)--> ESP32 bridge (in a bag, keeps the SD card)
                              |
                              +--BLE (new GATT service)--> Android app --mobile data/Tailscale--> Home Assistant
                                                              ^
                       chest strap / Xiaomi Watch 5 --BLE-----+
```

1. **Status** - is everything working (bridge, SD card, bike link, Home Assistant link, backlog).
2. **Ride metrics** - live numbers chosen from what exists.
3. **VO2max test supervisor** - a guided submaximal test that tells the rider when to start, what power to hold, when to stop and when to repeat.
4. **Relay** - upload the ESP's buffered records to Home Assistant while riding, using the existing exactly-once protocol (`boot`/`seq`, ack back).

Hard constraints found while researching:

- Bosch's Live Data Interface is meant for small accessories; smartphone support is reported as out of scope, so the **ESP32 stays the bike's accessory** and the app talks to the ESP, not to the bike.
- The ESP currently allows **one** BLE connection (the bike). The firmware needs a second connection slot and a protected service for the phone.
- Rider power comes from the bike's own torque sensor. **No published accuracy validation for the Bosch value was found** (see section 5).

## 2. Architecture

### 2.1 ESP32 GATT service (new)
Custom 128-bit service. All characteristics require an encrypted, bonded link (LE Secure Connections).

| Characteristic | Type | Content |
|---|---|---|
| `live` | notify, 1 Hz | speed, cadence, rider power, battery %, odometer, flags, ESP uptime, epoch |
| `status` | read + notify on change | firmware version, boot id, clock valid, SD state (ok / missing / failing), unacked records, dropped, write failures, slowest write, bike link state, WiFi/MQTT state |
| `command` | write | `SET_TIME(epoch)`, `SYNC_FROM(boot,seq)`, `ACK_UP_TO(boot,seq)`, `OPTIONS(...)` |
| `log` | notify | 56-byte records exactly as stored on the card (CRC included), streamed after `SYNC_FROM` |

- `SET_TIME` is sent on every connection: the phone owns the time, so every record is dated even if the ESP never sees WiFi.
- The phone acknowledges **to the ESP only after Home Assistant acknowledged to the phone**. The card stays the source of truth.
- Needs: `CONFIG_BT_NIMBLE_MAX_CONNECTIONS` 2 or 3, second advertising mode (the bike uses a whitelisted private advertisement; the phone needs a connectable one), and WiFi switched off by option while riding if airtime becomes a problem.
- Standard **Cycling Power Service** can be added on the same link so off-the-shelf apps can display power.

### 2.2 Android app
- Kotlin, Jetpack Compose UI, Room (local database), foreground service for BLE (screen off, pocket).
- Permissions: Bluetooth connect/scan, notifications, foreground service (connected device), battery-optimisation exemption. Companion Device Manager pairing for the ESP.
- Modules: `BridgeLink` (ESP GATT), `HrLink` (chest strap, Wear OS), `Relay` (MQTT or HTTPS to Home Assistant), `RideEngine` (metrics, zones), `Vo2Coach` (test state machine, estimate), `Store`.

### 2.3 Home Assistant side
Reuse `offline_backfill` unchanged: the app publishes the same JSON to `ebike-bridge-mobile/ride_log` and listens for the same acks (signed acks recommended). Test results are published retained to `ebike/vo2/result` and become a sensor.

## 3. Screens

1. **Status** (home screen). One row per link with a plain state: ESP bridge, Bike, SD card (ok/missing/failing, free space), Clock, Home Assistant, Backlog (records waiting, last upload), Heart-rate sensor, Phone battery. A red banner replaces the screen if the SD card is failing or the bike link is down while moving.
2. **Ride.** Large live power with zone bar, plus a metric grid the rider chooses. Available: rider power (now, 3 s, 30 s, 5 min avg), W/kg, zone, cadence, speed, battery %, odometer, distance, ride time, heart rate and % of HRmax, HR drift. Not available from the bike: motor power, assist level, elevation (phone GPS or barometer can add this).
3. **VO2max test.** Guided flow, section 4.
4. **History.** Rides (from Home Assistant), tests with quality score, trend of estimated VO2max with a visible error band.
5. **Settings.** Rider profile (age, weight, sex, resting HR, HRmax method), sensors (ESP pairing, chest strap, watch), Home Assistant connection, alert preferences.

Design rules: glanceable, big numerals, daylight contrast, audio-first during tests (eyes on the road), one-handed use, never block the Status banner.

## 4. VO2max test supervision

### 4.1 Which test
A **submaximal two-stage cycle test**, not a maximal one. The research (section 5) favours the Ekblom-Bak approach: a standard low work rate stage followed by one individually chosen higher work rate, using the change in heart rate per unit change in power. It was validated in ages 20-86, has about half the error of the Astrand test, and test-retest shows no mean drift. A maximal test is not recommended without medical supervision for a 67-year-old.

**Before coding, the published equation and exact stage rules must be taken from Ekblom-Bak et al. (2014, 2016) and verified.** This document does not copy coefficients from memory. The app will also compute a transparent second estimate (individual HR-power line extrapolated to age-predicted HRmax) so the two can be compared.

### 4.2 Supervision flow (state machine)
1. **Readiness check:** health questions (PAR-Q+ style) with an explicit stop if any answer is "yes" and medical clearance is missing; HR sensor quality; bike linked; assist mode recorded; flat, quiet road or path; same route and time of day as last time.
2. **Resting HR:** 3 minutes seated and still.
3. **Warm-up:** 5 min easy, power within a loose band.
4. **Stage 1:** 4 min at the standard low power. Live target band (for example +/-10% of target). Audio: "start now", "a little harder / easier", "30 seconds left".
5. **Stage 2:** 4 min at a higher power chosen from stage 1 so that steady-state HR lands in the moderate range of the published protocol.
6. **Cool-down:** 3-5 min easy.
7. **Result:** estimate, plausible range, quality score, and whether to repeat.

Rider power is the control variable, **not speed**: assisted cycling lowers the rider's power and oxygen use together, so the crank power (which is what the bike measures) is the right input, and the road, wind and assist level only change speed.

### 4.3 Safety and abort rules (parameters to review with a physician)
- Hard stop if HR exceeds a ceiling (default 85% of predicted HRmax; for age 67 predicted HRmax is about 161 bpm by Tanaka, about 168 by HUNT, so the ceiling is roughly 137-143 bpm) or if the rider reports chest pain, dizziness, breathlessness out of proportion, or taps STOP.
- Stop if HR signal drops out for more than 10 s.
- Beta-blockers and some other medicines make HR-based estimates invalid; the app asks and refuses to compute.
- The app is a coaching tool, not a medical device, and says so.

### 4.4 Quality score and repeat rule
Score from: power inside the band for at least 80% of each stage, steady-state HR (change under 5 bpm between minute 3 and 4), HR sensor coverage above 90%, no stop-and-go, and a flat route. Repeat is suggested after 48 hours if the score is below threshold, or if the result differs from the previous valid test by more than twice the method error.

## 5. How reliable is it? (research summary)

| Source of error | Evidence | Effect |
|---|---|---|
| **Method** | Ekblom-Bak submaximal test: CV 8.7-9.3%, SEE about 0.28-0.30 L/min, versus Astrand CV about 18%; test-retest no mean difference ([Ekblom-Bak 2014](https://consensus.app/papers/details/d25c92ca5cea59148d02e6f268f994fd/?utm_source=claude_desktop), [2016](https://consensus.app/papers/details/4cce0266db7d55e3bd53816762c22874/?utm_source=claude_desktop)) | about +/-9% |
| **Age-predicted HRmax** | HUNT: SEE 10.8 bpm; agreement limits about +/-18-24 bpm across equations ([Nes 2013](https://consensus.app/papers/details/4cb4b91290aa5edab3be78bfac866e66/?utm_source=claude_desktop), [Martin 2025](https://consensus.app/papers/details/7c67723c6a405a54ab821bd52c0a8d90/?utm_source=claude_desktop)) | matters only for methods that extrapolate to HRmax |
| **Heart-rate ratio method (Uth)** | poor agreement in middle-aged and older adults ([Ducharme 2021](https://consensus.app/papers/details/98ba42856bd35138b5f79946dfbead94/?utm_source=claude_desktop)); precluded for cycling ([Vehrs 2019](https://consensus.app/papers/details/417079ebef37579e95e7dfe010ebf219/?utm_source=claude_desktop)) | **do not use** (the Home Assistant package currently shows it as an attribute: remove) |
| **Wrist optical HR** | mean difference during cycling -4.5 bpm ([Zhang 2020](https://consensus.app/papers/details/8c4c16ef13a5572d934fef8175a93e93/?utm_source=claude_desktop)); accuracy varies by device and is lower on a bike ([Gillinov 2017](https://consensus.app/papers/details/1861d2ef64bf5b89af59229343249b79/?utm_source=claude_desktop), [Reddy 2018](https://consensus.app/papers/details/2a1f152c67ae51e1abb21b23b5ef0e97/?utm_source=claude_desktop)); placement matters ([Vermunicht 2025](https://consensus.app/papers/details/8f889117e1ed59379bb3a896569a213d/?utm_source=claude_desktop)); chest straps agree best ([Merrigan 2022](https://consensus.app/papers/details/16d405495c7352259f069513aaae894a/?utm_source=claude_desktop)) | a few bpm at steady state; **use a chest strap for tests** |
| **Bosch rider power** | derived from torque x cadence; no validation found, only owner reports of differences from pedal power meters; pedal meters are typically 1-2% | unknown, plausibly 5-10%; a constant scale error scales the estimate, so **trends on the same bike are more trustworthy than the absolute value** |
| **E-bike physiology** | assistance lowers rider power, HR and VO2 together ([Sperlich 2012](https://consensus.app/papers/details/b1989dc73d665a2baa6886e10ed89219/?utm_source=claude_desktop), [McVicar 2022](https://consensus.app/papers/details/ecd1518f951851b8abd031cb08276b52/?utm_source=claude_desktop), [Bonardi 2025](https://consensus.app/papers/details/b57f29c9d14054e5a94ea4e4436c0f58/?utm_source=claude_desktop)) | confirms rider power as the input |
| **Smartwatch VO2max, for comparison** | exercise-based wearable estimates: bias near zero but individual limits about +/-10 ml/kg/min ([Molina-Garcia 2022](https://consensus.app/papers/details/45c50efafac453aaa6a61966891939f3/?utm_source=claude_desktop)); Apple Watch MAPE about 13% ([Lambe 2025](https://consensus.app/papers/details/d7bd5ce5fac55f0686889192994e8b1d/?utm_source=claude_desktop)) | the watch's own number is not a gold standard either |

**Realistic overall:** if everything is done well (chest strap, steady stages, same conditions), expect roughly **+/-12-15%** on the absolute VO2max value (method about 9% combined with power and HR errors), and **better than that for change over time**. A result of 36 should be read as "somewhere around 31-41". It is good for seeing a trend over months, not for diagnosis. Anyone needing the true value needs a laboratory gas-exchange test.

The estimate currently in the Home Assistant package (10.8 x best 5-minute W/kg + 7) is the oxygen cost of the best 5-minute power. It is a **lower bound** of VO2max, not an estimate of it, because a rider seldom produces a true all-out 5 minutes. It should be renamed and replaced by the test result.

## 6. Heart rate sources

| Source | Quality | Effort | Notes |
|---|---|---|---|
| **BLE chest strap** (standard Heart Rate Service) | best | low | Phone connects directly, in parallel with the ESP. **Recommended for tests.** |
| **Xiaomi Watch 5** (Wear OS per product pages) | good at steady state, worse in intervals | medium | Needs a small **Wear OS app** that reads HR via Health Services in an exercise session and sends it to the phone through the Wearable Data Layer. The Home Assistant app on the watch is not real-time enough. Whether the watch can broadcast HR over standard Bluetooth was **not verified**. |
| Health Connect | late, batched | low | Not usable live. |

The app records which source was used and shows a quality indicator; a test with only wrist HR is accepted but flagged.

## 7. Data and privacy
Health data (HR, weight, age, tests) stays on the phone and in your Home Assistant; nothing goes to a third party. Raw records keep the existing CRC and `(boot, seq)` identity. Exports: CSV and FIT/GPX are optional later.

## 8. Phased plan
1. **ESP firmware:** second connection, GATT service, `SET_TIME`, log streaming. Test with the free nRF Connect app on the phone. Success criteria: bike link unaffected over a 1-hour ride with the phone connected.
2. **App MVP:** pairing, Status screen, live Ride screen, set time.
3. **Relay:** publish to the broker, acks back, signed acks.
4. **HR:** chest strap first, Wear OS companion second.
5. **VO2 coach:** state machine and audio, with the second estimate, then the published equation once verified.
6. **Validation:** compare against the watch's value and, if possible, one laboratory test; calibrate Bosch power against pedal meter data if available.

## 9. Open questions
- Which phone model and Android version, and is Tailscale always on during rides?
- Chest strap available or to be bought?
- Is a second Bluetooth connection acceptable for ride-time stability? (Phase 1 answers this.)
- Does any medication affect heart rate?
- Do you want the Wear OS app, or is the chest strap enough?
