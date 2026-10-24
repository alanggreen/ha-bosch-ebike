# Guided VO2max test: specification

Status: **DRAFT for owner review. Section 2 is filled from the verified research report (2026-10-20); owner decisions (g), (h), (i) of 2026-10-21 and the later decisions (j), (k), (l) of 2026-10-21 are applied (section 10); an independent review of 2026-10-21 (41 findings) is applied. No code may be written until the owner has reviewed it and the physician checklist (section 8.3) is signed.**
Companion contract (DRAFT, not in force): [../contracts/VO2_RESULT.md](../contracts/VO2_RESULT.md).
Parent design: [ANDROID_APP_DESIGN.md](ANDROID_APP_DESIGN.md) (module `Vo2Coach`). On 2026-10-21 the VO2-related lines of its sections 2.2, 3, 4.1, 4.2, 4.4, 6 and 8 were brought into line with this spec; the rest of that document is unchanged. Where the two disagree, this spec wins.

Rider: one person, 67, 61 kg, daylight, handlebar mount (portrait) or pocket/bag (audio only), one-handed. Design world: the departure board
(ruled bands, big numerals, bone/navy, Barlow, square corners, alert red `#b3261e` only for real faults).

What this is: a guided **two-stage cycle test modelled on the Ekblom-Bak (EB) submaximal test**. Power comes from the Bosch bike via the bridge (`rider_power`, crank power, not motor power), heart rate from a
**Bluetooth chest strap** (standard Heart Rate Service 0x180D). The phone describes the procedure, beeps at the start and at every change, always shows what to do now with elapsed and remaining time,
asks for a perceived-exertion rating in the second minute of stage 2, and at the end computes the result on the phone. The result is stored on the phone and, unless it is a development result (5.1), published to Home Assistant.

> **It is not the validated EB test.** The EB test was validated on a mechanically braked laboratory ergometer. On an e-bike on the road the load is not a braked flywheel: assist, gradient, wind and rolling
> resistance change the relation between pedal power and effort, and the Bosch rider-power signal has no published validation (especially its accuracy near 30 W). The output is therefore always called an
> **"Ekblom-Bak style estimate"**, never a measured VO2max, and it is a coaching estimate, not a medical device. See 8.2 for the calibration step.

> **Stopping is always fine. Nothing is lost but this attempt.** There is no pause in this test (decision (j)): the timing is physiological. Best place: a stationary trainer, or a closed quiet flat path. A public road only if nothing else is possible, and then only where you can stop safely at any moment.

## 1. Rules of this document

- Nothing here invents a coefficient or a clinical threshold. Values tagged **VERIFIED** come from the research report (full text of S1-S4). Anything else is marked **NOT-FOUND** (no source gives it), **INFERENCE**, **APP CHOICE** (product decision) or **PLACEHOLDER (to review)** (a config value that must exist but has no source: it needs review).
- UX timings the owner asked for (30 s warning, countdown in the last 5 s of a phase, "stage 2 next" hint in the last 10 s of stage 1) are product choices, not physiology.
- The estimate is one pure function `estimate(config, inputs): Estimate?`; the state machine never contains physiology.

### Sources (all cited by id below)

| Id | Reference | Read |
|---|---|---|
| S1 | Bjorkman F, Ekblom-Bak E, Ekblom O, Ekblom B. Validity of the revised Ekblom Bak cycle ergometer test in adults. *Eur J Appl Physiol* 2016;116(9):1627-38. PMID 27311582, PMC4983286, DOI 10.1007/s00421-016-3412-0. **The current equation.** | full text |
| S2 | Karolinska Institutet / GIH official test manual (not peer reviewed), https://ki.se/media/272183/download and https://ki.se/media/272184/download. Only source of the stage-2 table, the RPE adjustment rules and the rounding footnote. | full text |
| S3 | Vaisanen D, Ekblom O, Ekblom-Bak E, et al. Criterion validity of the Ekblom-Bak and the Astrand submaximal test in an elderly population. *Eur J Appl Physiol* 2020;120(2):307-16. PMID 31820103, PMC6989574, DOI 10.1007/s00421-019-04275-7. Ages 65-75. | full text |
| S4 | Ekblom O, Ekblom-Bak E, et al. Ability of a submaximal cycle ergometer test to detect longitudinal changes in VO2max. *BMC Sports Sci Med Rehabil* 2021. PMID 34906224, PMC8670613, DOI 10.1186/s13102-021-00387-w. | full text |
| S5 | Ekblom-Bak 2014 original test (PMID 23126417), superseded by S1 | abstract only |
| S6 | Osth 2025, propranolol and the EB test | abstract only |
| S7 | Tanaka H, Monahan KD, Seals DR. Age-predicted maximal heart rate revisited. *J Am Coll Cardiol* 2001 (PMID 11153730) | abstract only |

## 2. Protocol parameters and equation (`ProtocolConfig`)

All values immutable and versioned; stored with every result as `protocol_id` + `protocol_version`. Bump the version on any change below.

### 2.1 Identity and phases

| `ProtocolConfig` field | Value | Status / source |
|---|---|---|
| `protocolId` | `eb-2016-s1` (equation of S1, stage rules of S2, adaptations of S3) | APP CHOICE |
| `protocolVersion` | `1` | |
| Warm-up | **none.** The EB test starts from resting heart rate after seated rest. The design doc's 5 min warm-up and 3 min resting-HR phase are removed. | VERIFIED (S1, S2, S3) |
| `seatedRestSeconds` | `900` (15 min seated rest, as in the older-adult validation S3). Phase name `SEATED REST`. A config value in `ProtocolConfig`, not editable in Settings. | S3 cites 15 min; **owner confirmed 15 minutes as the default (decision (g), 2026-10-21)**. A shorter value is **"needs physician or physiologist sign-off"**: a result produced with `seatedRestSeconds` below 900 carries the quality flag `short_rest` and `setup.seated_rest_s`, and the Result screen says "Shorter rest than the standard 15 minutes." Any change of this value bumps `protocolVersion`. |
| Rest readiness | Before stage 1 can start: (a) HR near resting: in the last 60 s of rest the HR spread is at most `restSteadyBpm` and the mean is at most `restHrMarginBpm` above the rider's known resting HR (if none is known yet, the stability test only); (b) the bridge and the bike are linked and a rider-power value is at most `powerStaleSeconds` old. The resting HR is stored in the profile **only when (a) was met**, never after a "continue anyway" (`rest_hr_high`). | `restSteadyBpm = 5`, `restHrMarginBpm = 15`: **NOT-FOUND, PLACEHOLDER (to review)** |
| `restExtraMaxSeconds` | `300`: the longest extra wait at the end of the seated rest while readiness (above) is not met. `buildSchedule` enforces the cap. | PLACEHOLDER (to review) |
| `restPromptTimeoutSeconds` | `60`: after `restExtraMaxSeconds` the rider is asked "Continue anyway, or stop?" (4.1). No tap within 60 s = `Aborted(rest_not_ready)`. | PLACEHOLDER (to review) |
| Resting HR use | recorded for the quality score and the history only. **The equation does not use resting HR or HRmax.** | VERIFIED (equation) |
| `stage1Seconds` | `240` at `stage1Watts = 30` (0.5 kp), cadence 60 rpm, the same for everyone | VERIFIED (S1, S2) |
| `stage2Seconds` | `240`, starting immediately after stage 1, **no rest** | VERIFIED (S2) |
| `stage2ExtraSeconds` | `60`: added once when the load is raised after the minute-2 RPE (S3 variant, see 2.4); a config flag `extendOnRaise` (default true) | VERIFIED (S3); applying it to the S2 rule alone is an APP CHOICE |
| `rpeAskAtSeconds` | `60` into stage 2 (start of the second minute) | VERIFIED (S2: minute 2; S3: after minute 1) |
| `rpeFirstWaitSeconds` | `15`: wait after the first prompt (config value) | APP CHOICE (decision (h)) |
| `rpeRepeatWaitSeconds` | `15`: wait after the one repeat prompt (config value) | APP CHOICE (decision (h)) |
| `rpeCorrectSeconds` | `5`: length of the readback window (4.4) and, separately, of the correction picker after `CORRECT` | APP CHOICE |
| RPE window rule | `buildSchedule` rejects a config where `rpeAskAtSeconds + rpeFirstWaitSeconds + rpeRepeatWaitSeconds + 2 x rpeCorrectSeconds` exceeds 120 s, so the answer and its one correction always fall inside minute 2 and before the first HR reading. With the defaults: 60 + 15 + 15 + 5 + 5 = 100 s. The unanswered deadline is 90 s and **a correction never moves it**. There is no other RPE window and no 30 s window. | APP CHOICE |
| Stage-2 selection | **chosen before the test**, by the rough-guide table (2.3) and the RPE rules (2.4). **No source derives stage 2 from the HR at the end of stage 1.** | VERIFIED (absence in every source) |
| Stage-2 target | a load expected to give RPE about 14 (Borg 6-20) and HR 110-140 bpm for age 50 and over (120-150 bpm under 50). The age-50 symbol was garbled in the PDF; a web summary read it as 50 and over. | VERIFIED; symbol reading to confirm |
| `stage2RaiseCapWatts` | a raise never takes the target above `150` W for a rider aged 65 or over (`191` W, the largest table value, under 65); a start load already above the cap is not lowered. No ramp: the raise applies at once. | PLACEHOLDER (to review); physician item 8 |
| `cadenceRpm` | `60`, constant; no talking, no change of position | VERIFIED (no numeric tolerance in any source) |
| `cadenceBandRpm` | `5` rpm half-width (live cue "faster/slower") | **NOT-FOUND, PLACEHOLDER (to review)** |
| `powerBandPct` | design draft `10` % half-width (at 30 W that is 3 W: finer than the unvalidated Bosch signal can be trusted) | **NOT-FOUND, PLACEHOLDER (to review)**, flagged |
| `hrReadingTimes` | per stage, HR read at `3:15, 3:30, 3:45, 4:00` into the stage; the stage HR is their mean; `end_hr` is the last of the four. With an extended stage 2 the same four offsets are taken from the new end of the stage (`4:15, 4:30, 4:45, 5:00`). | VERIFIED for 4-minute stages; the extended-stage offsets are an **INFERENCE** (NOT-FOUND) |
| Mean-power window | the minute that ends at the last reading: `3:00-4:00` of a 4-minute stage, `4:00-5:00` of the extended stage 2 (it follows the reading offsets). | APP CHOICE |
| Steady-state criterion | none in any source. The app's check (HR change under 5 bpm between minute 3 and 4 of a stage) is the design doc's own, used for the quality score only, not for validity. | NOT-FOUND, APP CHOICE |
| `cooldownSeconds` | `180` easy pedalling after stage 2 | APP CHOICE (not part of the EB test) |
| Total time | `total = seatedRest + restExtra + stage1 + stage2 + (raised and extendOnRaise ? stage2Extra : 0) + cooldown`, with `0 <= restExtra <= restExtraMaxSeconds`. Shortest 900 + 240 + 240 + 180 = **1560 s (26:00)**; longest 1560 + 300 + 60 = **1920 s (32:00)**. | derived |
| Pre-test conditions (readiness list) | no heavy meal 3 h, no smoking 2 h, no vigorous activity the day before or the day of (S2); no food 90 min, no caffeine or nicotine 2 h (S3) | VERIFIED |
| HR sensor | chest strap (Polar in the studies). Wrist heart rate is **not accepted** for a test. | VERIFIED |

### 2.2 Equation, units and guards (S1; identical in S2 and S3)

Output absolute VO2max in **L/min** = exp(ln VO2max).

| Sex | Equation |
|---|---|
| Men | `ln VO2max = 2.04900 - 0.00858 * age - 0.90742 * (dHR/dPO) + 0.00178 * dPO - 0.00290 * HR_standard` |
| Women | `ln VO2max = 1.84390 - 0.00673 * age - 0.62578 * (dHR/dPO) + 0.00175 * dPO - 0.00471 * HR_standard` |

