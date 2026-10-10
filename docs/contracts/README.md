# Contracts between the parts

The project has four parts that are built and tested separately: the **firmware** (ESP32), the **Android app**, the
**Home Assistant integration** and the **enclosure**. They only depend on each other through the written contracts below.

| Contract | Between | Document |
|---|---|---|
| BLE link: GATT service, 41-byte status, 56-byte record, commands | firmware <-> Android app | [PHONE_LINK_PROTOCOL.md](../app/PHONE_LINK_PROTOCOL.md) |
| MQTT records and acknowledgements, exactly-once rules, sensor key order | firmware / Android app -> Home Assistant | [MQTT_RECORDS.md](MQTT_RECORDS.md) |
| Board measurements, hole pattern, screw length | you -> enclosure | `hardware/enclosure/README.md` |

## How to change a contract

1. Update the document and the tests that pin it (host tests, `ProtocolTest`, `RecordJsonTest`, `test_backfill_core.py`) **first**.
2. Then change the part that owns the change, and tell the other owners which document changed.
3. A change that touches two parts at once (for example a new field in the status block) is done by one agent in one
   session, so the two ends cannot drift apart.

Real captured bytes (from nRF Connect or the logs) are the best test fixtures: the Android tests already use them.
