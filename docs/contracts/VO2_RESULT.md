# VO2max test result (phone -> Home Assistant)

> **DRAFT. Not in force. No code or tests pin this yet.** Owner review needed. Changes follow [README.md](README.md): this document and its tests first, then the parts.
> Specification of the test itself: [../app/VO2_TEST_SPEC.md](../app/VO2_TEST_SPEC.md) (protocol `eb-2016-s1`, equation of Bjorkman 2016, PMID 27311582). The equation and protocol are **not** defined here; the receiver never recomputes them.

The Android app publishes one JSON document per finished or aborted guided test. Home Assistant stores it durably, acknowledges it (or rejects it with a reason), and shows it.
This contract is **separate from** [MQTT_RECORDS.md](MQTT_RECORDS.md): `ride_log` topics, `(boot, seq)` identity, ack rules and `SyncEngine` are untouched.

The result is an **Ekblom-Bak style estimate** from a road/e-bike adaptation that has not been validated (the test was validated on a braked laboratory ergometer). It is never a measured VO2max, and no field, entity or dashboard text may say "measured".

## Topics

Base topic `ebike/vo2/result` (configurable on the phone like the ride topic; the ack topic is derived).

| Topic | Direction | QoS | Retained | Content |
|---|---|---|---|---|
| `ebike/vo2/result` | phone -> HA | 1 | **no** | one result JSON per message |
| `ebike/vo2/result/ack` | HA -> phone | 1 | no | stored: `{"test_id":"...","stored":true}`; rejected: `{"test_id":"...","stored":false,"reason":"..."}`; plus `"mac"` in both when `ack_secret` is set |

### Why not retained (decision, DRAFT)

The design doc proposed a retained message. Rejected because:
- A retained topic keeps **one** message. If two tests (or a test and an aborted attempt) are published while Home Assistant is down, only the last survives: history is lost. This project's rule is nothing lost.
- A retained publish is accepted by the broker, not by Home Assistant. Without a Home Assistant ack the phone cannot honour "release only after the ack".
- A retained message is replayed to every new subscriber and after every HA restart, so the receiver must in any case be idempotent. With a non-retained message plus a durable phone outbox, the phone is the source of truth until the ack, as for ride records.

Retained is acceptable **only** for a derived "latest" topic that Home Assistant itself may publish for dashboards (not required).

## Result JSON

One object, UTF-8, at most 8 KB. Keys are fixed; unknown keys are ignored (forward compatible); a missing optional value is **left out, never `null`**. Numbers are JSON numbers. The HR series is **not** published (owner decision); an optional `hr_summary` object may be added later under `schema` 1.

Golden fixture (**ARITHMETIC ONLY, NOT A VALIDATION**). A man aged 67 and 61 kg, training status `inactive` (stage-2 table value 88 W, spec 2.3, first value of the pair), stage 1 at 30 W, `HR_standard` 100 bpm, stage-2 HR 135 bpm, so dHR = 35 bpm and dPO = 88 - 30 = **58 W** (`d_po_source = measured_mean`, the measured means equal the targets here). Ratio 35/58 = 0.603448 rounded to **0.60**.
`ln V = 2.04900 - 0.00858*67 - 0.90742*0.60 + 0.00178*58 - 0.00290*100 = 2.04900 - 0.57486 - 0.544452 + 0.10324 - 0.29000 = 0.742928`;
`V = exp(0.742928) = 2.1021 L/min` (2.102082); ml/kg/min is computed from the **unrounded** L/min: `2.102082 / 61 * 1000 = 34.46`, shown as **34.5** (from the rounded 2.10 it would read 34.4, which is why the unrounded value is specified).
Range: age 65-75 men, larger absolute limit 0.70 L/min (S3): `(2.102082 - 0.70) / 61 * 1000 = 22.98` -> **23.0** and `(2.102082 + 0.70) / 61 * 1000 = 45.94` -> **45.9** ml/kg/min (1.40 to 2.80 L/min).
Timing: no rest extension, no raise: `total = 900 + 240 + 240 + 180 = 1560 s` (26:00), so `finished_epoch - started_epoch = 1560`. Quality: every criterion of spec 2.9 is met (power in band 90 % and 85 %, both steady, coverage 98 %, rated at the first prompt, rest ready, conditions ok) = 100, no flags. The heart-rate, power, in-band and assist values are invented to exercise the formula; no ride produced them.