- `age` in years. `HR_standard` = mean HR (bpm) of stage 1 (the 30 W stage, 2.1 reading times). `dHR` = mean HR of stage 2 minus `HR_standard` (bpm).
- `dPO` = work rate of stage 2 minus work rate of stage 1, in **watts**. That the manual table's "factor for higher work rate" (32, 64, 95, 127, 159, 191, 222) equals the stage-2 watts minus about 30 W is an **INFERENCE** of the research report, not stated by the sources.
- `dHR/dPO` is **rounded to 2 decimals before use** (S2 footnote). Rounding mode (half away from zero) is not stated: APP CHOICE.
- Which `dPO` the app uses: `dpoSource = measured_mean` (default; mean rider power over the mean-power window of each stage, because on a road the power is measured, not set by a brake) or `target`. The protocol means the set load; the choice is **APP CHOICE, flagged for review**. Both numbers are stored (`d_po_source`, `d_po`, `d_po_target`).
- **Body mass is not an input.** Relative value: `ml/kg/min = L/min * 1000 / body_mass_kg`, computed from the **unrounded** L/min (body mass from the Settings profile, confirmed at Setup, copied into the result).
- **Guards (decision of this review, finding 3).** Before the equation runs, `estimate` returns `Invalid` (the machine goes to `Aborted(invalid_stage)`, 4.1) when any of these fails: `dPO >= minDpoWatts` (PLACEHOLDER (to review) **20 W**; soft-pedalling gives a dPO near zero and a wildly high result), `dHR > 0` (a falling heart rate gives a negative ratio), the stage-1 mean power within `stage1MeanBandWatts` of 30 W (PLACEHOLDER (to review) **15 W**, i.e. 15 to 45 W), fewer than 3 valid readings in a stage (5.3). The guards apply to the `dPO` actually used. Out-of-band results that pass the guards are flagged, not rejected.
- Worked arithmetic check (ARITHMETIC ONLY, not a validation): see the golden fixture in the contract. Man, 67, `HR_standard` 100, `dHR` 35, `dPO` 58 W (stage 2 at 88 W, the `inactive` table value), ratio 35/58 = 0.603448 -> **0.60**. `ln V = 2.04900 - 0.57486 - 0.544452 + 0.10324 - 0.29000 = 0.742928`; `V = exp(0.742928) = 2.1021 L/min` (2.102082); at 61 kg that is 2.102082 / 61 x 1000 = 34.46, shown as **34.5 ml/kg/min** (from the rounded 2.10 it would be 34.4: hence "unrounded").
- Model quality (S1): model n = 217, CV 8.7 %, SEE 0.28 L/min. Cross-validation n = 115: CV 9.4 %, limits of agreement (95 %) -0.54 to +0.76 L/min men, -0.61 to +0.44 L/min women. Known bias: high VO2max underestimated, low overestimated.

### 2.3 Stage-2 rough guide (S2 table, stored as data)

The load is chosen before the test from sex and training status (`inactive`, `low`, `moderate`, `high`), asked in the readiness check (default: the last answer; the rider confirms). **Each cell has two values in the source (two work rates per cell); which is flywheel power and which is pedal power is NOT-FOUND.** The table is a data file (`stage2_guide.json`, versioned with `protocolVersion`), not code.

| Training status | Woman (W, pair) | Man (W, pair) |
|---|---|---|
| inactive | 59 / 64 | 88 / 95 |
| low | 88 / 95 | 118 / 127 |
| moderate | 118 / 127 | 147 / 159 |
| high | 147 / 159 | 177 / 191 |

Stored as `stage2GuideWatts[sex][status] = [first, second]` plus `stage2GuideColumn`. **Default = the first (lower) value of each pair, a PLACEHOLDER (to review) to confirm.** Reasons: (1) the app measures power at the crank, which for a braked flywheel is not the flywheel value, and which of the two columns corresponds is unknown; (2) the lower load is the safer error: the RPE rules below raise it within the first two minutes if it is too easy, whereas too high a load is the failure the sources warn about (RPE 17 stops the test). The `dPO` used by the equation is whatever load was actually held (2.2), not the table value.

### 2.4 RPE adjustment rules (S2; S3 variant)

The rider is asked for a Borg 6-20 rating **in the second minute of stage 2** (flow in section 4.4). The rider taps one of four bands; a tap is **final and acts at once**. Rules:

| RPE band | Action | Source |
|---|---|---|
| 6 to 9 (below 10) | raise the load by 1 kp | S2 |
| 10 to 11 | raise the load by 0.5 kp | S2 |
| 12 to 16 | keep the load | S2 |
| 17 to 20 | **STOP at once**; no estimate; retest on another day; cannot be corrected | S2 |
| no answer to the prompt and to its one repeat (`rpeFirstWaitSeconds` + `rpeRepeatWaitSeconds`, 15 s + 15 s) | **STOP**; no estimate; `abort_reason = rpe_no_answer`; retest on another day | APP CHOICE (owner, 2026-10-21) |
| below 12 after the first minute (S3 variant) | raise the load and **extend the test by 1 minute** | S3 |

**Why an unanswered prompt stops the test.** The effort rating is the only stop rule in the papers (S2: RPE 17 or more). The app cannot tell a rider who is merely busy from one who cannot answer because something is wrong. So an unanswered prompt is treated as a possible reason to stop, not as a missing value to be worked around: the app asks, waits 15 s, asks once more (spoken and on screen, same picker), waits 15 s more, and if there is still no answer the test stops with no estimate. The cause may be medical, so the stop is followed by the spoken guidance of 3.6.

Conversion of kp to watts: the sources give no formula the research verified. The manual's table steps are about **31.7 W per 0.5 kp** (so about 63 W per 1 kp at 60 rpm); this is an **INFERENCE** from the table, to be confirmed on the bike. Adjustment steps are stored as watts: `raiseBelow10Watts = 63`, `raise10to11Watts = 32` (**PLACEHOLDER (to review)**, derived), limited by `stage2RaiseCapWatts` (2.1). The new target is stored in the result (`initial_target_w` and `target_w`).

### 2.5 Eligibility, validity range and plausible range

| Item | Value | Status |
|---|---|---|
| Age | women 21-86, men 20-84; outside: `not_eligible`, no estimate | VERIFIED (S1) |
| VO2max validity | women 19-62, men 24-76 ml/kg/min. A computed value outside shows the badge `OUTSIDE THE VALIDATED RANGE` and the flag `outside_validated_range` (in `estimate.flags`); the number is shown but marked unreliable. | VERIFIED range; handling APP CHOICE |
| Sex | `male` or `female` from the Settings profile; the test is unavailable until set (no default) | owner decision (a) |
| Women's equation for small, low-fit older men | not used (post-hoc suggestion in S3, r 0.73 vs 0.44, unvalidated) | physician review item |
| Medicines | beta-blockers invalidate the test (S1/S4 exclude; S6 abstract: propranolol overestimated by +0.377 L/min, +11.2 %); other HR-affecting medicines: no source list | VERIFIED / NOT-FOUND |
| Plausible range | the point estimate plus and minus the larger absolute 95 % limit of agreement (L/min), then divided by body mass. Age 65-75: women 0.43, men 0.70 (S3: -0.43..+0.39 women, -0.60..+0.70 men). Other ages: S1 cross-validation, women 0.61, men 0.76. The sign convention of the limits (estimate minus measured) is not confirmed, so the larger absolute value is used on both sides. Age 76-84: S1 values with a note that S3 stops at 75. | VERIFIED values; symmetric rule APP CHOICE |

The range is the **95 % limits of agreement of a laboratory validation** on a braked ergometer; it is **not** the error of this e-bike use, which is unknown and probably larger. Worked example (fixture): 2.102082 L/min, limit 0.70 -> 1.402 to 2.802 L/min -> 23.0 to 45.9 ml/kg/min at 61 kg.

### 2.6 Second estimate: dropped

The design doc's second estimate (individual HR-power line extrapolated to age-predicted HRmax) is **dropped**: it needs HRmax (an age-predicted value with an error of 10 bpm or more), and the Uth heart-rate ratio method is excluded (poor agreement in older adults, not valid for cycling; see design doc section 5). The EB equation needs no HRmax. `secondEstimate` is removed from the config.

### 2.7 Repeat rule

Each row maps to one value of the contract enum `repeat_advice` (`repeat_another_day` or `next_test_in_about_3_months`). Aborted records carry it too.

| Situation | Advice (screen text) | Enum | Status |
|---|---|---|---|
| RPE 17 or more in stage 2 (`rpe_stop`) | retest on **another day** (default). Same-day retest after 20 min rest at a lower load is possible per S2, but not offered by default | `repeat_another_day` | VERIFIED rule; default APP CHOICE; physician item 6 |
| No answer to the RPE prompt and its repeat (`rpe_no_answer`) | retest on **another day**; the screen suggests that the rider talks to a doctor if the effort felt wrong | `repeat_another_day` | APP CHOICE (decision (h)) |
| Stopped by the HR ceiling (`hr_ceiling`) | retest another day. The text says the limit was reached and shows it; it does not suggest raising the limit. A changed limit is possible only through the physician sign-off (2.8, 8.4) | `repeat_another_day` | APP CHOICE (decision (i)) |
| Aborted for any other reason (`user_stop`, `hr_dropout`, `link_lost`, `interrupted`, `app_killed`, `bluetooth_off`, `invalid_stage`, `rest_not_ready`) | retest another day | `repeat_another_day` | APP CHOICE |
| Complete, low quality (score under `qualityThreshold`, 2.9) | repeat on another day (`retestMinHours = 24`, PLACEHOLDER (to review)) under the same conditions. This is the one value of the minimum gap; the design doc's 48 h is withdrawn | `repeat_another_day` | APP CHOICE |
| Complete, good | next test in about 3 months for the trend (config `retestWeeks = 12`, PLACEHOLDER (to review)) | `next_test_in_about_3_months` | APP CHOICE |

`not_eligible` attempts stay on the phone and carry no advice enum.

### 2.8 Safety extras (not from the EB sources)

| Parameter | Default | Status |
|---|---|---|
| RPE stop | RPE 17 or more in stage 2, acts at once | VERIFIED (the only EB stop rule) |
| `hrCeilingPctOfHrMax` | **85 % of predicted HRmax, ON by default as a hard stop.** This is the app's own extra safeguard (design doc), not part of the EB test. At age 67, HRmax 161 and ceiling about 137 bpm. **Locked:** the value is editable only through the physician sign-off flow (8.4); until a sign-off is recorded the default applies. **Absolute age cap (decision (k)):** no sign-off, percent or typed HRmax may give a ceiling above 90 % of the age-predicted maximum, `absoluteCeilingBpm(age) = round(0.90 x round(208 - 0.7 x age))`, 145 bpm at age 67. The cap is also applied at run time. | APP CHOICE (decisions (i), (k)), review R |
| `hrMaxMethod` | `tanaka`: `208 - 0.7 * age` (S7, healthy adults, abstract only), about 161 at age 67; or `typed`. **Locked** like the ceiling (8.4). **HUNT coefficients are not verified and are not offered.** | S7 FROM-ABSTRACT; flagged for physician review |
| Where the ceiling applies | Stage 1 and stage 2: hard stop. Seated rest: **warning only** ("Heart rate is high at rest. Stay seated."), no abort. Cool-down and later: not evaluated. | APP CHOICE (finding 40) |
| **Known conflict** | The default ceiling of about 137 bpm (age 67) is *below the upper end* of the protocol's own stage-2 target of 110-140 bpm for age 50 and over (S2). The ceiling can therefore stop a correctly run test in stage 2. This is **accepted as the safer error** until the physician decides (checklist 8.3 item 2). It is not hidden: the stop message and the Result screen say it can happen in a correctly run test (3.6) and show the limit that was reached. | owner decision (i) |
| Hard-stop timing values | `hrDropoutSeconds`, `hrCeilingSeconds`, `linkLostSeconds`, `powerStaleSeconds` and the rest-phase values: numbers in 8.1 | PLACEHOLDER (to review) |

### 2.9 Quality score and threshold

The score is the sum of pass/fail criteria (no part credit). **All weights and the threshold are PLACEHOLDER (to review).**

