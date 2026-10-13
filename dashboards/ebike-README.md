# eBike Stage dashboard

A Home Assistant dashboard built around rider power and a VO2max estimate. The look is a Grand Tour stage-profile poster: power drawn as the altitude profile, zones coloured like climb categories.

- `mockups/analysis.html`, `mockups/live.html`: the approved design mockups (synthetic data, open in a browser).
- `ebike-dashboard.yaml`: the real dashboard. Views: **Live** (phone, handlebar), **Analysis** (desktop/tablet), **Routes** (this integration's own cards).
- `ebike_package.yaml`: helpers and sensors the dashboard needs (rider profile, power zone, best 5-minute power, VO2max estimate, time in zone, SD-card backlog).
- `themes/ebike.yaml`: "Ebike Stage" (dark) and "Ebike Live" (light, for sunlight).

## Install

1. HACS frontend: install **apexcharts-card** and **button-card**.
2. Copy `ebike_package.yaml` to `/config/packages/` and `themes/ebike.yaml` to `/config/themes/`. Make sure `configuration.yaml` includes `homeassistant: packages: !include_dir_named packages` and `frontend: themes: !include_dir_merge_named themes`.
3. Replace two tokens in both YAML files: `BIKE_` is the prefix of the Bosch integration sensors (search `last_ride_avg_power` in Developer Tools > States) and `BRIDGE_` is the prefix of the ESP bridge sensors (search `ebike_speed`). Example: `sed -i 's/BIKE_/my_ebike_/g; s/BRIDGE_/ebike_bridge_mobile_/g' ebike-dashboard.yaml ebike_package.yaml`. The Bosch sensors only exist once the `ha_bosch_ebike` integration is installed and set up.
4. Copy `ebike-dashboard.yaml` to `/config/dashboards/` and register it as in the header comment of that file (use a key like `dashboard-ebike-stage` so it does not replace an existing `dashboard-ebike`). The `resources:` entry loads the Barlow fonts.
5. Restart Home Assistant.

## What is real and what is not

- The live numbers come from the ESP bridge entities (`sensor.ebike_rider_power`, `ebike_speed`, `ebike_cadence`, `ebike_battery_soc_live`). If your entity ids differ, edit them in the two YAML files.
- **VO2max is an estimate**: `10.8 x best 5-minute rider power / weight + 7`, or the value of `sensor.xiaomi_watch_5_vo2max` if that sensor exists (it does not until you create it; check the watch's actual entity ids). A heart-rate cross-check (Uth) is shown as an attribute.
- FTP defaults to a **placeholder of 150 W**. Known limitation: the zone **colour bands** inside the stage-profile chart are fixed for FTP 150 W (83 / 113 / 135 / 158 W), because apexcharts-card `color_threshold` takes only numbers, not a helper. The dashed FTP line on that chart **does** follow the FTP helper, and a note under the chart warns when the helper is not 150 W. If you change FTP, edit the four `color_threshold` values (55 / 75 / 90 / 105 % of FTP). The zone number on the Live view and the time-in-zone block follow the helper.
- **Not pedalling is not a zone.** `sensor.ebike_power_zone` is `0` when rider power is under 1 W (or cadence is 0), `1` to `5` otherwise, and `unknown` with no power reading. Zone 1 to 5 time therefore excludes standing still. History already recorded is not rewritten: today's Zone 1 total keeps the idle time recorded before this change until midnight.
- The profile chart shows the **last 24 hours**, not a chosen ride (apexcharts-card cannot snap to the last ride); its power axis runs from 0 to at least 200 W. A ride picker like the mockup's needs a custom card.
- Time in zone, today, is a button-card block (apexcharts-card 2.2.3 has no horizontal stacked bar) from the five `sensor.ebike_zone_N_today` history-stats sensors; each zone prints its number, name and time.
- The Bosch all-rides average is `sensor.active_line_plus_avg_rider_power_all_rides` (the integration's name for that sensor is "Avg Rider Power (All Rides)"). Missing or unknown numbers show `--` and a reason.
- Tab titles are text only (no icons) so each tab is labelled.
- Statistics-based long trends (VO2max over 12 weeks) fill in as the sensors accumulate long-term statistics.
- This YAML has not been loaded into a live Home Assistant; expect to adjust entity ids on first load.
