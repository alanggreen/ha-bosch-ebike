# Ride data logging while offline (SD card buffer)

Offline buffering for the [mobile bridge](MOBILE.md): every sample is written to a microSD card first, sent over MQTT, and only released once Home Assistant has confirmed it stored it. A dead zone, hotspot drop, tunnel hiccup, broker restart or a power cut loses nothing, and nothing arrives twice.

Provided by the `ride_data_logger` external component ([`components/ride_data_logger/`](components/ride_data_logger/)) on the ESP side and `offline_backfill` in the `ha_bosch_ebike` integration on the Home Assistant side.

> **Available languages / Sprachen:** [English](#english) · [Deutsch](#deutsch)

---

## English

### How it works

1. **Write first.** Each sample (default every 2 s) is appended to the SD card whether you are online or not. Being online only decides whether it is also sent.
2. **Send and wait for the receipt.** Records go out over MQTT QoS 1 on `<replay_topic>` (default `<node name>/ride_log`). Home Assistant stores each record, then publishes `{"boot":..,"seq":..}` on `<replay_topic>/ack` meaning "everything up to this record is safely stored".
3. **Release only after the receipt.** The card keeps a record until its ack arrives. The acknowledged position is saved on the card in two checksummed slots, so a power cut cannot corrupt it.
4. **Resend is safe.** After a reconnect, an ack timeout (default 10 s) or a reboot, the ESP resends from the last acknowledged position. Every record has a globally unique id (`boot` = random per power-up, `seq` = counter within that boot) and Home Assistant drops ids it already stored. Resend plus de-duplication gives exactly-once delivery.
5. **Order is preserved.** Live samples go through the same card queue, so they can never overtake older buffered ones.
6. **Ring buffer.** The log is a ring of 28 KB segment files. When the budget (`max_log_bytes`, default 16 MiB, about 160 hours at 2 s) is full, the **oldest** unsent segment is deleted so recording never stops. The loss is counted and reported on `<replay_topic>/status` (`dropped`) and logged by Home Assistant.

What it cannot do: if the card is missing or has failed, samples are only sent live (no buffering) and the log says so at boot. Samples taken while the card write fails are lost and counted (`write_failures`).

### Hardware

Any standard 6-pin SPI microSD adapter. Classic ESP32 (30-pin devkit) VSPI pins, as used in `example-bridge-mobile.yaml` and `ha-bosch-ebike-home.yaml`:

| Adapter pin | ESP32 GPIO | key |
|---|---|---|
| CS | GPIO5 | `cs_pin` |
| MOSI | GPIO23 | `mosi_pin` |
| MISO | GPIO19 | `miso_pin` |
| SCK | GPIO18 | `clk_pin` |
| VCC | 3V3 (or 5V if the adapter has its own regulator) | - |
| GND | GND | - |

Avoid GPIO6-11 (flash). Format the card **FAT32** (not exFAT; cards over 32 GB often ship exFAT). `format_if_mount_failed` is off on purpose, so a bad card fails loudly in the log instead of being reformatted. Files use 8.3 names (`rlog/00000001.seg`, `meta.bin`) because FAT here has no long-filename support.

### Configuration

```yaml
ride_data_logger:
  id: ride_logger
  cs_pin: GPIO5
  mosi_pin: GPIO23
  miso_pin: GPIO19
  clk_pin: GPIO18
  time_id: sntp_time        # optional, stamps records with real time
  sample_interval: 2s       # default 2s, minimum 200ms
  replay_topic: ...         # default "<node name>/ride_log"
  ack_topic: ...            # default "<replay_topic>/ack"
  status_topic: ...         # default "<replay_topic>/status"
  max_unacked: 16           # max records in flight; starts at 1, doubles per ack (slow start)
  ack_timeout: 10s          # no ack for this long -> resend
  max_replay_per_loop: 4
  max_log_bytes: 16777216   # ring-buffer size on the card
  sensors:        [ {id: speed_sensor, key: "speed"}, ... ]        # up to 8
  binary_sensors: [ {id: connected_sensor, key: "connected"}, ... ] # up to 8
```

Changing the sensor list changes the on-card record layout only through the fixed 8+8 slots, so an unsent backlog survives a firmware update; the old single-file `ride_log.bin` from earlier versions is not read.

### Message formats

Sample (ESP to HA), QoS 1:
`{"boot":123456,"seq":42,"epoch":1725270031,"uptime_ms":88123,"speed":24.5,"cadence":81,"connected":true}`
`epoch` is omitted until the clock has synced. Home Assistant works out the time of such samples later from `uptime_ms` and the first sample of that boot that has an `epoch`.

Ack (HA to ESP), QoS 1: `{"boot":123456,"seq":42}`.

Status (ESP to HA) every 30 s: `{"sd":true,"unacked":0,"inflight":0,"dropped":0,"corrupt":0,"write_failures":0,"used_bytes":28672}`.

### Home Assistant side

Enable the receiver in `configuration.yaml`:

```yaml
ha_bosch_ebike:
  offline_backfill: {}          # or set topic / entities / keep_days
```

Requires the MQTT integration. For each sample it: drops resends, restores the real timestamp, appends it to `<config>/ha_bosch_ebike_ride_log/YYYY-MM-DD.jsonl`, saves its dedupe state, and only then publishes the ack. Hourly mean/min/max of speed, cadence, rider power, ambient brightness and battery SoC are imported into long-term statistics at the samples' **original** time (the odometer and flags stay in the raw log only). It is opt-in YAML because the integration is otherwise config-entry only.

### Troubleshooting

| Symptom | Cause |
|---|---|
| `Failed to mount the SD card filesystem` | Card not FAT32 |
| `spi_bus_initialize failed` | Pin conflict or MOSI/MISO swapped |
| `Offline buffering is DISABLED: no usable SD card` | Card missing, dead, or CS wrong |
| `No acknowledgement for 10000 ms - resending` repeatedly | `offline_backfill` not enabled in Home Assistant, MQTT user not allowed to publish/subscribe the ack topic, or HA down |
| `Ring buffer full: N ... overwritten` | Outage longer than the buffer; raise `max_log_bytes` or use a bigger card |

---

## Deutsch

### Funktionsweise

1. **Erst schreiben.** Jeder Messwert (Standard alle 2 s) wird zuerst auf die SD-Karte geschrieben, online oder offline. Online entscheidet nur, ob er zusätzlich gesendet wird.
2. **Senden und auf die Quittung warten.** Datensätze gehen per MQTT QoS 1 an `<replay_topic>` (Standard `<Knotenname>/ride_log`). Home Assistant speichert sie und antwortet auf `<replay_topic>/ack` mit `{"boot":..,"seq":..}` ("alles bis hierher ist sicher gespeichert").
3. **Erst nach der Quittung freigeben.** Die Karte behält einen Datensatz bis zur Quittung. Die quittierte Position liegt in zwei geprüften Slots auf der Karte; ein Stromausfall kann sie nicht beschädigen.
4. **Erneutes Senden ist sicher.** Nach Wiederverbindung, Quittungs-Timeout (10 s) oder Neustart sendet der ESP ab der letzten quittierten Position neu. Jede ID (`boot` + `seq`) ist weltweit eindeutig, Home Assistant verwirft bereits gespeicherte IDs. Ergebnis: genau einmal.
5. **Reihenfolge bleibt erhalten.** Auch Live-Werte laufen durch die Karte und überholen nie ältere.
6. **Ringpuffer.** Das Log besteht aus 28-KB-Segmenten. Ist das Budget (`max_log_bytes`, Standard 16 MiB, ca. 160 Stunden) voll, wird das **älteste** ungesendete Segment gelöscht, damit die Aufzeichnung nie stoppt. Der Verlust wird gezählt (`dropped` im Status-Topic) und von Home Assistant gemeldet.

Grenzen: Fehlt die Karte, wird nur live gesendet (kein Puffer). Schreibfehler werden gezählt (`write_failures`).

### Hardware und Konfiguration

Standard-6-Pin-SPI-microSD-Adapter an die VSPI-Pins des klassischen ESP32: CS GPIO5, MOSI GPIO23, MISO GPIO19, SCK GPIO18, VCC 3V3, GND. Karte als **FAT32** formatieren. Optionen: siehe Englisch (`ack_topic`, `max_unacked`, `ack_timeout`, `max_log_bytes`).

### Home-Assistant-Seite

In `configuration.yaml` aktivieren:

```yaml
ha_bosch_ebike:
  offline_backfill: {}
```

Benötigt die MQTT-Integration. Pro Messwert: Duplikat verwerfen, echten Zeitstempel herstellen, in `<config>/ha_bosch_ebike_ride_log/` anhängen, Zustand speichern und erst dann quittieren. Stundenmittel der numerischen Sensoren werden mit der **ursprünglichen** Zeit in die Langzeitstatistik importiert.