| Criterion | Points | Passes when |
|---|---|---|
| Stage 1 power in band | 20 | `in_band_pct >= 80` |
| Stage 2 power in band | 20 | `in_band_pct >= 80` |
| Stage 1 HR steady | 10 | HR change under 5 bpm between minute 3 and 4 |
| Stage 2 HR steady | 10 | same |
| Stage 1 HR coverage | 10 | `hr_coverage_pct >= 90` |
| Stage 2 HR coverage | 10 | same |
| Effort rating given | 10 at the first prompt, 5 at the repeat prompt (flag `rpe_second_prompt`) | an RPE band was tapped |
| Rest ready | 5 | no `rest_hr_high` |
| Pre-test conditions | 5 | `pretest_conditions_ok` |

Total 100. `qualityThreshold = 70`: below it the Result shows `LOW QUALITY` and the repeat advice. A failed criterion adds its flag (`stage1_power_off_band`, ...). `short_rest`, `ceiling_changed` and `cooldown_cut_short` are flags only and do not change the score. Flag names are the closed enum in the contract. The fixture meets every criterion: 100.

## 3. User journey and screens

Entry: the **Test tab**, the third of four tabs (decision (c)). Navigation order **Status, Ride, Test, Settings**: bottom bar in portrait, navigation rail in landscape. The existing fault banner (failing SD card, lost bridge) stays visible on the Test tab and on every sub-screen, **including the Run screen, where it takes its own reserved line so the status line is never covered (3.3)**. History of tests is a section of the Test tab (below the Start entry), not a fifth tab.

### 3.1 Procedure screen (Test tab, top)

Portrait, ruled bands, top to bottom:

| Band | Content |
|---|---|
| Title | `VO2MAX TEST` and a badge `ESTIMATE, NOT A MEASUREMENT` |
| Time | "About NN minutes in total", computed by `buildSchedule` from `ProtocolConfig`: the shortest total (26 min) plus about 3 min of checks, and "up to 6 minutes more if your heart rate needs longer to settle or the load is raised"; then a ruled list of the phases with minutes: Seated rest, Stage 1, Stage 2, Cool-down. The RPE prompt, its one repeat and the 15 s waits happen inside stage 2 and add no time; an unanswered prompt ends the test early |
| How it goes | Plain explanation (the paragraph at the end of this document) |
| You need | Checklist rows that turn OK or fault live: Sex set in Settings, Bridge linked, Bike linked, Chest strap on and sending, Beep check done, **A place chosen: a stationary trainer or a closed quiet flat path (best), or a flat quiet road where you can stop safely at any moment.** Same words as on Status. |
| Stopping | "Stopping is always fine. Nothing is lost but this attempt. There is no pause: if you stop, you start again another day." |
| Not medical | "This is a coaching estimate, not a medical device, and not the laboratory test it is modelled on. It does not diagnose anything. Stop if you feel unwell." |
| Action | `START CHECKS` (solid, square, 48 dp or larger); disabled with the reason shown while a "you need" row fails |

Landscape: left half the description, right half the phase list and the action.

### 3.2 Readiness check

Stop at any step: `Cancel` returns to Idle with no record (the teardown effect releases audio, 4.1).

1. **Health questions** (PAR-Q+ style). Wording is a **physician-reviewed, versioned data file** (`ReadinessQuestions`, stored with the result as `questions_version`). The contraindication list is NOT-FOUND in the literature beyond "healthy" (S3 excluded severe joint problems, very high blood pressure and cardiovascular disease). Do not ship placeholder text as final.
   - Any "Yes" stops the test: "Do not start. Speak to your doctor first." Continue only after the explicit "My doctor has cleared me for this test" (stored `clearance_confirmed=true` with timestamp).
   - No default answers; each is a large Yes/No.
2. **Medication.** "Do you take a beta-blocker, or any medicine that slows or changes your heart rate?" (list in the physician-written medication data file). Yes or "Not sure" raises the event `Ineligible`: the machine goes to `Aborted(not_eligible)` (4.1), the screen says "Heart-rate tests are not valid with this medicine. No estimate will be made." and the estimate function is never called. The same event is raised for age or sex outside 2.5.
3. **Pre-test conditions** as questions (heavy meal, caffeine/nicotine, vigorous activity: S2/S3 list); a "No" shows the rule and advises another day; the rider may continue and the result carries `pretest_conditions_ok=false`.
4. **Setup**: sex (from Settings), **age and weight shown for confirmation** ("Age 67, 61.0 kg. Correct?"; a wrong value links to Settings; the ceiling and the equation depend on age and the relative result on weight), training status (inactive/low/moderate/high), assist level (the bike does not report it; list Off, Eco, Tour, eMTB/Sport, Turbo, not sure), proposed stage-2 watts from 2.3 shown as "Stage 2 will start at NN W" with the placeholder warning until the owner has confirmed the table column. Two live information rows: **Seated rest** ("15 minutes", or "Shorter rest: the result will be flagged" when `seatedRestSeconds` < 900) and **Heart-rate limit** ("NNN bpm, standard" or "NNN bpm, changed with a sign-off by NAME on DATE"), from `safetyLimits` (8.4). The limits in force are snapshotted at BEGIN and cannot change during the run.
5. **Link checks** (live rows, each OK / fault with next step): Bluetooth on (`MainActivity.withBluetooth`, `Board.bluetoothNote`); bridge connected and `live` arriving; bike connected and rider power present; chest strap connected, contact detected, bpm plausible for N s (5.3); phone/bridge clock valid; phone battery above a floor (owner choice). A strap not marked **mine** is allowed but the row says "Development strap: the result will stay on this phone" (5.1).
6. **Beep check** (decision (b)): the phone plays a sample tone and speaks "Can you hear this?"; the rider taps Yes. A muted phone or a failed TTS shows `Speech: off, tones only` (see 3.3 audio). Not heard = cannot start.
7. Rider profile present (age, sex, weight); if missing, a row links to Settings.

Everything must be OK to enable `BEGIN`.

### 3.3 Run screen

The only screen during a test. Keep screen on. Never scrolls.

**Layout, portrait** (top to bottom, ruled bands):

| Band | Content |
|---|---|
| Fault line | Reserved: the existing fault banner and test faults (strap lost, link lost) take this line; the status line below it is never covered. A tap on the fault line **acknowledges** a fault: it silences the repeating alarm tone for that fault only; the abort timer keeps running and the line stays until the condition clears. |
| **STATUS LINE** (always present) | Step name (`STAGE 1 OF 2`), the instruction (`HOLD 30 W`, `SIT STILL`, `RATE HOW HARD`, `STOP`), step elapsed / remaining as `m:ss`, **total** elapsed / remaining as `m:ss`. Large (step 18 sp, instruction 28 sp bold, tabular numerals). TalkBack: polite live region on changes of step or instruction only. |
| Progress rule | Segmented rule, one segment per phase (the rest segment and the stage-2 segment grow with a visible tick when the rest is extended or the load is raised), thin ticks for the HR reading times |
| Power | Big numeral for live rider power, the target band strip, words `IN BAND`, `A LITTLE MORE`, `A LITTLE LESS`; cadence line `60 rpm` with `FASTER`/`SLOWER` |
| Heart rate | Big numeral bpm, strap status word (`OK`, `WEAK`, `LOST`), small line `limit NNN` |
| STOP | Full-width solid button, 72 dp or higher, bottom of screen, with the line "Stopping is always fine. Nothing is lost but this attempt." under or inside it |

During the RPE prompt and its readback (4.4) the Power band collapses to one line (power numeral and `IN BAND` words) and the RPE picker takes the Power band's space; **the Heart-rate band stays as a compact row (bpm, strap word `OK/WEAK/LOST`, limit) beside or above the picker, so a lost strap is never hidden**. The fault line, status line and STOP stay.

Alert red only for a real fault or abort. Out of band is amber/ink emphasis plus words.

**Layout, landscape**: left the status line, middle power and band strip, right HR; STOP a full-height strip on the right edge; the RPE picker takes the middle column and the HR row stays in the right column.

**Instruction wording per step**

| Step | Instruction | Band |
|---|---|---|
| Seated rest | `SIT STILL. DO NOT PEDAL.` with live HR | none |
| Seated rest, extended | `HEART RATE STILL SETTLING. KEEP SITTING.` | none |
| Stage 1 | `HOLD 30 W AT 60 RPM` | target +/- `powerBandPct` |
| Stage 1, last 10 s | `STAGE 2 NEXT: HOLD NN W` | none change |
| Stage 2 | `HOLD NN W AT 60 RPM`; minute 2: `HOW HARD? 6 TO 20`; on the repeat prompt: `ASKING AGAIN: HOW HARD? 6 TO 20` | target band |
| Cool-down | `PEDAL EASY, KEEP MOVING` | loose |
| Finishing | `TEST DONE. WORKING OUT YOUR RESULT` | none |

There is no "between stages" pause: stage 2 starts the instant stage 1 ends.

**Beep and alarm rules** (tones are the primary cue; speech follows, English). **Hard-stop cues (fault alarm, stop pattern) pre-empt any prompt or readback speech; out-of-band speech is suppressed while the RPE picker or readback is open.**

| Event | Tone | Speech |
|---|---|---|
| Test start | long tone, then 3 short | "Test started. Sit still." |
| Stage change (including the RPE-triggered load change) | 2 rising tones | "Stage one. Hold thirty watts." / "Stage two. Hold NN watts." / "Raise to NN watts." |
| 30 s before end of a phase | 1 short tone | "Thirty seconds." |
| Last 5 s of a phase | 1 short tone per second | optional countdown |
| HR reading times (3:15, 3:30, 3:45, 4:00) | none (silent; shown as ticks) | none |
| RPE prompt (first, at 60 s into stage 2) | 3 short tones | "How hard is it, six to twenty? Tap a button." |
| RPE repeat prompt (`rpeFirstWaitSeconds` = 15 s after the first, only if no answer) | 3 short tones, then 1 more (distinct from the first prompt) | "I am asking again. How hard is it, six to twenty? Tap a button. If you do not answer, the test will stop." |
| RPE accepted (not a stop) | 1 tone | readback, 4.4 |
| RPE 17 or more | stop pattern | "Rating seventeen or more. Stopping." then the stop guidance below |
| RPE not answered (`rpeRepeatWaitSeconds` = 15 s after the repeat) | stop pattern (long low tone, twice) | "No answer. The test has stopped." then the stop guidance |
| Out of band for 10 s (not during the picker) | 1 soft tone, at most once per 15 s | "A little more." / "A little less." |
| Test finished | 3 rising tones | "Test done. Pedal easily, or stop safely." |
| Stop / abort | long low tone, twice | "Test stopped. [reason]. Stopping is always fine. Nothing is lost but this attempt. Pedal easily, or stop safely. If you feel unwell, get help." |
| Fault | distinct alarm pattern every 5 s until acknowledged, the condition clears or the abort rule fires | "Heart rate lost." |

**Audio approach.**
- Tones: `SoundPool` or pre-generated `AudioTrack` buffers (no dependency on TTS); `ToneGenerator` only as fallback. Speech: `TextToSpeech`, **English** (`Locale.ENGLISH`), initialised before START. If TTS is unavailable or English data is missing, the run continues with tones only, and the readiness shows `Speech: off, tones only`; every cue that matters (RPE prompt, load change, stop) must also be understandable from tone patterns plus the screen. A spoken-only cue is never the only cue.
- Audio focus: `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` per cue, abandoned after. Losing focus to navigation or music is **not** an interruption (3.6).
- Headphones: the system route; a headset disconnecting is not an abort.
- Volume: media volume; the beep check in 3.2 catches a muted phone.
- Screen: `FLAG_KEEP_SCREEN_ON`; the run is owned by the existing **foreground service** (verify on Android 17 whether a second foreground type is needed); partial wake lock for the test only. Timers use `SystemClock.elapsedRealtime()`.
- **Teardown (every transition into Aborted and Done, and Readiness cancel):** one effect `Teardown` that releases audio focus, the wake lock and `FLAG_KEEP_SCREEN_ON`, cancels the repeating fault alarm and all pending cue timers, flushes and closes the samples file, stops the test part of the foreground service (the bridge link and ride upload keep running), and shuts down `TextToSpeech` and `SoundPool` **after** the final spoken line has finished (or after 10 s at most).

