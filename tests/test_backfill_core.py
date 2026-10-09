"""Standalone tests for backfill_core.py — run with: python3 -m pytest tests/test_backfill_core.py"""
import importlib.util
import json
import tempfile
from pathlib import Path

_path = (
    Path(__file__).resolve().parent.parent
    / "custom_components" / "ha_bosch_ebike" / "backfill_core.py"
)
_spec = importlib.util.spec_from_file_location("backfill_core", _path)
bc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bc)

T0 = 1_725_270_000  # an arbitrary unix time


def msg(boot, seq, epoch=None, uptime=None, **data):
    m = {"boot": boot, "seq": seq, "uptime_ms": seq * 2000 if uptime is None else uptime}
    if epoch is not None:
        m["epoch"] = epoch
    m.update(data)
    return m


def feed(core, payload):
    rec = core.parse(payload)
    assert rec is not None, payload
    return core.ingest(rec)


def test_parse_rejects_garbage():
    p = bc.BackfillCore.parse
    assert p("nope") is None
    assert p({"boot": 1, "seq": 1}) is None  # no uptime
    assert p({"boot": -1, "seq": 1, "uptime_ms": 5}) is None
    assert p({"boot": 1, "seq": 1.5, "uptime_ms": 5}) is None
    assert p({"boot": True, "seq": 1, "uptime_ms": 5}) is None
    ok = p({"boot": 1, "seq": 2, "uptime_ms": 5, "speed": 3.5, "light": True, "name": "x"})
    assert ok["data"] == {"speed": 3.5, "light": True}  # strings dropped


def test_resend_is_dropped_exactly_once():
    core = bc.BackfillCore()
    for seq in range(5):
        status, res = feed(core, msg(11, seq, epoch=T0 + seq * 2, speed=seq))
        assert status == "new" and len(res) == 1
    # ESP crashed before it got our ack and replays 3 and 4 (and 2 as well)
    for seq in (2, 3, 4):
        status, res = feed(core, msg(11, seq, epoch=T0 + seq * 2, speed=seq))
        assert status == "duplicate" and res == []
    status, res = feed(core, msg(11, 5, epoch=T0 + 10, speed=5))
    assert status == "new"


def test_gaps_from_ring_buffer_overwrite_are_fine():
    core = bc.BackfillCore()
    feed(core, msg(1, 0, epoch=T0))
    status, _ = feed(core, msg(1, 900, epoch=T0 + 1800))  # 1..899 were overwritten on the SD
    assert status == "new"
    assert feed(core, msg(1, 500, epoch=T0 + 1000))[0] == "duplicate"


def test_new_boot_is_independent_of_old_one():
    core = bc.BackfillCore()
    for seq in range(3):
        feed(core, msg(1, seq, epoch=T0 + seq))
    status, res = feed(core, msg(2, 0, epoch=T0 + 100))
    assert status == "new" and res[0]["boot"] == "2"
    # the old boot's backlog is still accepted afterwards
    assert feed(core, msg(1, 3, epoch=T0 + 3))[0] == "new"


def test_samples_without_clock_wait_then_get_real_timestamps():
    core = bc.BackfillCore()
    # ESP booted away from WiFi: no epoch yet for the first samples
    for seq in range(3):
        status, res = feed(core, msg(7, seq, uptime=10_000 + seq * 2000, speed=seq))
        assert status == "new" and res == []
    assert len(core.pending) == 3
    # clock syncs: sample 3 carries epoch, at uptime 16000
    status, res = feed(core, msg(7, 3, epoch=T0, uptime=16_000, speed=3))
    assert status == "new" and core.pending == []
    assert [r["seq"] for r in res] == [0, 1, 2, 3]
    assert [round(r["ts"]) for r in res] == [T0 - 6, T0 - 4, T0 - 2, T0]
    # after that, epoch-less samples of the same boot resolve immediately
    status, res = feed(core, msg(7, 4, uptime=18_000))
    assert round(res[0]["ts"]) == T0 + 2


def test_pending_of_other_boot_is_not_released():
    core = bc.BackfillCore()
    feed(core, msg(1, 0, uptime=1000))
    status, res = feed(core, msg(2, 0, epoch=T0, uptime=1000))
    assert [r["boot"] for r in res] == ["2"]
    assert len(core.pending) == 1 and core.pending[0]["boot"] == "1"


def test_state_roundtrip_keeps_dedupe_and_pending():
    core = bc.BackfillCore()
    feed(core, msg(3, 0, uptime=1000))  # pending
    feed(core, msg(4, 0, epoch=T0))
    restored = bc.BackfillCore(json.loads(json.dumps(core.state())))
    assert feed(restored, msg(4, 0, epoch=T0))[0] == "duplicate"
    assert feed(restored, msg(3, 0, uptime=1000))[0] == "duplicate"
    status, res = feed(restored, msg(3, 1, epoch=T0 + 50, uptime=3000))
    assert [r["seq"] for r in res] == [0, 1]  # pending survived the restart


def test_boot_memory_is_bounded():
    core = bc.BackfillCore()
    for boot in range(bc.MAX_BOOTS + 10):
        feed(core, msg(boot, 0, epoch=T0))
    assert len(core.hwm) == bc.MAX_BOOTS and len(core.offset) <= bc.MAX_BOOTS