```json
{
  "schema": 1,
  "test_id": "vo2-1791540000-3f9a1c",
  "protocol_id": "eb-2016-s1",
  "protocol_version": 1,
  "app_version": "0.0.0",
  "status": "complete",
  "started_epoch": 1791540000,
  "finished_epoch": 1791541560,
  "rider": {"age": 67, "sex": "male", "weight_kg": 61.0, "rest_hr": 58, "hr_max": 161, "hr_max_method": "tanaka"},
  "setup": {"assist": "off", "hr_source": "chest_strap", "training_status": "inactive", "clearance_confirmed": false, "questions_version": 1, "pretest_conditions_ok": true, "seated_rest_s": 900},
  "safety": {"ceiling_pct": 85, "hr_ceiling_bpm": 137, "hr_max_method": "tanaka", "ceiling_changed": false},
  "stages": [
    {"n": 1, "target_w": 30, "mean_w": 30.0, "in_band_pct": 90, "mean_hr": 100.0, "end_hr": 101, "steady": true, "hr_coverage_pct": 98},
    {"n": 2, "target_w": 88, "mean_w": 88.0, "in_band_pct": 85, "mean_hr": 135.0, "end_hr": 136, "steady": true, "hr_coverage_pct": 98, "rpe": 13, "rpe_band": "12-16", "rpe_prompts": 1, "load_raised": false}
  ],
  "estimate": {"vo2max": 34.5, "low": 23.0, "high": 45.9, "unit": "ml/kg/min", "vo2max_l_min": 2.10, "method": "ekblom_bak_2016_men", "estimate_basis": "ekblom_bak_style_estimate", "loa_l_min": 0.70, "loa_source": "vaisanen_2020_age_65_75_men", "d_hr": 35.0, "d_po": 58.0, "d_po_source": "measured_mean", "d_po_target": 58.0},
  "quality": {"score": 100, "flags": []},
  "repeat_advice": "next_test_in_about_3_months"
}
```

The fixture above is a shape-and-arithmetic example. Real fixtures come from the first accepted test and are pinned in tests; `quality.score`, `in_band_pct` and the assist level are invented.