**STOP controls**: on-screen STOP; volume-down held 2 s; headset media button via `MediaSession` (optional, verify). STOP asks nothing; it is accepted in every active state (3.6).

### 3.4 Result screen

| Band | Content |
|---|---|
| Header | `RESULT` and the badge `EKBLOM-BAK STYLE ESTIMATE, NOT A MEASUREMENT`; for a development result also `DEVELOPMENT: KEPT ON THIS PHONE ONLY` |
| Estimate | Big numeral `NN` + `ml/kg/min`, beside it the plausible range `NN to NN` (always shown with the number); L/min value in small type |
| Quality | `QUALITY NN of 100` plus reasons as short rows (power in band stage 1/2, steady HR, strap coverage, RPE answered, rest HR); `LOW QUALITY` under 70 |
| Meaning | "This is an estimate in the style of the Ekblom-Bak test, from your heart rate at two power levels. The test was validated in a laboratory on a braked exercise bike, not on an e-bike on the road; the range shown is the laboratory error and your real error is probably larger. For a man of your age the lab error is about plus or minus 0.6 to 0.7 litres a minute. It is best used to watch the change over months on the same bike and route. It is not a medical result." |
| Repeat | Advice from 2.7 |
| Comparison | If a previous valid (not development) test exists: the change, with an overlap statement ("not clearly different") |
| Sync | `Saved on this phone.` and `Sent to Home Assistant.` / `Waiting to send.` (sent only after the HA ack) / `Rejected by Home Assistant: reason` / for a development result only "Not sent. Development result." |
| Actions | `DONE`, `SEE HISTORY` |

Aborted: `NO ESTIMATE` and the reason; no number, range or quality. After an RPE stop: "You rated it 17 or more. The test stops here. Rest, and try again on another day." After an unanswered effort question (`rpe_no_answer`): "The test stopped because you did not answer the effort question. No result. If the effort felt wrong, talk to a doctor." After a heart-rate ceiling stop in stage 1 or rest-phase fault: "Your heart rate reached the limit of NNN bpm. The test stopped. No result." In **stage 2**: "Heart-rate limit reached in stage 2 (NNN bpm). This can happen in a correctly run test: the limit is below the top of the usual target. It is not a fault of the bike or the strap. No result." and, if the limit was changed by a sign-off, "This limit was changed by a sign-off." For `invalid_stage`: "Not enough usable heart-rate or power data in a stage. No result." For `rest_not_ready`: "Your heart rate or the bike was not ready. No result." Never a partial estimate. An aborted record also shows the line "Stopping is always fine. Nothing is lost but this attempt."

A complete result produced with a changed heart-rate ceiling or HRmax method shows the row `HEART-RATE LIMIT CHANGED (sign-off by NAME, DATE): NNN bpm instead of NNN bpm` and the same in the stored record (8.4). A complete result with a rest shorter than 15 minutes shows `SHORT REST`. A complete result whose cool-down was cut short shows "Cool-down cut short. The estimate is not affected."

### 3.5 History (in the Test tab)

One ruled row per test: date, `NN` (or `ABORTED`, or `DEVELOPMENT`), range, quality, sync state (`Sent`, `Waiting`, `Rejected`, `Stuck`, `Not sent (development)`), and badges `SHORT REST`, `LIMIT CHANGED`, `LOW QUALITY`. There is no `INVALID` status: an unusable stage is an `ABORTED` row with the reason "not enough usable data". Tap opens the stored result read-only with a Details disclosure (inputs, stage targets and means, RPE band, assist, protocol version). Trend plot: one series per `protocol_version`, range as a band; aborted and development results are not plotted. Delete is local only (the text says HA keeps its copy; a development result has no HA copy).

### 3.6 Error and abort flows

**Definitions.** The machine is *active* in every state except `Idle`, `Done` and `Aborted`. STOP is accepted in every active state. Behaviour of STOP and abort conditions by state:

| State | STOP / abort condition | Result |
|---|---|---|
| Readiness | Cancel / STOP | back to Idle, no record; `Ineligible` -> `Aborted(not_eligible)` |
| Seated rest, rest wait, rest prompt | STOP or a rest-phase abort condition | `Aborted(...)` |
| Stage 1, Stage 2 (all sub-states) | STOP or any abort condition | `Aborted(...)` |
| Cool-down, Computing | STOP or any abort condition (dropout, link, call, Bluetooth off, ceiling not evaluated) | **not an abort**: the data are complete. The test ends as `Done` with the estimate kept and the flag `cooldown_cut_short`; Computing cannot be interrupted (it is immediate) |

All aborts: stop cues, the spoken guidance below, the `Teardown` effect (3.3), save `status=aborted` + `abort_reason`, no estimate; samples file kept. **Every stop and abort says aloud and on screen: "Pedal easily, or stop safely. If you feel unwell, get help."** (this matters most for `rpe_no_answer` and `hr_ceiling`, where the cause may be medical), followed by "Stopping is always fine. Nothing is lost but this attempt."

| Condition | Detection | Applies in | Behaviour | `abort_reason` |
|---|---|---|---|---|
| User STOP | button, volume hold, media button | every active state | immediate | `user_stop` |
| RPE 17 or more | the minute-2 prompt (or a correction) | stage 2 | immediate stop with message; retest another day | `rpe_stop` |
| HR ceiling | smoothed bpm above `hrCeiling` for `hrCeilingSeconds` (8.1) | stage 1 and 2 (hard stop); rest: warning only | immediate abort; shows the limit reached; stage-2 wording in 3.4 | `hr_ceiling` |
| RPE not answered | no answer to the first prompt (60 s into stage 2) and none to the one repeat (15 s later), 15 s after the repeat | stage 2 | stop cues, message; retest another day | `rpe_no_answer` |
| HR dropout | no valid bpm for more than `hrDropoutSeconds`; warn at 3 s | stage 1 and 2: 10 s. **Seated rest: warn at 60 s, abort at 180 s** (8.1) | alarm, then abort | `hr_dropout` |
| Bluetooth off | `BridgeLink.Ui.bluetoothOn` false | rest, stage 1, stage 2 | abort at once | `bluetooth_off` |
| Bridge/bike link lost | `BridgeLink` not connected, rider power older than `powerStaleSeconds`, or bike `connected=false`, **continuously** for `linkLostSeconds` | stage 1 and 2 only. **Not evaluated in seated rest** (the bike may sleep); it is checked as a readiness condition before stage 1 (2.1) | warn at 3 s, abort at `linkLostSeconds` | `link_lost` |
| Phone call | `AudioManager` mode `MODE_RINGTONE`, `MODE_IN_CALL` or `MODE_IN_COMMUNICATION` for more than `interruptSeconds` (PLACEHOLDER (to review) 3 s), through `AudioManager.OnModeChangedListener` (API 31): **no new permission** (no `READ_PHONE_STATE`). Losing audio focus alone (navigation, music) is ignored; the cue player simply repeats a missed critical cue. Whether the listener reports a ringing call on Android 17: verify on the device (4.5) | rest, stage 1, stage 2 (no pause: timing is physiological) | abort | `interrupted` |
| Process killed | leftover active file on next start (4.2) | any | closed as aborted; the screen says "The test ended unexpectedly. Pedal easy, then rest." | `app_killed` |
| Rest not ready | rest extension used up, and "Continue anyway, or stop?" not answered within `restPromptTimeoutSeconds` | rest | abort | `rest_not_ready` |
| Unusable data | `estimate` returns `Invalid` (2.2 guards, fewer than 3 valid readings) | Computing | abort, no estimate | `invalid_stage` |
| Symptom (chest pain, dizziness, unusual breathlessness) | only STOP | any | as user stop plus "If symptoms continue, get medical help." | `user_stop` |
| Not valid by input | beta-blocker, failed eligibility, age or sex out of range | Readiness | no estimate; local record only | `not_eligible` |
| Cadence far off 60 rpm | band exceeded for long | any stage | not an abort; lowers quality | n/a |
| Result cannot be published | no broker | after the test | outbox, not an error | n/a |
| Low quality but complete | score under threshold | after the test | shown as `LOW QUALITY` + repeat advice | n/a |

**Precedence when several conditions fire in the same tick** (one transition only; PLACEHOLDER (to review)): `user_stop` > `rpe_stop` > `hr_ceiling` > `rpe_no_answer` > `hr_dropout` > `bluetooth_off` > `link_lost` > `interrupted`. An RPE answer with a timestamp at or before the 90 s deadline wins over the deadline, including an answer at exactly 90.0 s.

Aborted attempts are published to HA with the facts the contract lists (status, reason, timing, rider, setup, safety, finished stages, `rpe_at_stop`, `repeat_advice`; never an estimate, quality score or HR series). This refines decision (e) ("status only") and is for the owner to confirm. `not_eligible` attempts and development results are never published.

## 4. State machine (pure Kotlin, unit-testable)

### 4.1 States and transitions

`Vo2Machine` is a reducer `(state, event) -> (state, effects)`; no Android types, no clock reads (time arrives as a monotonic millisecond event). Events with the same timestamp are processed in this order: user events (STOP, answers, taps) first, then conditions, then deadline ticks.

States: `Idle`, `Readiness`, `SeatedRest` (sub-states `Resting`, `RestWait`, `RestPrompt`), `Stage1`, `Stage2` (sub-states `Plain`, `RpeWindow(ask = 1 or 2, askedAtMs)`, `Readback(band, appliedAction, expiresMs, corrected)`, `Correcting(startedMs)`; plus an `extended` flag), `Cooldown`, `Computing`, `Done(result)`, `Aborted(reason)`. There is no Warmup and no Stage2Pending: stage-2 watts are known before the test.

| From | Event | To | Effects |
|---|---|---|---|
| Idle | `StartChecks` | Readiness | initialise TTS and SoundPool |
| Readiness | `Cancel` / `Stop` | Idle | `Teardown`; no record |
| Readiness | `Ineligible` | Aborted(not_eligible) | `Teardown`; local record; spoken/on-screen guidance |
| Readiness | all OK + `Begin` | SeatedRest.Resting | create `test_id`, open sample file, snapshot safety limits, cue start |
| SeatedRest.Resting | `Tick`, rest elapsed, readiness met (2.1) | Stage1 | cue change; store resting HR (only now) |
| SeatedRest.Resting | `Tick`, rest elapsed, not ready | SeatedRest.RestWait | instruction `HEART RATE STILL SETTLING` |
| SeatedRest.RestWait | readiness met | Stage1 | cue change; store resting HR; `restExtra` = time waited |
| SeatedRest.RestWait | `Tick`, `restExtraMaxSeconds` elapsed | SeatedRest.RestPrompt | prompt "Continue anyway, or stop?" (spoken and on screen; `CONTINUE` is enabled only while the bike gives power) |
| SeatedRest.RestPrompt | `RestContinue` | Stage1 | cue change; flag `rest_hr_high`; resting HR **not** stored |
| SeatedRest.RestPrompt | `Stop`, or no tap for `restPromptTimeoutSeconds` | Aborted(user_stop) / Aborted(rest_not_ready) | as for every abort |
| Stage1 | elapsed | Stage2.Plain | cue change at once (no rest), set band to stage-2 watts |
| Stage2.Plain | `t = rpeAskAtSeconds` (60) | RpeWindow(ask 1) | `PromptRpe(1)`: first prompt cue (tones, speech, picker) |
| RpeWindow(ask 1 or 2) | `RpeAnswered` band 17-20 | Aborted(rpe_stop) | **at once**, no readback, no correction; `rpe_at_stop = 17-20` |
| RpeWindow(ask 1 or 2) | `RpeAnswered` band 6-9, 10-11 or 12-16 | Readback | **apply at once** (2.4: raise and extend / keep); record `rpe_prompts` = ask number; readback speech |
| RpeWindow(ask 1) | `Tick` with `t >= rpeAskAt + rpeFirstWaitSeconds` (75 s), no answer | RpeWindow(ask 2) | `PromptRpe(2)`: repeat prompt cue, same picker, text `ASKING AGAIN` |
| RpeWindow(ask 2) | `Tick` with `t >= rpeAskAt + rpeFirstWaitSeconds + rpeRepeatWaitSeconds` (90 s), no answer | Aborted(rpe_no_answer) | as for every abort |
| Readback (corrected = false) | `CorrectTapped` within `rpeCorrectSeconds` | Correcting | undo the applied action (original load and length restored); picker reopens with its own `rpeCorrectSeconds` timer |
| Correcting | `RpeAnswered` band 17-20 | Aborted(rpe_stop) | at once |
| Correcting | `RpeAnswered` band 6-9, 10-11 or 12-16 | Readback(corrected = true) | apply the new action at once; no further `CORRECT` |
| Correcting | `Tick`, `rpeCorrectSeconds` elapsed, no new tap | Stage2.Plain | the **first answer stands** (its action is re-applied) |
| Readback | `Tick`, `rpeCorrectSeconds` elapsed (5 s) | Stage2.Plain | done |
| Stage2 | elapsed (`240` or `300`) | Cooldown | cue change |
| Cooldown | elapsed | Computing | |
| Cooldown | `Stop` or any abort condition | Computing | flag `cooldown_cut_short`; estimate kept |
| Computing | `Estimated` | Done | `Teardown`; persist, enqueue publish (not for a development result) |
| Computing | `Invalid` (2.2 guards, fewer than 3 valid readings) | Aborted(invalid_stage) | as for every abort |
| every active state except the rows above | `Stop` | Aborted(user_stop) | as for every abort |
| every active state | abort condition (3.6, with precedence) | Aborted(reason) | as for every abort |
| any | `Tick(now)` | same | each missed cue once, ordered |

