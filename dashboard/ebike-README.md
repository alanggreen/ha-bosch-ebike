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
- FTP defaults to a **placeholder of 150 W**. The zone colour thresholds inside the profile chart are fixed for FTP 150 W (83/113/135/158 W) because apexcharts-card cannot read a helper; edit them if you change FTP. The zone number on the Live view and the time-in-zone chart do follow the FTP helper.
- The profile chart shows the last 3 hours, not a chosen ride. Change `graph_span` to match your ride; a ride picker like the mockup's needs a custom card.
- Time in zone is "today", from history of `sensor.ebike_power_zone`.
- Statistics-based long trends (VO2max over 12 weeks) fill in as the sensors accumulate long-term statistics.
- This YAML has not been loaded into a live Home Assistant; expect to adjust entity ids on first load.
