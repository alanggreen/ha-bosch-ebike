# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

Second surface: the Home Assistant dashboard (web, see surface brief dashboard-mockups-analysis-html). The Android companion app (docs/app/ANDROID_APP_DESIGN.md) is the active design work; it is native Material 3 themed with the dashboard's world.

## Users
One rider (owner of a Bosch eBike Smart System bike, EU account, Home Assistant at home on the LAN). Age 67, 61 kg. Reviews rides mostly afterwards on desktop or tablet; on the bike wants a phone-only live strip with no analysis. Other household members are not a confirmed audience.

## Product Purpose
A Home Assistant Lovelace dashboard for this fork of `ha-bosch-ebike`: shows live and historical eBike data, with the rider's own power (watts the rider produces, not motor assist) and VO2max as the headline interests. Success: after a ride the rider can see how hard they worked, in which power zones, how it compares with earlier rides, and how fitness is trending; during a ride a phone shows live power at a glance.

## Positioning
Combines the Bosch cloud data (rides, totals), the ESP32 BLE bridge live data (speed, cadence, rider power, SoC, odometer) with SD-card offline backfill, and the rider's Wear OS watch (heart rate) in one HA dashboard.

## Operating Context
- Served from Home Assistant at http://192.168.0.10:8123/dashboard-ebike/0 (YAML or UI dashboard).
- Built from core HA cards, ApexCharts card (HACS) and this repo's own custom cards (Dashboard, 2D/3D map, heatmap).
- Offline-buffered ride samples are backfilled into HA long-term statistics, so charts can span dead-zone gaps.
- Views: phone (live strip, separate from analysis) and desktop/tablet (analysis).

## Capabilities and Constraints
- Available entities: live `sensor.ebike_speed`, `ebike_cadence`, `ebike_rider_power`, `ebike_ambient_brightness`, `ebike_battery_soc_live`, `ebike_odometer_live`; last-ride sensors incl. `last_ride_avg_power`, `last_ride_max_power`, avg/max cadence, avg speed, calories, battery Wh/%; `avg_power_all_rides`, `total_calories`.
- VO2max is NOT provided by Bosch or the bridge. It will be an estimate computed in HA from power, weight, age and watch heart rate, labelled as an estimate. A watch-sourced VO2max entity slot is undecided/optional.
- Watch: Xiaomi Watch 5 running Wear OS with HA Companion; heart rate expected via HA sensor (entity id not yet known).
- Weight (61 kg) and age (67) must be configurable (input_number helpers). FTP is unknown: zones use a configurable FTP helper with a clearly labelled placeholder.
- Power figures are rider power from the bike's sensors; motor assist is not rider output.

## Evidence on Hand
Real entity names from `custom_components/ha_bosch_ebike/sensor.py` and `esphome/example-bridge-mobile.yaml`. No real ride history, FTP, heart-rate entity or VO2max values were provided; demonstration data in mockups must be labelled synthetic.

## Product Principles
- Rider power and fitness lead; bike telemetry supports.
- Never present an estimate as a measurement.
- Phone live strip stays free of analysis; analysis lives on its own view.
- Every number traces to a real HA entity or a labelled helper.

## Accessibility & Inclusion
Rider is 67: large, high-contrast numerals and generous touch targets for the live view; readable in daylight on a handlebar-mounted phone.

## Companion app (Android) - design facts
- Same rider. Phone sits on a handlebar mount in portrait, glanced at in daylight, one-handed, screen kept on; also pocket/bag with audio alerts only.
- Screens for the MVP: Status (ESP bridge link, bike, SD card, clock, Home Assistant, backlog, dropped/write failures, ESP uptime; actions Pair/Connect, Disconnect, Set clock) and Ride (rider power with zone bar, speed, cadence, battery, odometer, stale-data indication). Later: VO2max test, History, Settings.
- A failing SD card or lost bridge must always be visible on every screen; the banner is never blocked by other UI.
- Values come from the ESP32 phone link (docs/app/PHONE_LINK_PROTOCOL.md); nothing is invented client-side.