"As for every abort": stop cues, spoken guidance (3.6), `Teardown`, persist `status=aborted`, `finished_epoch` = the event time, `repeat_advice` (2.7), enqueue publish unless `not_eligible` or development.

The schedule is `buildSchedule(config, adjustments)`: a list of `PhaseSpec(name, durationSec, targetBand?)` that is **rebuilt** when the load is raised (the stage-2 phase is extended by `stage2ExtraSeconds` when `extendOnRaise`, its target watts change, capped by `stage2RaiseCapWatts`), when the rest is extended (the rest phase grows by the time waited, at most `restExtraMaxSeconds`), and when a correction undoes a raise. The schedule also carries the RPE cue times inside the stage-2 phase: `rpePrompt1 = rpeAskAt` (60 s), `rpePrompt2 = rpeAskAt + rpeFirstWait` (75 s), `rpeDeadline = rpePrompt2 + rpeRepeatWait` (90 s), so `cues()` fires each once and a late tick fires the repeat prompt and then the stop in order. The waits live inside stage 2; they are config values (2.1), and `buildSchedule` rejects a config that does not fit minute 2 (the RPE window rule). Total duration and remaining time are computed from the current schedule, never a constant:

`total = seatedRest + restExtra + stage1 + stage2 + (raised and extendOnRaise ? stage2Extra : 0) + cooldown`; `remaining = total - elapsed`. Before the rest ends the screen shows the shortest total (26:00) with "may be up to 6 minutes longer"; before the RPE answer it shows "may be 1 min longer"; an unanswered prompt ends the test at the deadline (`rpe_no_answer`) and the remaining time then reads `0:00` with the stop message. Extensions are announced when they happen and the progress rule updates.

### 4.2 Persisted if the app is killed

`vo2/active.json` (atomic, at every phase change, at the RPE answer and every 30 s as a heartbeat with `last_epoch`): `test_id`, protocol, inputs used, start epoch, phase, current stage-2 watts, RPE (ask number and prompt times) and extension, the rest extension, and the **snapshot of the safety limits in force** (ceiling percent, HRmax method, sign-off id or none), and the `dev` flag. `vo2/samples_<test_id>.csv` (1 Hz, flushed every 5 s). **No resume.** A leftover active file on the next start becomes `Aborted(app_killed)`, with `finished_epoch` = the time of the **last stored sample** (or `last_epoch` if later; `started_epoch` if there are none). The app **immediately** shows (and speaks, if TTS is ready) "The test ended unexpectedly. Pedal easy, then rest." Sample files are kept **30 days** (decision (e)). Completed results go to the results file and the outbox before the Result screen says "saved".

### 4.3 Pure functions (unit tests, no phone)

| Function | Does | Test with |
|---|---|---|
| `buildSchedule(config, adjustments)` | phases, cue times, total incl. rest extension and raise; caps `restExtraMaxSeconds`; rejects a config that breaks the RPE window rule | exact tables: 1560 s plain, 1620 raised, 1860 with 300 s rest extension, 1920 both; with and without `extendOnRaise` |
| `clock/remaining(state, now)` | elapsed and remaining per phase and total | boundaries, late ticks, extension mid-stage |
| `cues(prev, now, schedule)` | cues crossed once | gaps 0.5 s, 3 s, 40 s |
| `powerBand`, `bandStatus`, `cadenceStatus` | in/low/high, 10 s hysteresis, grace after a load change | edges, stale power |
| `restReady(samples, profile, link)` | 2.1 readiness | spread 4/5/6 bpm, margin 14/15/16, bike stale |
| `stage2Initial(table, sex, status)` | the guide watts | all 8 cells, column default |
| `rpeBandOf(n)` | `6-9`, `10-11`, `12-16`, `17-20` | 6, 9, 10, 11, 12, 16, 17, 20 |
| `rpeDecision(band, currentW, config)` | Raise(watts capped, extend) / Keep / Stop | each band; cap 150 W at 65+; start load above the cap is not lowered |
| `readbackText(band, newW)` | the four readbacks (4.4) | exact strings |
| `rpeWindow(config, tStage2Ms, answeredAtMs?)` | `Waiting1` / `Waiting2` / `Expired` (`rpe_no_answer`) | edges 59.9, 60, 74.9, 75, 89.9, 90 s; answer at 14.9 s and 29.9 s after the first prompt; **an answer at exactly 90.0 s counts**; late tick of 40 s; a correction never moves the deadline; a config that does not fit minute 2 is rejected |
| `hrAtReadings(samples, stageStart, offsets)` | mean HR of the four readings, `end_hr` | missing reading, gaps, extended stage |
| `dpoHr(config, stage1, stage2)` | dHR, dPO, ratio rounded to 2 decimals | 35/58 = 0.603448 -> 0.60; 0.546875 -> 0.55 |
| `estimate(config, inputs)` | guards, then the equation, L/min and ml/kg/min (unrounded); `Invalid` or null if ineligible | the worked example (golden: ln V 0.742928, 2.1021 L/min, 34.5 ml/kg/min); dPO 19/20 W; dHR 0 and negative; stage-1 mean 14/15/16 W off 30; property tests: finite and positive, null for out-of-range age |
| `plausibleRange(config, age, sex, value)` | 2.5 rule | age groups, women/men; fixture 23.0 to 45.9 |
| `validityFlag(sex, mlKgMin)` | outside_validated_range | edges 19/62, 24/76 |
| `tanakaHrMax(age)`, `absoluteCeilingBpm(age)`, `ceilingFloorBpm(age)`, `hrCeilingBpm(limits, age)`, `ceilingExceeded(samples, limits, age, seconds)`, `smoothedHr`, `hrDropout`, `steadyState` | `tanakaHrMax = 208 - 0.7 x age`; `absoluteCeilingBpm = round(0.90 x round(tanakaHrMax))`; `ceilingFloorBpm = round(0.70 x round(tanakaHrMax))`; `hrCeilingBpm = min(round(pct x round(HRmax used)), absoluteCeilingBpm(age))`; smoothing = median of the last 5 valid 1 Hz readings | ages 20, 50, 67, 84: cap 175, 156, 145, 134; default 85 %: 165, 147, 137, 127; floor 70 %: 136, 121, 113, 104; exactly at the ceiling is not exceeded; spikes under `hrCeilingSeconds` |
| `safetyLimits(config, signoff?, protocolVersion, age)` | `SafetyLimits(ceilingPct, hrMaxMethod, hrMaxTyped?, state = Locked / Unlocked, changedFromDefault, reason)`; Locked with the defaults when there is no sign-off, when the sign-off is for another `protocolVersion`, when it was reverted, or when it no longer passes `validateSignoff` for the **current age** | no sign-off; valid sign-off; stale protocol version; revoked; sign-off equal to the defaults (Unlocked, `changedFromDefault=false`); age changed so the stored ceiling exceeds the cap |
| `signoffBounds(age)` | the bounds of the form, all derived from age: effective bpm between `ceilingFloorBpm(age)` and `absoluteCeilingBpm(age)`; typed HRmax within `tanakaHrMax(age)` +/- 20 bpm (PLACEHOLDER (to review)) | ages 20, 50, 67, 84 |
| `validateSignoff(form, age)` | list of field errors (empty = valid) | each field empty, too short, future date, date before 2020, effective ceiling below floor or **above the cap** (percent route and typed-HRmax route), 100 % or 120 % rejected, 90 % of Tanaka accepted at the cap, method not in the list, typed HRmax out of bounds or missing for `typed` |
| `qualityScore`, `qualityFlags`, `repeatAdvice` | 2.9 table; 2.7 mapping | one test per criterion and per row; changed ceiling and short rest do not change the score |
| `abortPrecedence(conditions)` | 3.6 order | every pair |
| `Vo2Json.build/parse` | the wire JSON (stable key order, mac); never writes `dev` | golden string; closed enums |
| `HrMeasurement.parse(bytes)` | 5.2 | captured bytes |
| `readiness`, `Vo2Machine.reduce` | may begin; the table above | scripted runs including RPE paths, readback and correction paths, every abort path ends in `Teardown` and the guidance |

### 4.4 The RPE prompt and the load-adjustment flow

**When.** At `rpeAskAtSeconds = 60` into stage 2 (start of the second minute; S2: minute 2; S3: after minute 1). The rider may answer for `rpeFirstWaitSeconds` (**15 s**, config), is asked **once more** (same picker, spoken and on screen), and may answer for `rpeRepeatWaitSeconds` (**15 s**, config). Both waits end inside minute 2 (2.1 window rule). There is no 30 s window.

**Audio.** First prompt: 3 short tones, then speech: "How hard is it, six to twenty? Tap a button." Repeat prompt: the cue table in 3.3 (tones plus "I am asking again..."), the screen header changes to `ASKING AGAIN`. TTS unavailable: tones plus screen (the buttons are large and labelled). A hard-stop cue pre-empts this speech (3.3).

**One-handed input (coarse picker with big targets).** Four stacked full-width buttons, each at least 72 dp high with a gap, one per decision band of 2.4, so one tap decides the action without fine motor work. Labels are the **numbers** (so no two buttons share a word) with the Borg anchors as a second line (standard Borg wording, **not in the research report: confirm the wording**):

| Button | Meaning | Maps to |
|---|---|---|
| `6 TO 9` / "very, very light to very light" | RPE 6-9 | raise 1 kp, extend 1 min |
| `10 TO 11` / "fairly light" | RPE 10-11 | raise 0.5 kp, extend 1 min |
| `12 TO 16` / "somewhat hard to hard" | RPE 12-16 | keep |
| `17 TO 20` / "very hard to maximal" | RPE 17-20 | stop |

**A tap is final and acts at once.** A rating of 17 or more stops the test immediately (`Aborted(rpe_stop)`); it is not read back first and cannot be corrected. The band is stored (`rpe_band`, and `rpe_at_stop = 17-20` in the aborted record). There is no highlighted-but-unconfirmed state.

**Exact number (optional).** During the readback window a small `FINER` row with large `-` and `+` (starting empty `--`) lets the rider enter the exact number 6-20; it must lie inside the chosen band and it never changes the action. It is stored as `rpe`. **A band alone stores `rpe_band` only; no midpoint or other number is invented** (`rpe` is left out).

**Readback (spoken and on screen), after the action is applied, for the three non-stop bands only:**
- `6 TO 9`: "Rating six to nine. Raising to NN watts. One extra minute."
- `10 TO 11`: "Rating ten to eleven. Raising to NN watts. One extra minute."
- `12 TO 16`: "Rating twelve to sixteen. Keeping NN watts."
- `17 TO 20` (stop, not a readback window): "Rating seventeen or more. Stopping." followed by the stop guidance (3.6).