def test_rawlog_append_dedupes_on_read_and_survives_torn_line():
    with tempfile.TemporaryDirectory() as d:
        raw = bc.RawLog(d)
        hour = bc.hour_of(T0)
        recs = [
            {"boot": "1", "seq": i, "ts": hour + i * 60.0, "data": {"speed": 10.0 + i}}
            for i in range(5)
        ]
        raw.append(recs)
        raw.append(recs[3:])  # crash-replayed duplicates in the file
        with open(Path(d) / next(n for n in __import__("os").listdir(d)), "a") as fh:
            fh.write('{"boot":"1","seq":99,"ts":')  # torn line from a power cut
        got = raw.read_hour(hour)
        assert [r["seq"] for r in got] == [0, 1, 2, 3, 4]
        ids = raw.recent_ids()
        assert ("1", 4) in ids


def test_rawlog_splits_days_and_reads_one_hour():
    with tempfile.TemporaryDirectory() as d:
        raw = bc.RawLog(d)
        day2 = (T0 // 86400 + 1) * 86400
        raw.append([
            {"boot": "1", "seq": 0, "ts": day2 - 10.0, "data": {"speed": 1.0}},
            {"boot": "1", "seq": 1, "ts": day2 + 10.0, "data": {"speed": 2.0}},
        ])
        assert len(list(Path(d).glob("*.jsonl"))) == 2
        assert [r["seq"] for r in raw.read_hour(bc.hour_of(day2 - 10))] == [0]
        assert [r["seq"] for r in raw.read_hour(bc.hour_of(day2 + 10))] == [1]


def test_prune_keeps_recent_days():
    with tempfile.TemporaryDirectory() as d:
        raw = bc.RawLog(d)
        now = 1_725_270_000.0
        raw.append([{"boot": "1", "seq": 0, "ts": now - 40 * 86400, "data": {}}])
        raw.append([{"boot": "1", "seq": 1, "ts": now - 1 * 86400, "data": {}}])
        assert raw.prune(0, now) == 0
        assert raw.prune(30, now) == 1
        assert len(list(Path(d).glob("*.jsonl"))) == 1


def test_aggregate_hour_ignores_booleans_and_missing():
    recs = [
        {"data": {"speed": 10.0, "light": True}},
        {"data": {"speed": 20.0}},
        {"data": {"cadence": 80.0}},
    ]
    agg = bc.aggregate_hour(recs, ["speed", "light", "cadence", "power"])
    assert agg["speed"] == {"mean": 15.0, "min": 10.0, "max": 20.0}
    assert agg["cadence"]["mean"] == 80.0
    assert "light" not in agg and "power" not in agg


def test_implausible_epoch_is_not_trusted_as_a_date():
    p = bc.BackfillCore.parse
    for bad in (1, 946684800, 4_294_967_295, 99_999_999_999):
        rec = p({"boot": 1, "seq": 1, "uptime_ms": 5, "epoch": bad})
        assert rec is not None and rec["epoch"] is None, bad
    good = p({"boot": 1, "seq": 1, "uptime_ms": 5, "epoch": T0})
    assert good["epoch"] == T0


def test_huge_or_non_finite_numbers_are_rejected():
    p = bc.BackfillCore.parse
    assert p({"boot": 2**40, "seq": 1, "uptime_ms": 5}) is None
    assert p({"boot": 1, "seq": float("inf"), "uptime_ms": 5}) is None
    assert p({"boot": 1, "seq": 1, "uptime_ms": float("nan")}) is None
    rec = p({"boot": 1, "seq": 1, "uptime_ms": 5, "speed": float("nan"), "ok": 1.5})
    assert rec["data"] == {"ok": 1.5}


def test_field_count_and_key_length_are_limited():
    p = bc.BackfillCore.parse
    payload = {"boot": 1, "seq": 1, "uptime_ms": 5}
    payload.update({f"k{i}": float(i) for i in range(100)})
    payload["x" * 200] = 1.0
    rec = p(payload)
    assert len(rec["data"]) <= bc.MAX_FIELDS
    assert all(len(k) <= bc.MAX_KEY_LEN for k in rec["data"])


def test_identify_works_even_when_the_rest_is_garbage():
    assert bc.BackfillCore.identify({"boot": 5, "seq": 9, "uptime_ms": "bad"}) == (5, 9)
    assert bc.BackfillCore.identify({"boot": 5}) is None
    assert bc.BackfillCore.identify("nope") is None


def test_poison_epoch_cannot_reach_the_file_writer():
    # A date that cannot be formatted must never get as far as RawLog.
    core = bc.BackfillCore()
    rec = core.parse({"boot": 1, "seq": 0, "uptime_ms": 1000, "epoch": 99_999_999_999})
    status, res = core.ingest(rec)
    assert status == "new" and res == []  # waits for a real clock instead of crashing
    with tempfile.TemporaryDirectory() as d:
        bc.RawLog(d).append(res)  # nothing to write, and no exception


def test_ack_mac_matches_a_reference_hmac():
    import hashlib
    import hmac

    expect = hmac.new(b"s3cret", b"123:45", hashlib.sha256).hexdigest()
    assert bc.ack_mac("s3cret", 123, 45) == expect
    assert len(expect) == 64 and expect == expect.lower()
    assert bc.ack_mac("other", 123, 45) != expect
    assert bc.ack_mac("s3cret", 123, 46) != expect
