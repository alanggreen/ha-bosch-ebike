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
- Permissions: Bluetooth connect/scan, notifications, foreground service (connected device), battery-optimisation exemption, and fine location (optional, asked from Settings with its reason shown; used only to read the Wi-Fi name so the broker address can switch between the home IP and the remote host; without it, or with location services off, the remote host is used and Settings says so). The Wi-Fi name is read through a Wi-Fi `NetworkCallback` registered with `FLAG_INCLUDE_LOCATION_INFO` and cached per network; when the name changes the MQTT session is restarted only if the chosen host differs (unacknowledged records are published again, which the receiver ignores). Settings > Home networks has two ruled sections: "Saved home networks (n of 5)" first (with the Add-a-name field, "Connected now" marked, Delete), then "Nearby networks" with a secondary Scan button. Scan lists cached `WifiManager` scan results at once (de-duplicated, hidden names dropped, strongest first, connected network marked, saved ones shown muted as "Saved") and asks for a fresh scan best-effort, showing "Scanning..." with a progress bar until results arrive or 10 s pass, then a live-region line such as "Updated 14:32:07, 6 networks" or, if Android refused the scan, a plain throttling message (`CHANGE_WIFI_STATE`; Android throttles it, and Settings says so when it refuses or finds nothing). It needs the same fine location permission and location services as the Wi-Fi name; `NEARBY_WIFI_DEVICES` is not used, as it would be a second prompt and the connected name still needs fine location. The `neverForLocation` flag stays on `BLUETOOTH_SCAN` only. Companion Device Manager pairing for the ESP.
- Modules: `BridgeLink` (ESP GATT), `HrLink` (chest strap, Wear OS), `Relay` (MQTT or HTTPS to Home Assistant), `RideEngine` (metrics, zones), `Vo2Coach` (test state machine, estimate), `Store`.

### 2.3 Home Assistant side
Reuse `offline_backfill` unchanged: the app publishes the same JSON to `ebike-bridge-mobile/ride_log` and listens for the same acks (signed acks recommended). Test results are published retained to `ebike/vo2/result` and become a sensor.

## 3. Screens

1. **Status** (home screen). One row per link with a plain state: ESP bridge, Bike, SD card (ok/missing/failing, free space), Clock, Home Assistant, Backlog (records waiting, last upload), Heart-rate sensor, Phone battery. A red banner replaces the screen if the SD card is failing or the bike link is down while moving. Connect and Pair phone first check that Bluetooth is on: if not, Android's "turn on Bluetooth" dialog opens (no extra permission beyond `BLUETOOTH_CONNECT`); accepting continues the action, declining leaves the ESP32 bridge row and the count plate saying "Bluetooth is off. Turn Bluetooth on to connect to the bridge", and switching it off while connected explains the dropped link the same way.
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
| **Method** | Ekblom-Bak submaximal test: CV 8.7-9.3%, SEE about 0.28-0.30 L/min, versus Astrand CV about 18%; test-retest no mean difference (Ekblom-Bak 2014, 2016) | about +/-9% |
| **Age-predicted HRmax** | HUNT: SEE 10.8 bpm; agreement limits about +/-18-24 bpm across equations (Nes 2013, Martin 2025) | matters only for methods that extrapolate to HRmax |
| **Heart-rate ratio method (Uth)** | poor agreement in middle-aged and older adults (Ducharme 2021); precluded for cycling (Vehrs 2019) | **do not use** (the Home Assistant package currently shows it as an attribute: remove) |
| **Wrist optical HR** | mean difference during cycling -4.5 bpm (Zhang 2020); accuracy varies by device and is lower on a bike (Gillinov 2017, Reddy 2018); placement matters (Vermunicht 2025); chest straps agree best (Merrigan 2022) | a few bpm at steady state; **use a chest strap for tests** |
| **Bosch rider power** | derived from torque x cadence; no validation found, only owner reports of differences from pedal power meters; pedal meters are typically 1-2% | unknown, plausibly 5-10%; a constant scale error scales the estimate, so **trends on the same bike are more trustworthy than the absolute value** |
| **E-bike physiology** | assistance lowers rider power, HR and VO2 together (Sperlich 2012, McVicar 2022, Bonardi 2025) | confirms rider power as the input |
| **Smartwatch VO2max, for comparison** | exercise-based wearable estimates: bias near zero but individual limits about +/-10 ml/kg/min (Molina-Garcia 2022); Apple Watch MAPE about 13% (Lambe 2025) | the watch's own number is not a gold standard either |

**Realistic overall:** if everything is done well (chest strap, steady stages, same conditions), expect roughly **+/-12-15%** on the absolute VO2max value (method about 9% combined with power and HR errors), and **better than that for change over time**. A result of 36 should be read as "somewhere around 31-41". It is good for seeing a trend over months, not for diagnosis. Anyone needing the true value needs a laboratory gas-exchange test.

The estimate currently in the Home Assistant package (10.8 x best 5-minute W/kg + 7) is the oxygen cost of the best 5-minute power. It is a **lower bound** of VO2max, not an estimate of it, because a rider seldom produces a true all-out 5 minutes. It should be renamed and replaced by the test result.

Citations are given as author and year; the papers were found with a Consensus literature search on 9 Oct 2026 (journals: Scand J Med Sci Sports, Eur J Appl Physiol, Sports Med, PLOS ONE, Med Sci Sports Exerc, J Sports Sci, JMIR mHealth, Eur Heart J Digit Health). Search them by title before relying on a number.

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

### 3.1 Settings screen as built (departure-board world)

Top to bottom: the fault banner, a fixed `SETTINGS` title, the scrolling groups, and a **pinned bar** that stays above the keyboard (`imePadding`) in portrait and landscape.
Every group starts with the same ruled-band head (`SectionHead`, token `sectionHead`, announced as a heading):

1. **At home**: address at home, saved home networks (up to 5, the count turns to alert ink at 5; Delete is an outlined 48 dp button with an Undo snackbar), a manual "Add a network name" field and the Nearby networks scan (signal shown as Strong, Fair or Weak; dBm stays in the TalkBack description). Add puts a name on the list; Save and connect applies it.
2. **Away from home**: the VPN or Tailscale address.
3. **Screen**: Allow landscape.
4. **Running in the background**: battery exemption.
5. **Advanced: MQTT**, collapsed with a one-line summary (`<user> on port <port>`) and the note "Only change these if your installer says so": user name, password and acknowledgement secret (each secret field has a 48 dp Show toggle), topic, port (blank means 1883, shown under the field), secure connection (TLS). It opens by itself when something in it is invalid.

The pinned bar holds `Save and connect` (solid, square, 48 dp), enabled only when the form differs from the saved settings and is valid; the line `Not saved` (ink) while there are unsaved edits; the reason when Save is blocked (no address, bad port); and the connection result as a polite live region ("Saved. Connecting to <host actually used>...", "Connected to the broker.", "Not connected to the broker." with the shortened error and a next step).
Back with unsaved edits asks "Save or discard?" (Save and connect, Discard changes, Keep editing).
Form values are saved instance state (rotation keeps them) and follow the stored settings only while the form is unedited. Known gap: switching to another tab drops unsaved edits (the Back prompt does not cover tabs).
Alert-red sentences use `alertText`: #b3261e on bone (5.8:1) and #ffb4ab on navy (10.2:1; #b3261e itself is 2.64:1 there). The logic (dirty, valid, host used, status wording) is in `ui/SettingsForm.kt` with tests in `SettingsFormTest`.