The readback window lasts `rpeCorrectSeconds` (5 s). In it a tap on `CORRECT` undoes the action and reopens the picker **once**, with its own 5 s timer; a new tap there is final (a 17-20 stops at once, the others are applied and read back, with no second `CORRECT`). If the picker times out without a tap, the first answer stands. A correction does **not** restart or extend the 15 s waits or move the 90 s deadline, and no deadline exists once the rider has answered. STOP stays reachable throughout.

**No answer: the test stops** (decision (h), 2026-10-21). Flow: prompt 1, wait 15 s, prompt 2, wait 15 s; still no answer = `Aborted(rpe_no_answer)`: stop cues ("No answer. The test has stopped."), the stop guidance, samples kept, record `status=aborted`, no estimate, no quality, no range. Reason: the rating is the only stop rule in the papers, so an unanswered prompt is treated as a possible reason to stop (2.4). The Result screen says plainly: "The test stopped because you did not answer the effort question. No result." and advises another day (2.7). An answer after the stop is ignored.

**Raise flow.** Raise applies at once: new target = min(current + step (2.4), `stage2RaiseCapWatts` rule) in watts, the band is re-drawn, out-of-band cues are suppressed for `loadChangeGraceSeconds` (PLACEHOLDER (to review) 10 s), stage 2 is extended by `stage2ExtraSeconds` (S3 variant) and the total and remaining time update. The RPE is asked **once** (not again after the raise; the one repeat prompt of the no-answer rule is not a second rating). The equation uses the load actually ridden in the mean-power window (the raised load when raised).

**Stop flow.** RPE 17 or more: cues for stop, abort with `rpe_stop`. No answer to both prompts: abort with `rpe_no_answer`. Both go through `Teardown` and the spoken guidance.

### 4.5 Only verifiable on the phone with the bike

Strap pairing and contact behaviour; two BLE connections held for 30+ min with the screen off; beep audibility with wind/helmet/earbuds; TTS availability; foreground service survival; **Bosch rider power smoothness and accuracy near 30 W**; whether the Bosch system stays awake and keeps sending power through 15 minutes of seated rest (the reason link loss is not evaluated in rest); the RPE picker usable with a glove or on rough road; the watts that correspond to the table (2.3) and 0.5 kp steps (2.4); volume-key and media-button STOP; `AudioManager` mode changes during a ringing call on Android 17; a supervised dry run.

## 5. Heart-rate link design (`HrLink`)

### 5.1 Scan, pair and "mine"
- Scan filtered by service UUID 0x180D (`ScanFilter.setServiceUuid`). `BLUETOOTH_SCAN` (`neverForLocation` as now) and `BLUETOOTH_CONNECT`; no new permission.
- **Strap selection is by scanning for the 0x180D service and letting the owner pick** from a list by name and signal word (Strong/Fair/Weak); stored: address and name. No model is hard-coded. Do not require bonding; reconnect by stored address. "If your strap does not appear, close other apps or watches using it, and (Garmin) set it to the open connection type."
- One strap at a time; Forget button.
- **Mark as mine (decision (l)).** Right after the rider picks a strap, the app asks "Is this your own strap?" with two large buttons: `YES, THIS IS MINE` and `NO, BORROWED (DEVELOPMENT ONLY)`. There is no default: a test cannot start until it is answered. The answer is stored with the strap's address (`mine = true/false`) and shown in Settings, Chest strap, with a `MARK AS MINE` / `MARK AS BORROWED` toggle.
- **Development results.** A test run with a strap that is not marked mine is stored with `setup.dev = true` on the phone only: it is shown in History as `DEVELOPMENT`, the Result screen says "Development result. Kept on this phone only.", it is **never published** (the outbox excludes it; a receiver would reject it with `dev_result`) and it is left out of trends and comparisons. Marking a strap as mine later never converts older development results; they stay development. Keep/delete: development results are kept on the phone for **30 days** with their sample files (PLACEHOLDER (to review), same as samples), can be deleted one by one from History, and `DELETE ALL DEVELOPMENT RESULTS` is in the History section. The rider's own strap (Garmin HRM 600) is bought before real tests.

### 5.2 Heart Rate Measurement (0x2A37, notify)
Standard Bluetooth SIG format; pure parser `HrMeasurement.parse`, tested with captured bytes.

| Flags bit | Meaning |
|---|---|
| 0 | 0: bpm uint8; 1: uint16 little endian |
| 1-2 | sensor contact: bit 2 supported, bit 1 detected (0b11 detected, 0b10 supported not detected, 0b0x not supported) |
| 3 | energy expended (uint16) present; skipped |
| 4 | RR list present: uint16, 1/1024 s |

Output `bpm`, `contact`, `rr` (ms). Reject a packet shorter than its flags promise; bpm 0 or above 250 is invalid. RR is kept in the sample file only. Also read 0x2A38 and Battery 0x180F/0x2A19 if present.

### 5.3 Signal quality
Valid 1 Hz sample: packet in the last 2 s, contact DETECTED or UNSUPPORTED, 30 <= bpm <= 220 (bounds: review). `coverage = valid seconds / phase seconds`. Status `OK` / `WEAK` (last valid 2-5 s ago) / `LOST` (over 5 s). A jump of more than 30 bpm between seconds is `suspect` and excluded from the reading means (threshold: review). The four reading times need a valid sample within +/- 2 s; otherwise that reading is missing; fewer than 3 valid readings in a stage makes the stage invalid (`invalid_stage`, no estimate; APP CHOICE, flagged).

### 5.4 Reconnect
`connectGatt(autoConnect=false)` to start; during a test retry with back-off 1, 2, 4 s; re-enable notifications; reconnect inside the dropout window (10 s in the stages, 180 s in seated rest) cancels the abort.

### 5.5 Heart-rate strap facts: what was and was not verified (2026-10-20, web search and fetch)

| Question | Finding | Verified? |
|---|---|---|
| Garmin HRM 600 transmits over Bluetooth Low Energy and ANT+ | yes (retailer specs, Garmin help pages) | yes, from retailer and Garmin web help |
| Broadcasts the standard Heart Rate Service 0x180D with 0x2A37 notifications | **not stated** in any page found; third-party apps (Zwift) use the strap in "open" mode, which implies the standard profile | **NOT verified by name** |
| Pairing / bonding | Garmin defines two BLE connection types: **secure** (encrypted, authenticated, for Garmin watches/devices; LED 2 flashes every 5 s) and **open** (recommended by Garmin "for third-party smartwatches, tablets, or fitness equipment"; LED 3 flashes every 5 s). Press the button twice quickly to toggle. A Zwift user got "connected but HR 0" in secure mode and fixed it by switching to open. Whether open mode needs bonding: not stated | partly: connection types verified from Garmin pages and a forum thread; bonding NOT verified |
| More than one simultaneous BLE connection | spec: **3 BLE connections** and unlimited ANT+. Whether a secure link to a watch plus an open link to the phone work together is **unresolved** (forum, Garmin support suggested). An older test (Fenix 6 as broadcaster) found 3 concurrent BLE apps failing, a different device | spec yes; real behaviour NOT verified |
| Battery | Garmin rating about 2 months at 1 h/day (rechargeable); one reseller says 60 h | secondary source; NOT verified on a device |
| Contact behaviour | not found for the HRM 600; generic: dry electrodes read poorly until moistened | NOT verified |

Implication: the app must work with an **open** (unencrypted) strap connection, must not need bonding, and must show a clear "no heart rate (check the strap is in open mode)" row. **On-device verification** is part of the dry run (9.3): read the strap with nRF Connect first (advertises 0x180D? notifies 0x2A37 without a pairing prompt? contact bit changes when lifted off the skin?), then with a watch connected at the same time and the phone connected.

### 5.6 Coexistence with `BridgeLink`
Two independent GATT clients on the phone; stop scanning before connecting; no scan during a test. Risks to verify on the Pixel: connection-interval contention; a Bluetooth headset as a third radio user. Not verified.

### 5.7 Settings additions

| Group | Fields |
|---|---|
| Rider | Age, **Sex (`male`/`female`, required; no default; the Test tab shows "Set sex in Settings" until set)**, Weight (kg), Resting HR (typed or from test). The test shows age and weight for confirmation at Setup (3.2). The phone profile is the source of truth for the test; HA's own `input_number` values feed only the floor sensor. |
| Chest strap | Selected strap, `Find strap`, `Forget`, **mine / borrowed** toggle (5.1), live bpm with signal word |
| Test options | Speech on/off (default on, English), beep check, **the VO2 result topic** (base topic `ebike/vo2/result`, configurable like the ride topic; the ack topic is derived) |
| **Safety limits** | The heart-rate ceiling percent and the HRmax method (Tanaka or typed value; no HUNT), shown **read-only with the reason** until a physician sign-off is recorded; then editable only within the age-derived bounds (8.4). Other safety parameters (RPE stop, waits, dropout) and `protocolVersion` are read-only always. The HRmax method is **not** in the Rider group any more. |

Profile values used in a test are copied into the result; later edits never rewrite old results.

## 6. Data on the phone

File-based like `RecordStore` (no Room: tens of results a year). One set of names, used everywhere in this document.

| File | Content |
|---|---|
| `vo2/results.jsonl` | one JSON per finished or aborted test (wire JSON + local fields such as `setup.dev`, `rejected`, `not_eligible` attempts) |
| `vo2/outbox.jsonl` | unacked `test_id`s; independent of `SyncEngine` ride rules; never contains development or `not_eligible` results |
| `vo2/active.json` | the active test |
| `vo2/safety_signoff.json` | the current physician sign-off (atomic write), absent = defaults apply |
| `vo2/safety_signoff_history.jsonl` | append-only: every sign-off recorded and every revert to defaults (kept; never rewritten) |
| `vo2/samples_<test_id>.csv` | 1 Hz samples, kept 30 days |
| `vo2/stage2_guide.json`, `vo2/readiness_questions.json`, `vo2/medications.json` | versioned data files (2.3, 3.2) |

Record groups: Identity (`test_id`, `schema`, `protocol_id`, `protocol_version`, `app_version`, `questions_version`); Time; Status; Inputs used (age, sex, weight_kg, rest_hr, hrmax and method); Safety (ceiling percent and bpm, method, `ceiling_changed`, copy of the sign-off record: approver, date, approved ceiling and method, protocol version; the free-text note stays local); Setup (assist, training status, hr_source, `seated_rest_s`, local `dev`); Stage 1 and 2 (target W, mean W, in-band %, mean HR from the four readings, `end_hr`, coverage); Stage 2 additionally RPE band and optional exact RPE, adjustment; Result (estimate L/min and ml/kg/min, range, `estimate_basis`, flags, quality, repeat advice); `rpe_at_stop`; Sync (local only: sent / waiting / rejected / stuck).

Privacy: health data stays on the phone and in the owner's Home Assistant.

## 7. Home Assistant (summary; contract in [VO2_RESULT.md](../contracts/VO2_RESULT.md))

- Topic `ebike/vo2/result`, **not retained**, QoS 1; ack on `ebike/vo2/result/ack` (`stored:true`, or `stored:false` with a `reason`); the phone releases from its outbox only after a `stored:true` ack and stops retrying on a `stored:false` ack.
- `ha-integration` adds a receiver (like `offline_backfill`), sensors, long-term statistics, dashboard changes.
- The `eBike VO2max Estimate` sensor (10.8 x best 5 min W/kg + 7) is a **lower bound** and is renamed (same `unique_id`); the Uth attribute and the watch override are removed; the test result becomes the headline. The old sensor stays on the dashboard as a **secondary line** once a test exists (decision (e)). A watch value is never the test result.
- Aborted attempts: the facts the contract lists, no estimate. The HR series is not published. Development results are never published.
- Dashboard wording says "Ekblom-Bak style estimate", never "measured". The card shows `SHORT REST` and `HEART-RATE LIMIT CHANGED` when the attributes say so.

## 8. Safety

### 8.1 Parameters (`SafetyConfig`; read-only in Settings except the two limits of 8.4, which unlock only with a sign-off)

