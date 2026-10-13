"""Dashboard package and dashboard YAML checks - run with:
python3 tests/test_dashboard_package.py

Needs PyYAML and Jinja2; without them the script says SKIP and exits 0 (CI
without those packages stays green, but the checks did not run).

1. The power-zone template in dashboards/ebike_package.yaml is rendered with a
   stub `states()`: standing still (rider power under 1 W, or cadence 0) is
   state 0 ("not pedalling") and therefore in none of Zone 1-5; no power
   reading is "unknown".
2. dashboards/ebike-dashboard.yaml parses, every entity id it references is
   defined (package, integration sensor.py + en.json names, ESP bridge), and
   the two apexcharts-card keys that version 2.2.3 rejects are not used.
"""
import re
import sys
from pathlib import Path

try:
    import yaml
    import jinja2
except ImportError as exc:  # pragma: no cover
    print(f"SKIP: {exc.name} is not installed, dashboard checks did not run")
    sys.exit(0)

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = yaml.safe_load((ROOT / "dashboards" / "ebike_package.yaml").read_text(encoding="utf-8"))
DASH_TEXT = (ROOT / "dashboards" / "ebike-dashboard.yaml").read_text(encoding="utf-8")
DASH = yaml.safe_load(DASH_TEXT)


def _zone_template() -> str:
    for block in PACKAGE["template"]:
        for sensor in block.get("sensor", []):
            if sensor.get("unique_id") == "ebike_power_zone":
                return sensor["state"]
    raise AssertionError("ebike_power_zone template not found")


def zone(power, cadence="5", ftp="150") -> str:
    values = {
        "sensor.ebike_bridge_mobile_ebike_rider_power": power,
        "sensor.ebike_bridge_mobile_ebike_cadence": cadence,
        "input_number.ebike_rider_ftp": ftp,
    }
    out = jinja2.Environment().from_string(_zone_template()).render(states=lambda e: values.get(e, "unknown"))
    return out.strip()


def test_zones() -> None:
    assert zone("0") == "0", "0 W must be 'not pedalling', not zone 1"
    assert zone("0.0", cadence="0") == "0"
    assert zone("0.4") == "0"
    assert zone("80", cadence="0") == "0", "cadence 0 means not pedalling"
    assert zone("1", cadence="60") == "1"
    assert zone("82") == "1"
    assert zone("83") == "2"
    assert zone("112") == "2"
    assert zone("113") == "3"
    assert zone("135") == "4"
    assert zone("158") == "5"
    assert zone("187") == "5"
    assert zone("100", cadence="unavailable") == "2", "no cadence reading must not hide real power"
    assert zone("unavailable") == "unknown"
    assert zone("unknown") == "unknown"
    assert zone("240", ftp="300") == "3", "zones follow the FTP helper"


def test_zone_sensors_count_only_zones_1_to_5() -> None:
    wanted = {str(n) for n in range(1, 6)}
    states = {s["state"] for s in PACKAGE["sensor"] if s.get("platform") == "history_stats"}
    assert states == wanted, states


def _defined_entities() -> set[str]:
    found = {"sensor.ebike_power_zone", "sensor.ebike_zone_1_today"}
    for s in PACKAGE.get("sensor", []):
        if s.get("unique_id"):
            found.add("sensor." + s["unique_id"])
    for key in PACKAGE.get("input_number", {}):
        found.add("input_number." + key)
    for s in PACKAGE.get("mqtt", {}).get("sensor", []):
        found.add("sensor." + re.sub(r"[^a-z0-9]+", "_", s["name"].lower()).strip("_"))
    for block in PACKAGE["template"]:
        for s in block.get("sensor", []):
            found.add("sensor." + re.sub(r"[^a-z0-9]+", "_", s["name"].lower()).strip("_"))
    for n in range(1, 6):
        found.add(f"sensor.ebike_zone_{n}_today")
    # Integration sensors: <bike device name> + translated name (en.json).
    import json

    en = json.loads((ROOT / "custom_components/ha_bosch_ebike/translations/en.json").read_text(encoding="utf-8"))
    names = en["entity"]["sensor"]
    sensor_py = (ROOT / "custom_components/ha_bosch_ebike/sensor.py").read_text(encoding="utf-8")
    for key in ("last_ride_avg_power", "last_ride_max_power", "avg_power_all_rides"):
        assert f'key="{key}"' in sensor_py, f"{key} is not a sensor in sensor.py"
        slug = re.sub(r"[^a-z0-9]+", "_", names[key]["name"].lower()).strip("_")
        found.add("sensor.active_line_plus_" + slug)
    # ESP bridge (esphome/example-bridge-mobile.yaml, friendly name "eBike Bridge Mobile").
    esp = (ROOT / "esphome" / "example-bridge-mobile.yaml").read_text(encoding="utf-8")
    for label, domain in (
        ("eBike Speed", "sensor"),
        ("eBike Cadence", "sensor"),
        ("eBike Rider Power", "sensor"),
        ("eBike Battery SoC (Live)", "sensor"),
        ("eBike Connected", "binary_sensor"),
    ):
        assert f'name: "{label}"' in esp, label
        slug = re.sub(r"[^a-z0-9]+", "_", ("eBike Bridge Mobile " + label).lower()).strip("_")
        found.add(f"{domain}.{slug}")
    return found


def test_dashboard_entities_exist() -> None:
    code = "\n".join(line for line in DASH_TEXT.splitlines() if not line.lstrip().startswith("#"))
    ids = set(re.findall(r"\b((?:sensor|binary_sensor|input_number)\.[a-z0-9_]*[a-z0-9])\b", code))
    ids.discard("sensor.ebike_zone_")
    ids.discard("sensor.xiaomi_watch_5_vo2max")  # optional watch sensor, guarded in the package
    missing = sorted(ids - _defined_entities())
    assert not missing, f"dashboard references entities nothing defines: {missing}"
    assert "sensor.active_line_plus_avg_power_all_rides" not in DASH_TEXT


def _walk(node):
    if isinstance(node, dict):
        yield node
        for v in node.values():
            yield from _walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from _walk(v)


def test_apexcharts_schema_2_2_3() -> None:
    cards = [n for n in _walk(DASH) if n.get("type") == "custom:apexcharts-card"]
    assert cards
    for card in cards:
        assert card.get("chart_type", "line") in ("line", "scatter", "pie", "donut", "radialBar"), card
        assert "statistics" not in card, "statistics belongs inside a series in apexcharts-card 2.2.3"


def test_live_view_has_no_tap_popups() -> None:
    live = next(v for v in DASH["views"] if v["path"] == "live")
    for card in _walk(live["cards"]):
        if card.get("type") == "custom:button-card":
            template = DASH["button_card_templates"].get(card.get("template", ""), {})
            tap = (card.get("tap_action") or template.get("tap_action") or {}).get("action")
            if card.get("template") == "ebike_live_field":
                tap = DASH["button_card_templates"]["ebike_live_field"]["tap_action"]["action"]
            assert tap == "none", card


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print("ok  ", name)
    print("ALL PASSED")