| Field | Type, unit | Required | Notes |
|---|---|---|---|
| `schema` | int | yes | this contract's version; receiver rejects an unknown major (`unsupported_schema`) |
| `test_id` | string, `[a-z0-9-]{8,64}` | yes | **idempotency key.** Generated once by the phone at START (`vo2-<start epoch s>-<6 hex random>`), never reused, kept on retransmission |
| `protocol_id`, `protocol_version` | string, int | yes | `eb-2016-s1`, `1` (see spec 2.1); a changed constant bumps the version |
| `app_version` | string | yes | |
| `status` | `"complete"` or `"aborted"` | yes | there is no `invalid` status: an unusable stage is an abort with `abort_reason = invalid_stage` |
| `abort_reason` | closed enum | if aborted | `user_stop`, `rpe_stop`, `rpe_no_answer` (the effort question was asked twice, 15 s apart, and not answered), `hr_dropout`, `hr_ceiling`, `link_lost`, `interrupted`, `app_killed`, `bluetooth_off`, `invalid_stage`, `rest_not_ready` (heart rate still high or bike not ready after the rest extension, and no "continue" within the prompt time). `not_eligible` exists only on the phone: those attempts are **never published** (they come from health answers before a test exists); a receiver that gets it rejects with `bad_field` |
| `started_epoch`, `finished_epoch` | int, Unix seconds UTC | yes | phone clock; between 2020 and 2100; `finished >= started`. `finished_epoch` is the end of the cool-down for a complete test, the moment of the abort otherwise, and for `app_killed` the time of the last stored sample (spec 4.2) |
| `rider` | object | yes | `age` (years), `sex` (**`male` or `female`**, required, set in Settings), `weight_kg`, `rest_hr` (bpm, informational: the equation does not use it; present only when the rest was ready), `hr_max` (bpm, used only for the app's HR ceiling), `hr_max_method` (`tanaka` or `typed`; locked in the app unless a sign-off exists, see `safety`) |
| `setup` | object | yes | `assist` (`off`/`eco`/`tour`/`sport`/`turbo`/`unknown`), `hr_source` (`chest_strap`), `training_status` (`inactive`/`low`/`moderate`/`high`, used to pick the stage-2 load), `clearance_confirmed` (bool), `questions_version`, `pretest_conditions_ok` (bool), `seated_rest_s` (int, **required**: the seated rest actually taken, including any rest extension; the configured value is normally 900; a configured value below 900 also sets the quality flag `short_rest`). **`dev` (bool) is local metadata only and never appears on the wire** (see "Development results") |
| `safety` | object | **yes** | The heart-rate safety limits in force for this test and, if any, the physician sign-off. Required so that a receiver can tell "defaults" from "omitted": `ceiling_pct` (int, default 85), `hr_ceiling_bpm` (int, the effective ceiling = min(round(pct x round(HRmax)), the age cap of spec 8.4); 137 for age 67 by default), `hr_max_method` (`tanaka` or `typed`), `ceiling_changed` (bool, true when pct or method differs from the defaults), and when a sign-off exists a `signoff` object: `approver` (string, up to 80), `approved_date` (`YYYY-MM-DD`), `ceiling_pct`, `hr_max_method`, `protocol_version`. The free-text note stays on the phone and is never published. Present on aborted records too |
| `stages` | array | complete: 2 entries; aborted: those that finished | per stage: `n`, `target_w` (W; the target held in the reading window), `mean_w` (W, mean rider power over the reading window: 3:00-4:00 of the stage, or 4:00-5:00 for an extended stage 2), `in_band_pct` (0-100), `mean_hr` (bpm, mean of the four readings at 3:15, 3:30, 3:45, 4:00; 4:15 to 5:00 for an extended stage 2), `end_hr` (bpm, the last of the four readings, taken at the end of the stage), `steady` (bool), `hr_coverage_pct` (0-100). Stage 2 also: `rpe_band` (**closed enum** `6-9`, `10-11`, `12-16`, `17-20`; required on a complete record), `rpe` (int 6-20, **optional, present only when the rider entered an exact number**; a band alone never produces a number), `load_raised` (bool; true means a 60 s extension), `initial_target_w` (W, only when `load_raised`), `rpe_prompts` (int 1 or 2: the prompt at which the rider answered). There is no `rpe_missing`: an unanswered effort question aborts the test (`rpe_no_answer`) |
| `rpe_at_stop` | closed enum, as `rpe_band` | optional, aborted only | The last effort band the rider gave before the abort, if any. For `rpe_stop` it is always `17-20` (stage 2 did not finish, so it cannot be in `stages`) |
| `estimate` | object | **only if complete** | `vo2max` (ml/kg/min, 1 decimal, from the unrounded L/min), `low`, `high` (ml/kg/min; point estimate plus and minus `loa_l_min`, divided by body mass), `unit` (literal `ml/kg/min`), `vo2max_l_min` (2 decimals), `method` (`ekblom_bak_2016_men` or `ekblom_bak_2016_women`), `estimate_basis` (literal **`ekblom_bak_style_estimate`**: a road/e-bike adaptation, not a measured VO2max), `loa_l_min` (95 % limit of agreement used, L/min), `loa_source` (e.g. `vaisanen_2020_age_65_75_men`, `bjorkman_2016_crossval_women`), `d_hr` (bpm), `d_po` (W, the value used in the equation), `d_po_source` (`measured_mean` or `target`: which one `d_po` is), `d_po_target` (W, always the target-based value, so both numbers are stored), optional `flags` array (closed: `outside_validated_range`) |
| `quality` | object | only if complete | `score` (int 0-100, spec 2.9), `flags` (array, always present, may be empty; **closed enum**, see below) |
| `repeat_advice` | closed enum | **yes, complete and aborted** | `repeat_another_day`, `next_test_in_about_3_months` (mapping from spec 2.7) |
| `mac` | string | when `ack_secret` set | lower-case hex HMAC-SHA256 of the text **`<test_id>`** with the secret as key (same secret and primitive as ride acks) |

### Closed enums

- `quality.flags`: `stage1_power_off_band`, `stage2_power_off_band`, `stage1_hr_unsteady`, `stage2_hr_unsteady`, `stage1_low_hr_coverage`, `stage2_low_hr_coverage`, `rpe_second_prompt` (answered at the repeat prompt), `rest_hr_high` (the rider continued although the rest heart rate was not ready), `pretest_conditions_not_ok`, `short_rest`, `ceiling_changed`, `cooldown_cut_short` (the test was stopped or interrupted after the last stage-2 reading; the estimate is kept). A flag lists a condition that applies; `short_rest` and `ceiling_changed` are shown but do not change the score. `quality` exists only on complete records, so on aborted records the same facts are read from `setup.seated_rest_s` and `safety.ceiling_changed`.
- `estimate.flags`: `outside_validated_range` only (this one lives in `estimate`, never in `quality`).
- `abort_reason`, `rpe_band` / `rpe_at_stop`, `repeat_advice`, `d_po_source`, `assist`, `training_status`, `hr_max_method`, `method`: as listed in the table. A value outside a closed enum is rejected with `bad_field`.
- `invalid_stage` is an abort reason only, not a flag.

Rules: `status=aborted` **must not** carry `estimate` or `quality`; the receiver drops any such field and logs it. An aborted record **does** carry `rider`, `setup`, `safety`, the `stages` that finished, `rpe_at_stop` (if any) and `repeat_advice`, but never the HR series (this refines owner decision (e), "status only", and is listed for the owner to confirm). An `abort_reason` of `rpe_no_answer` or `rpe_stop` never carries an estimate. Out-of-range VO2max keeps the number but sets `estimate.flags=["outside_validated_range"]` (validity ranges: women 19-62, men 24-76 ml/kg/min). Receiver limits: payload at most 8 KB, keys up to 64 characters, at most 16 stages.

### The `mac` text (decided once)

Both the result message `mac` and the ack `mac` (stored or rejected) sign exactly the text **`<test_id>`** (the id already starts with `vo2-`, so it can not collide with a ride-ack text). The earlier draft's `<test_id>:<finished_epoch>` is withdrawn: the ack carries only `test_id`, `stored` and `reason`, so signing anything else could not be checked by the phone. The receiver verifies the message `mac` before storing; the phone verifies the ack `mac` before releasing or marking an entry rejected.

**Accepted limitation:** the `mac` authenticates the `test_id` only. It does not protect the content of the message against change in transit. The broker link is private (user name and password, optional TLS) and the data is the owner's own; this is accepted for this draft.

## Delivery: exactly once (offline and online)

1. **Write first, then send.** The phone appends the result to its durable `results.jsonl` and, unless it is a development result, to `outbox.jsonl` (atomic, fsync) **before** the Result screen says "Saved" and before any publish.
2. **Publish when the broker is reachable**, reusing the existing relay connection and network-path logic (`MqttRelay` generalised to more than one topic; `ride_log`, `SyncEngine` and `RecordStore` unchanged). Retry all outbox entries after a timeout (default 30 s), a reconnect, or a restart; oldest first. Order between tests is not required, so a stuck entry never blocks others.
3. **Release only after the ack.** Remove a `test_id` from the outbox only when an ack with `stored:true` and that `test_id` arrives (and, when a secret is configured, the `mac` over `<test_id>` is valid). Until then the Result screen says "Waiting to send".
4. **Rejection ack.** When the receiver cannot store a message whose `test_id` it could read, it publishes `{"test_id":"...","stored":false,"reason":"..."}` (with `mac` when a secret is set). `reason` is a closed enum: `bad_schema` (not valid JSON or a required field is missing), `unsupported_schema`, `bad_field` (a value outside its type, range or enum), `bad_mac`, `too_large`, `dev_result` (the message says `setup.dev = true`). On a valid rejection ack the phone **stops retrying that entry, keeps it in `results.jsonl`, marks it `rejected` with the reason** (History shows "Rejected by Home Assistant: reason") and does not delete it. A rejection is not a release of the outbox as a success. If the message is so broken that no `test_id` can be read, no ack is possible: the phone stops after `outboxMaxAttempts` unanswered attempts (PLACEHOLDER 10, spread over at least 24 h) and marks the entry `stuck`, with a visible warning; nothing is deleted.
5. **Home Assistant stores the result durably before it publishes the `stored:true` ack**, and acknowledges **every** received message, including duplicates and rejections.
6. **Idempotency:** `test_id` is the key. A second message with the same `test_id` is acked and ignored; if it differs in content, the stored one wins and a warning is logged.
7. **Ordering and "latest":** sensor entities show the complete result with the greatest `finished_epoch`, so a late delivery of an older test never replaces a newer one.

An acknowledgement is needed: plain MQTT sensor discovery cannot store durably nor confirm receipt, so a small receiver is required, in the pattern of `offline_backfill`.

### Development results (decided 2026-10-21)

A result produced with a chest strap that is not marked **mine** on the phone (spec 5.1) is a development result. It is stored in the phone's `results.jsonl` with `setup.dev = true`, is shown in History as **DEVELOPMENT**, and is **never published**: the outbox excludes it, so it never reaches Home Assistant and is not in any trend or comparison. `dev` is local metadata and is not part of the wire schema. If a message with `setup.dev = true` ever arrives, the receiver rejects it with `dev_result`. Phone-side keep and delete rules: spec 5.1.

## Home Assistant behaviour (for `ha-integration`; not implemented)

- **Receiver** in `custom_components/ha_bosch_ebike/` (logic in a pure `vo2_result_core.py` like `backfill_core.py`, with Python tests): validates schema, closed enums and limits, stores all results (complete and aborted) in HA storage (`Store`), publishes the `stored:true` or `stored:false` ack.
- **Entities** (names with the prefix `eBike VO2max test`, except the last-attempt sensor, which is named `eBike VO2max last attempt`; restore state from the store):

| Entity | State | Attributes |
|---|---|---|
| `sensor.ebike_vo2max_test` | latest complete estimate, ml/kg/min, `state_class: measurement` | `low`, `high`, `quality_score`, `quality_flags`, `test_id`, `finished` (ISO time), `protocol_id`, `protocol_version`, `method`, `estimate_basis`, `assist`, `repeat_advice`, `ceiling_changed`, `short_rest`, `cooldown_cut_short`, `safety` (ceiling pct and bpm, method, sign-off approver and date), `stages` summary, `estimate: true` |
| `sensor.ebike_vo2max_test_low`, `_high` | range bounds | |
| `sensor.ebike_vo2max_test_quality` | score 0-100 | flags |
| `sensor.ebike_vo2max_test_date` | `device_class: timestamp` | |
| `sensor.ebike_vo2max_last_attempt` | `complete` / `aborted` / `none` | `abort_reason`, `rpe_at_stop`, `repeat_advice`, time, `ceiling_changed`, `short_rest` |

- **History and long-term statistics:** import each complete result as an external statistic dated at `finished_epoch`: `ha_bosch_ebike:vo2max_test` (mean = estimate) with `_low` and `_high`. Whether `apexcharts-card` accepts an external statistic is not verified; the dashboard may plot `sensor.ebike_vo2max_test` instead.
- **Existing `sensor.ebike_vo2max_estimate`** (10.8 x best 5 min W/kg + 7): a **lower bound**. Rename the display name to "eBike VO2max floor from best 5 min" (keep `unique_id: ebike_vo2max_estimate`, so the entity id stays), remove the `vo2max_from_hr_uth` attribute and the Uth sentence, and remove the `sensor.xiaomi_watch_5_vo2max` override (the `watch` branch of its state and `source` attribute) so a watch value never replaces it silently. It **stays on the dashboard as a secondary line** once a test exists. The exact files and lines are in spec 9.1 task 9.
- **Dashboard card (Fitness view)**, specification only:
  - Headline `VO2max, from your last test (Ekblom-Bak style estimate)`, number, range `NN to NN`, quality score, date. A yellow badge `ESTIMATE, NOT MEASURED` on every state; the range is always shown with the number. Extra badges when the attributes say so: `SHORT REST`, `HEART-RATE LIMIT CHANGED`, `STOPPED EARLY` (`cooldown_cut_short`).
  - Empty state: `--` and "No test yet. Run the guided test in the eBike Companion app."; last attempt aborted: "Last test stopped (reason). No estimate."
  - Secondary line: "Floor from your best 5 minute power: NN", "a lower limit, not your VO2max".
  - Trend chart: the test series with the low-high band; the floor series as a faint second line.
- Dashboard text never says "measured".
- Age and weight: the test uses the phone's profile (copied into the result). The HA `input_number` age, weight and resting HR keep feeding the floor sensor only. **Source of truth for the test: the phone profile.** The card shows the weight and age used by the test from the result.

## Tests that must pin this contract (before implementation)

- `tests/test_vo2_result.py`: valid complete, valid aborted (with `rpe_at_stop`, `repeat_advice`, `safety`), aborted-with-estimate (dropped), duplicate `test_id`, out-of-order `finished_epoch`, bad schema, oversized, bad `mac` (message and ack), `stored:true` ack content, **`stored:false` acks for each reason** (`bad_schema`, `unsupported_schema`, `bad_field`, `bad_mac`, `too_large`, `dev_result`), missing `safety` (rejected), a value outside each closed enum, out-of-range flag, `d_po_source` / `d_po_target` present, `rpe_band` without `rpe` accepted, `rpe_no_answer` and `rest_not_ready` aborts, `safety.signoff` present / absent (note never present), `setup.dev = true` rejected.
- Android `Vo2JsonTest`: the golden JSON bytes shared with the Python test; the equation test (ln V 0.742928, 2.1021 L/min, 34.5 ml/kg/min, range 23.0 to 45.9, marked arithmetic only); `finished - started = 1560`; outbox idempotency; ack with wrong `mac` ignored; a `stored:false` ack marks the entry rejected and stops retries; development results never enter the outbox.
- A shared fixture file under `tests/` read by both.

## Open items

- Everything marked PLACEHOLDER or NOT-FOUND in the spec (section 10) can change `protocol_version`, not the shape of this document.
- Sign convention of the S3 limits of agreement; the symmetric rule is used until confirmed.
- Owner to confirm: aborted records carry more than "status only" (above); `not_eligible` attempts are local only.