| Parameter | Default (source) | Review |
|---|---|---|
| RPE stop | RPE 17 or more in stage 2, acts at once (S2, only EB rule) | R |
| RPE no answer | stop (`rpe_no_answer`) after the prompt, `rpeFirstWaitSeconds` (15 s), one repeat, `rpeRepeatWaitSeconds` (15 s); owner decision (h) | R (item 7) |
| `hrCeilingPctOfHrMax` | 85 % of Tanaka HRmax: about 137 bpm at 67, **ON, hard stop** in stage 1 and 2 (warning only in rest). App's own extra; conflicts with the protocol target of 110-140 bpm (2.8, accepted as the safer error). Editable only by sign-off (8.4), never above the absolute age cap `round(0.90 x HRmax_tanaka)` (145 bpm at 67) | **R** |
| `hrMaxMethod` | `tanaka`; editable only by sign-off (8.4) | **R** |
| `hrCeilingSeconds` | **15 s** continuously above the ceiling, on the smoothed value; smoothing = median of the last 5 valid 1 Hz readings (so a single spike never counts) | PLACEHOLDER (to review) |
| `hrDropoutSeconds` | stages: **10 s**, warn at 3 s (design doc). Seated rest: warn at **60 s**, abort at **180 s** (`hrDropoutRestWarnSeconds`, `hrDropoutRestAbortSeconds`) | R; rest values PLACEHOLDER (to review) |
| `linkLostSeconds` | **15 s** continuously (stages only), warn at 3 s | PLACEHOLDER (to review) |
| `powerStaleSeconds` | **5 s**: rider power older than this counts as link-lost evidence (stages), or as "not ready" before stage 1 | PLACEHOLDER (to review) |
| `interruptSeconds` | **3 s** of a call audio mode (3.6) | PLACEHOLDER (to review) |
| Abort precedence | `user_stop` > `rpe_stop` > `hr_ceiling` > `rpe_no_answer` > `hr_dropout` > `bluetooth_off` > `link_lost` > `interrupted` (3.6) | PLACEHOLDER (to review) |
| Health questions, medication list, symptom text | physician-written versioned data file | **R** |
| Contraindications | NOT-FOUND beyond "healthy" | **R** |

Wording (Procedure, before START, Result): "This is a coaching estimate in the style of the Ekblom-Bak test, not a medical device and not the laboratory test. It does not diagnose or treat anything. Stop if you feel unwell. If you have a heart or health condition, or take medicine that affects your heart rate, talk to your doctor before using it."

A hard stop cannot be disabled in the run screen, and the ceiling cannot be switched off or raised above the absolute age cap at all (only its percent and method can be changed, within the age-derived bounds, by sign-off). The app never encourages pushing harder than the protocol.

### 8.2 Road adaptation and calibration (not validated)

- The EB test relies on constant mechanical efficiency on a braked ergometer; `dPO` and `dHR/dPO` depend directly on accurate pedal power. The Bosch torque x cadence value is unvalidated, particularly near 30 W; assist, gradient, wind and rolling resistance break the assumption. Treat the result as an **uncalibrated analogue**.
- **Proposed calibration**: (1) do the real EB test once in a lab or on a calibrated mechanically braked ergometer (the same rider), and compare the number; (2) compare the Bosch rider power with a calibrated pedal or crank power meter on the same bike if one can be borrowed; (3) in every road test record the assist level (Off is the cleanest) and use the same flat route and conditions; a headwind or hill day is a reason to repeat.
- **Place**: a stationary trainer or a closed quiet flat path is recommended over a public road, because there is no pause and a stop costs the attempt (decision (j)).
- **Wording for the rider**: "This number is a guide for watching your own change over months. It has not been checked against a laboratory test on this bike."
- **Accuracy**: lab limits of agreement for ages 65-75 are about -0.43..+0.39 L/min (women) and -0.60..+0.70 L/min (men) (S3). For this rider (typical 2.2 L/min) +/-0.7 L/min is roughly +/-30 %, and for a typical older man (mean 2.83 L/min in S3) +/-21-25 % (this is **my arithmetic**, not a figure from the paper). Low-fit older men were overestimated; individual error is persistent over years (S4, r 0.84), so the test is better for **change** (estimated change tracked measured change, r 0.75, slope 0.88) than for one number.

### 8.3 Review checklist for a physician or exercise physiologist (with sign-off)

| # | Question | Decision | Name / date |
|---|---|---|---|
| 1 | May this rider do a self-directed moderate-effort test at all (cardiac history, blood pressure, HR-affecting drugs)? Are RPE 17 and the extra stops the right stop rules? | | |
| 2 | The HR ceiling (85 % of predicted HRmax, about 137 bpm at 67, which sits at the top of the protocol's own 110-140 bpm target and can stop a correct test), which HRmax equation, the **absolute cap of 90 % of the age-predicted maximum (145 bpm at 67)** and the other bounds of the sign-off form (8.4). A physician sign-off is the only way the app lets these values change, and never above the cap | | |
| 3 | Is the road/e-bike adaptation acceptable, and how should the Bosch power reading be checked against a calibrated ergometer (at least one lab EB test)? | | |
| 4 | The accuracy statement shown to the rider (trend over months, not a diagnostic number) | | |
| 5 | Whether the women's equation may be used for small, low-fit older men (unvalidated) | | |
| 6 | The 20-minute rest / same-day retest rule versus retest on another day | | |
| 7 | Health questions and medication list wording; confirm the owner's rule that an unanswered RPE prompt (asked twice, 15 s each) stops the test; and whether a rest shorter than 15 minutes may ever be used | | |
| 8 | The load raise: 63 W or 32 W steps applied at once without a ramp, and the cap of 150 W for riders 65 and over | | |
| 9 | The rest-phase limits (warn 60 s, abort 180 s without heart rate), the 15 s ceiling time, and that a stop mid-test has no pause (decision (j)) | | |
| Owner | Owner accepts the placeholders listed in section 10 | | |

### 8.4 Safety limits and the physician sign-off (decisions (i), (k))

**What is guarded.** `hrCeilingPctOfHrMax` (default 85) and `hrMaxMethod` (`tanaka` or `typed`; a typed HRmax value in bpm). They are shown in Settings, section **Safety limits**, and are the only safety values that can ever change.

**Absolute cap (decision (k), 2026-10-21).** **No sign-off, percent or typed HRmax may produce a ceiling above 90 % of the age-predicted maximum.** `absoluteCeilingBpm(age) = round(0.90 x round(208 - 0.7 x age))` is a pure function of age: 175, 156, 145, 134 bpm at ages 20, 50, 67, 84. The same cap is applied at run time (`hrCeilingBpm = min(requested, cap)`), and a stored sign-off that no longer passes for the current age is treated as Locked. A sign-off that exceeds the cap is rejected by `validateSignoff`; it cannot be recorded. The ceiling cannot be switched off. Going back to the defaults needs no sign-off.

**Known conflict (stated plainly).** At age 67 the default ceiling is about 137 bpm; the protocol's own stage-2 target is 110-140 bpm. The ceiling can stop a correctly run test. The owner accepts that as the safer error until the physician decides (checklist 8.3 item 2). Until then the defaults apply and no one can edit them without a sign-off.

**UI states** (pure function `safetyLimits`, section 4.3):

| State | When | Settings shows |
|---|---|---|
| Locked (defaults) | no sign-off; or the sign-off names another `protocolVersion`; or it was reverted; or it fails the bounds for the current age | Ceiling "85 % of predicted maximum (about NNN bpm for your age)" and method "Tanaka (208 - 0.7 x age)", both **read-only**, with the reason: "Locked. These limits protect you. Change them only on the advice of a doctor or exercise physiologist." A stale sign-off adds "The sign-off was for protocol version N; this is version M." Button `RECORD A SIGN-OFF`. |
| Unlocked (signed off) | a valid sign-off exists for the current `protocolVersion` and age | The approved values, editable only through the form (`EDIT SIGN-OFF`); who approved and when; text "Changed from the standard limits" when different from the defaults; button `REVERT TO STANDARD LIMITS` (no sign-off needed to go back to the safer defaults; it is logged in the history file). |
| Sign-off form | opened from either state | Fields: **approved by** (name, 2-80 characters, required), **date approved** (date picker; not in the future, not before 2020), **approved heart-rate ceiling in % of HRmax** (integer 1-100 as syntax only; the real bound is the resulting bpm below), **HRmax method** (one of `tanaka`, `typed`; the list is short on purpose, extending it bumps `protocolVersion`), **typed HRmax in bpm** (required only for `typed`; within `tanakaHrMax(age)` +/- 20 bpm, PLACEHOLDER (to review): 141 to 181 at age 67), **note** (free text, optional, up to 500 characters). **Bounds are derived from age, not fixed:** the form shows live "This gives NNN bpm. Allowed for your age: FLOOR to CAP" (113 to 145 at age 67) and rejects a result outside it in words next to the field. `SAVE` stays disabled until `validateSignoff` returns no errors. The form says that the app records a statement and cannot check that a doctor approved it. |

**What is persisted.** `vo2/safety_signoff.json` (current) and the append-only `vo2/safety_signoff_history.jsonl` (files in section 6). Record: `approver`, `approved_date`, `ceiling_pct`, `hr_max_method`, `hr_max_typed` (only for `typed`), `note` (local only, never published), `protocol_version`, `recorded_epoch`. Profile edits and sign-offs never rewrite old results.

**What goes into a test.** At BEGIN the active limits are snapshotted (and persisted in `active.json`, 4.2). The result copies the sign-off record (approver, date, approved ceiling and method, protocol version, no note) and the effective values into the **required** `safety` object of the contract. `ceiling_changed` is true when the effective percent or method differs from the defaults; then the Result screen and History say so (3.4), and the HA entity attributes show it.

**Effect on quality score and repeat advice.** A changed ceiling does not change the score or the estimate (the equation does not use HRmax or the ceiling); it adds the quality flag `ceiling_changed` so it is visible everywhere. An `hr_ceiling` abort gives repeat advice "another day" and never suggests raising the limit (2.7).

**Unit-testable pure functions:** `safetyLimits` (lock state), `signoffBounds`, `validateSignoff`, `tanakaHrMax`, `absoluteCeilingBpm`, `hrCeilingBpm`, `ceilingExceeded` (4.3).

## 9. Tasks, order, tests

### 9.1 Order and ownership

| # | Task | Owner | Size | Files (would change) |
|---|---|---|---|---|
| 0 | Review this spec; decide the owner items of section 10; confirm placeholders; physician checklist 8.3 | owner (physician for 8.3) | S-M | this file |
| 1 | Finalise `VO2_RESULT.md` (remove DRAFT), golden fixture, Python contract test, error ack. **After 0** (the contract shape depends on the owner decisions) | `ha-integration` with `android-app` in one session | S | `docs/contracts/VO2_RESULT.md`, `README.md`, `tests/test_vo2_result.py` |
| 2a | Data files: `readiness_questions.json` and `medications.json` **written by the physician** (android-app provides the file format and loader); `stage2_guide.json` (table 2.3) | physician (text), `android-app` (format, loader, tests) | S | `android/.../vo2/*.json`, loader |
| 2b | Pure core: `ProtocolConfig`, equation and guards, RPE decision and readback, schedule with rest extension and raise, `Vo2Machine` (Readback, Teardown, precedence), band, dropout, ceiling, **`safetyLimits`, `signoffBounds`, `validateSignoff`, `absoluteCeilingBpm`**, quality, range, JSON, `safety_signoff*.json` persistence | `android-app` | L | `android/.../vo2/*.kt`, `*Test.kt` |
| 3 | `HrMeasurement` + `HrLink`, strap "mine" flag | `android-app` | M | `ble/HrLink.kt` |
| 4 | Audio: `CuePlayer` (tones, English TTS, focus), beep check, call-mode listener, service integration, Teardown | `android-app` | M | `vo2/CuePlayer.kt`, `BridgeService.kt` |
| 5 | Fourth tab: navigation (bar and rail), banner on the Test tab and Run screen; Procedure, Readiness, Run, **RPE picker, readback and CORRECT**, Result, History (with development, rejected, stuck states) | `android-app` | L-XL | `ui/*`, `MainActivity.kt` |
| 6 | Settings: Sex (required), strap and mine toggle, **Safety limits section with the sign-off form and lock state (8.4)**, VO2 result topic setting; test unavailable until sex set | `android-app` | M | `SettingsScreen.kt`, `SettingsForm.kt` |
| 7 | Results store, outbox (excludes development results), relay for the second topic, rejection ack handling | `android-app` | M | `sync/Vo2Store.kt`, `MqttRelay.kt` (`SyncEngine` ride rules untouched) |
| 8 | HA receiver, sensors (with `ceiling_changed`, `short_rest`, `safety` attributes), statistics, ack and error ack | `ha-integration` | M-L | `custom_components/ha_bosch_ebike/vo2_result*.py`, tests |
| 9 | Dashboard and package changes (list below) | `ha-integration` | S-M | see below |
| 10 | Supervised dry run (strap check with nRF Connect first), then a real test, then the lab calibration | `android-app` installs; `field-test` judges; owner rides | M | `docs/app/PHONE_LINK_TEST_PROTOCOL.md` |
| 11 | Docs: `android/CLAUDE.md`, design doc status | `android-app` | S | docs |

**Dependencies.** 0 before 1 and before 2a/2b. 1 before 7 and 8. 2a and 2b before 5. 3, 4, 6 and 7 before 5 (strap row and mine flag, beep check, Safety limits state and sex gate, results store). 3 and 4 parallel with 2b. 8 before 9 (sensor names). 10 needs 3, 4, 5, 6, 7 and 8 (9 for the HA card). 11 after 10.

**Task 9: HA files and lines for the `ha-integration` agent** (line numbers from the review, locate by text):
- `dashboards/ebike_package.yaml` ~148-171: rename the display name of `ebike_vo2max_estimate` to "eBike VO2max floor from best 5 min" (keep `unique_id`); remove the `sensor.xiaomi_watch_5_vo2max` override (~157, ~165, the `source` attribute); remove `vo2max_from_hr_uth` (~168-171) and the `hr_max_tanaka` attribute (~166, if only used by it).
- `dashboards/ebike-dashboard.yaml` ~551-600: the VO2 card reads `sensor.ebike_vo2max_test` (state, `low`, `high`, `quality_score`, `finished`, badges) instead of `sensor.ebike_vo2max_estimate`; remove the `attrs.source === 'watch'` branch and 'Value from the watch' (~579); keep the floor as the secondary line; the apexcharts series (~598-605) plots `sensor.ebike_vo2max_test` (the `statistics: type: max` form and the external statistic `ha_bosch_ebike:vo2max_test` may not be accepted by `apexcharts-card`: verify, otherwise plot the sensor). ~637-650: the markdown paragraph with the Uth text and "or the watch value if ... exists": replace with the floor wording.
- `dashboards/ebike-README.md` ~21: remove the watch-value sentence.
- `dashboards/mockups/analysis.html` ~184 (watch slot) and ~336 (method text): remove the watch slot and the Uth mention.
- Weight and age: the floor sensor keeps HA's `input_number` weight and age; the test uses the phone profile (source of truth for the test, copied into every result); the card shows the weight used by the test from the result.
- Renaming the display name keeps `sensor.ebike_vo2max_estimate` as the entity id (it has a `unique_id`).

### 9.2 Unit tests
Every pure function in 4.3; the golden JSON shared with the Python test; scripted `Vo2Machine` runs (full test 1560 s, raise path with the extension 1620 s, rest extension, keep path, RPE stop at once with no readback, readback and one correction, correction timeout (first answer stands), each abort ending in `Teardown` and the spoken guidance; RPE answered at the first prompt, answered at the repeat prompt, **no answer to either = `Aborted(rpe_no_answer)`** with the repeat prompt at 75 s and the stop at 90 s, late tick of 40 s fires both in order, answer exactly at the deadline counts; `Ineligible` from Readiness; `Invalid` from Computing; abort conditions in Cooldown give `Done` with `cooldown_cut_short`; rest-phase dropout warns at 60 s and aborts at 180 s; link loss not evaluated in rest; precedence pairs); `rpeWindow` edges; `safetyLimits` lock states, `signoffBounds`, `validateSignoff` (cap exceeded by the percent and by the typed route), `hrCeilingBpm` and `ceilingExceeded` (4.3); a result with a changed ceiling carries `ceiling_changed` and the sign-off copy; a rest below 900 s carries `short_rest`; development results never enter the outbox; a `stored:false` ack marks the entry rejected and stops retries; the Result text for `rpe_no_answer` and for `hr_ceiling` in stage 2; `HrMeasurement` with captured strap bytes; outbox idempotency; no estimate for aborted or ineligible runs (property test).

### 9.3 Phone and ride
1. **Strap check (before the dry run):** nRF Connect on the phone with the strap: 0x180D present? 0x2A37 notifications without a pairing prompt (open mode)? contact bit? Then with a watch connected at once. Repeat with the borrowed strap (development) and the HRM 600 when bought.
2. **Dry run, bike stationary (supervised, ideally on a trainer):** all screens and cues, beep check, RPE picker one-handed with each button, readback and CORRECT, forced aborts, screen off 20 minutes, headphones, a ringing call, result lands in HA, outbox with broker off then on, a development result stays on the phone.
3. **Real ride:** trainer or closed flat path preferred, daylight, owner's decision; treated as a trial; plus the lab calibration test.
4. Report what was verified on the phone, in unit tests, or not at all.

## 10. Open items and decisions

### Decisions (owner, 2026-10-21)

| # | Decision |
|---|---|
| (g) | **Seated rest before the start: 15 minutes.** The owner confirmed the default. It stays a config value (`seatedRestSeconds`); shorter values remain "needs physician or physiologist sign-off" and flag the result (`short_rest`). |
| (h) | **Exertion rating (Borg 6-20) with no answer in stage 2:** ask, wait 15 s, ask once more (spoken and on screen, same picker), wait 15 s more; still no answer = the test STOPS, no estimate, `abort_reason = rpe_no_answer`. Both waits are config values. Reason: the rating is the only stop rule in the papers, so an unanswered prompt is treated as a possible reason to stop. Replaces the earlier "keep the load, flag it" default and the 30 s window. |
| (i) | **Heart-rate ceiling:** the app's own extra safeguard, 85 % of predicted HRmax (208 - 0.7 x age; about 137 bpm at 67), ON as a hard stop by default. The percent and the HRmax method are editable only through a physician sign-off flow in Settings, Safety limits (8.4); until then the defaults apply. The sign-off record is stored on the phone and copied into every result and the HA contract (now required `safety` object). Known conflict: 137 bpm vs the 110-140 bpm stage-2 target; the ceiling can stop a correctly run test, accepted as the safer error until the physician decides. **Amended by (k).** |
| (j) | **No pause in the test.** The timing is physiological, so a stop ends the attempt. The Procedure and Run screens and the spoken stop line say: "Stopping is always fine. Nothing is lost but this attempt." A trainer or a closed quiet path is recommended over a public road. No pause states are added. |
| (k) | **Absolute ceiling cap.** No sign-off, percent or typed HRmax may set a ceiling above 90 % of the age-predicted maximum (208 - 0.7 x age; 145 bpm at 67). The bound is a pure function of age; the sign-off form bounds are derived from age, not fixed (70..95 % and 120..220 bpm are withdrawn). Reverting to the defaults needs no sign-off. |
| (l) | **Development results.** Results from a chest strap not marked "mine" are stored on the phone with `setup.dev = true`, shown in History as DEVELOPMENT, and **never published to Home Assistant** (the outbox excludes them). The "mark as mine" step is at strap selection (5.1). |

Notes on choices made when the review offered alternatives (the safer one for a 67-year-old doing a self-directed test): an RPE tap is final and a 17-20 stops at once rather than being read back first; a "continue anyway" at a high rest heart rate is allowed only after the extension and a prompt, flagged, with the resting HR not stored; link and heart-rate loss in seated rest warn and abort only after 180 s, but Bluetooth off and a call still abort; after the last reading a stop or fault ends the test as `Done` (keeping a good result) instead of discarding it; calls are detected through `AudioManager` mode (no new permission) rather than a phone-state permission; the golden fixture uses the lower table load (88 W, `inactive`) rather than an invented 94 W; the load raise has a cap.

### Decisions (owner, 2026-10-20)

| # | Decision |
|---|---|
| (a) | The equation (men/women) is chosen by a `sex` field (`male`/`female`) in the Settings rider profile. The owner sets it once; no default. The Test is unavailable until it is set. |
| (b) | Audio = tones (primary cue) plus spoken English via `TextToSpeech`; if TTS is unavailable the run uses tones only; a beep check precedes the start. |
| (c) | The Test entry is a fourth tab: Status, Ride, Test, Settings (rail in landscape). The Status banner stays visible on the Test tab. |
| (d) | Strap: the owner will buy a Garmin HRM 600; a borrowed strap is used for development only. Sensor selection is by scanning for the 0x180D service and the owner picks. Facts in 5.5. |
| (e) | Aborted attempts are published to HA (refined on 2026-10-21 to the facts listed in 3.6: no estimate, no HR series). Sample files kept 30 days. The `floor` sensor stays on the dashboard as a secondary line once a test exists. |
| (f) | `mac` text: one text everywhere, `"<test_id>"` (both the message and the ack). See the contract; it protects the id only, not the content. |

### Open items

| Kind | Item |
|---|---|
| (i) NOT-FOUND in the literature | Which of the two table values per cell is flywheel vs pedal power; steady-state criterion; cadence and power tolerance bands; how the HR readings are timed in an extended stage 2; kp-to-watt formula (the 31.7 W/0.5 kp is inferred from the table); rest length requirement beyond S3's 15 min; contraindication list beyond "healthy"; HUNT coefficients (not verified); e-bike/outdoor validation; sign convention of the S3 limits; Borg anchor wording for the buttons; any source for the quality weights, the raise cap and the guard values |
| (ii) Needs physician review | Section 8.3 items 1-9, the HR ceiling vs the 110-140 bpm protocol target (defaults apply, locked, until a sign-off), the absolute 90 % cap and the age-derived sign-off bounds, health questions, medication list, confirming the no-answer stop and any rest shorter than 15 minutes, the raise steps and cap, the rest-phase limits |
| (iii) Needs on-device / bike verification | Garmin HRM 600 profile 0x180D, open vs secure mode, bonding, simultaneous watch connection, battery and contact behaviour; Bosch power near 30 W and staying awake through 15 min of rest; table watts and 0.5 kp steps on this bike; TTS English data; foreground service with screen off; `AudioManager` call mode on Android 17; RPE picker usability; the BLE load of two links |
| (iv) Owner decisions still open | Confirm the PLACEHOLDER defaults (rest HR margin, rest extension and prompt time, bands, grace times, `dpoSource`, guard values, quality weights and threshold 70, raise cap, hard-stop timings, precedence, stage-2 table column); phone battery floor; require the same assist level as last time; that aborted records carry more than "status only" (3.6); that `not_eligible` attempts stay on the phone; the 30-day keep time for development results; the single `retestMinHours = 24` |

## Plain-language explanation (for the Procedure screen)

"This test estimates how fit your heart and lungs are, in the style of a well-known laboratory test. It works best on an indoor trainer or a closed, quiet, flat path. You sit still for about 15 minutes so your heart rate settles. Then you pedal for 4 minutes at an easy 30 watts, and straight after, for 4 minutes at a harder power that is chosen before you start. In the second minute of the harder stage the phone asks how hard it feels, on a scale from 6 to 20, and may ask you to push a little more, or to hold, and if it feels very hard it stops the test. It asks twice, 15 seconds apart; if you do not answer either time, the test stops, because it cannot tell whether something is wrong. It also stops if your heart rate reaches a safety limit. There is no pause, but stopping is always fine: nothing is lost but this attempt. The phone beeps and speaks every change, and always shows what to do now and how long is left. Afterwards it works out a number from how much your heart rate went up between the two stages. It is an estimate, not a measurement: the original test was checked in a laboratory, not on an e-bike on the road, so use the number to watch your own change over months, on the same bike and route, and stop at any time if you feel unwell."
