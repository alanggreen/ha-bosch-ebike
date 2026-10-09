"""Pure logic for the offline ride-data backfill (no Home Assistant imports).

The ESPHome ``ride_data_logger`` component writes every sample to an SD card
and sends it over MQTT as::

    {"boot": 123, "seq": 7, "epoch": 1725270031, "uptime_ms": 88123,
     "speed": 24.5, "connected": true, ...}

``(boot, seq)`` is unique for all time and strictly increasing within a boot,
so ``BackfillCore`` can make delivery exactly-once on this side: anything at
or below the highest ``seq`` already stored for its ``boot`` is a resend and is
dropped. Samples sent before the ESP's clock had synced have no ``epoch``;
they are held back until a later sample of the same boot reveals the clock
offset, then get their real timestamp.

Persistence and I/O live in ``offline_backfill.py``; keeping this module free of
HA lets ``tests/test_backfill_core.py`` run it standalone.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import math
import os
from datetime import datetime, timezone
from typing import Any

MAX_BOOTS = 64  # remembered boot ids; older ones are forgotten (FIFO)
MAX_PENDING = 50000  # samples held while no clock offset is known
RESERVED_FIELDS = {"boot", "seq", "epoch", "uptime_ms"}

# Input limits: the MQTT topic is writable by anyone with a broker login, so
# nothing in a payload is trusted.
MIN_EPOCH = 1577836800  # 2020-01-01: anything earlier is a clock that never synced
MAX_EPOCH = 4102444800  # 2100-01-01
MAX_UINT32 = 2**32 - 1
MAX_FIELDS = 32  # sensor values per record
MAX_KEY_LEN = 64


def ack_mac(secret: str, boot: int, seq: int) -> str:
    """HMAC-SHA256 (hex) that authenticates an ack for record (boot, seq).

    Must match RideDataLogger::ack_valid_ in the ESP firmware: the message is the
    decimal text boot:seq, the key is the shared secret, the output is 64
    lower-case hex characters.
    """
    return hmac.new(secret.encode(), f"{boot}:{seq}".encode(), hashlib.sha256).hexdigest()


def _as_uint(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if value < 0 or value != int(value) or value > MAX_UINT32:
        return None
    return int(value)


class BackfillCore:
    """Exactly-once ingestion state: dedupe watermark, clock offsets, pending."""

    def __init__(self, state: dict[str, Any] | None = None) -> None:
        state = state if isinstance(state, dict) else {}
        # boot id (str) -> highest seq stored. dict order = insertion order.
        self.hwm: dict[str, int] = {
            str(k): int(v) for k, v in (state.get("hwm") or {}).items()
        }
        # boot id (str) -> (epoch - uptime_seconds): maps uptime to wall clock.
        self.offset: dict[str, float] = {
            str(k): float(v) for k, v in (state.get("offset") or {}).items()
        }
        # Samples seen (and acknowledged!) but not yet timestamped.
        self.pending: list[dict[str, Any]] = list(state.get("pending") or [])
        self.dirty = False

    # ------------------------------------------------------------------ state
    def state(self) -> dict[str, Any]:
        return {"hwm": self.hwm, "offset": self.offset, "pending": self.pending}

    def _touch_boot(self, boot: str) -> None:
        # Re-insert so the most recently active boot is last in FIFO order.
        value = self.hwm.pop(boot, -1)
        self.hwm[boot] = value
        while len(self.hwm) > MAX_BOOTS:
            old = next(iter(self.hwm))
            self.hwm.pop(old)
            self.offset.pop(old, None)

    # ----------------------------------------------------------------- ingest
    @staticmethod
    def parse(payload: Any) -> dict[str, Any] | None:
        """Validate a raw MQTT JSON payload. None = unusable."""
        if not isinstance(payload, dict):
            return None
        boot = _as_uint(payload.get("boot"))
        seq = _as_uint(payload.get("seq"))
        uptime = _as_uint(payload.get("uptime_ms"))
        if boot is None or seq is None or uptime is None:
            return None
        epoch = _as_uint(payload.get("epoch"))
        if epoch is not None and not MIN_EPOCH <= epoch <= MAX_EPOCH:
            epoch = None  # implausible clock: treat as "no clock yet", never as a date
        data = {}
        for k, v in payload.items():
            if len(data) >= MAX_FIELDS:
                break
            if k in RESERVED_FIELDS or not isinstance(k, str) or not 0 < len(k) <= MAX_KEY_LEN:
                continue
            if isinstance(v, bool) or (isinstance(v, (int, float)) and math.isfinite(v)):
                data[k] = v
        return {
            "boot": str(boot),
            "seq": seq,
            "uptime_ms": uptime,
            "epoch": epoch or None,
            "data": data,
        }

    @staticmethod
    def identify(payload: Any) -> tuple[int, int] | None:
        """(boot, seq) of a payload even when the rest of it is unusable.

        Lets the receiver acknowledge (and thereby drop) a record it cannot
        store, so one bad record cannot block the sender's queue forever.
        """
        if not isinstance(payload, dict):
            return None
        boot, seq = _as_uint(payload.get("boot")), _as_uint(payload.get("seq"))
        return (boot, seq) if boot is not None and seq is not None else None

    def ingest(self, rec: dict[str, Any]) -> tuple[str, list[dict[str, Any]]]:
        """Feed one parsed record (see ``parse``).

        Returns ``(status, resolved)``: ``status`` is ``"duplicate"`` or
        ``"new"``; ``resolved`` are timestamped records (``ts`` = unix
        seconds) that must now be persisted - possibly including older samples
        of the same boot released by this one.
        """
        boot, seq = rec["boot"], rec["seq"]
        if seq <= self.hwm.get(boot, -1):
            return "duplicate", []

        self._touch_boot(boot)
        self.hwm[boot] = seq
        self.dirty = True

        resolved: list[dict[str, Any]] = []
        if rec["epoch"] is not None:
            self.offset[boot] = rec["epoch"] - rec["uptime_ms"] / 1000.0
            # This sample proves the clock; release everything that waited for it.
            still: list[dict[str, Any]] = []
            for p in self.pending:
                if p["boot"] == boot:
                    resolved.append(self._timestamp(p, self.offset[boot]))
                else:
                    still.append(p)
            self.pending = still
            resolved.append(
                {
                    "boot": boot,
                    "seq": seq,
                    "ts": float(rec["epoch"]),
                    "data": rec["data"],
                }
            )
        elif boot in self.offset:
            resolved.append(self._timestamp(rec, self.offset[boot]))
        else:
            if len(self.pending) >= MAX_PENDING:
                self.pending.pop(0)  # bounded memory; oldest unknowable sample goes
            self.pending.append(rec)
        resolved.sort(key=lambda r: (r["ts"], r["seq"]))
        return "new", resolved

    @staticmethod
    def _timestamp(rec: dict[str, Any], offset: float) -> dict[str, Any]:
        return {
            "boot": rec["boot"],
            "seq": rec["seq"],
            "ts": rec["uptime_ms"] / 1000.0 + offset,
            "data": rec["data"],
        }

    # --------------------------------------------------------------- recovery
    def note_stored(self, boot: str, seq: int) -> None:
        """Raise the watermark for a record found already on disk (crash recovery)."""
        if seq > self.hwm.get(boot, -1):
            self._touch_boot(boot)
            self.hwm[boot] = seq
            self.dirty = True


class RawLog:
    """Append-only JSONL store, one file per UTC day: ``YYYY-MM-DD.jsonl``.

    This is the durable record of every backfilled sample. Hourly statistics
    are recomputed from it, and reads de-duplicate by ``(boot, seq)`` so even a
    crash between "file written" and "state saved" cannot skew a statistic.
    """

    TAIL_BYTES = 256 * 1024

    def __init__(self, directory: str) -> None:
        self.directory = directory

    def _path_for_ts(self, ts: float) -> str:
        day = datetime.fromtimestamp(ts, tz=timezone.utc).strftime("%Y-%m-%d")
        return os.path.join(self.directory, f"{day}.jsonl")

    def append(self, records: list[dict[str, Any]]) -> None:
        if not records:
            return
        os.makedirs(self.directory, exist_ok=True)
        by_file: dict[str, list[str]] = {}
        for r in records:
            line = json.dumps(r, separators=(",", ":"), sort_keys=True)
            by_file.setdefault(self._path_for_ts(r["ts"]), []).append(line)
        for path, lines in by_file.items():
            with open(path, "a", encoding="utf-8") as fh:
                fh.write("\n".join(lines) + "\n")
                fh.flush()
                os.fsync(fh.fileno())

    @staticmethod
    def _iter_lines(path: str):
        try:
            with open(path, encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        yield json.loads(line)
                    except ValueError:
                        continue  # torn last line after a crash
        except FileNotFoundError:
            return

    def read_hour(self, hour_start: float) -> list[dict[str, Any]]:
        """All unique samples with ``hour_start <= ts < hour_start + 3600``."""
        seen: set[tuple[str, int]] = set()
        out: list[dict[str, Any]] = []
        for r in self._iter_lines(self._path_for_ts(hour_start)):
            if not hour_start <= r.get("ts", -1) < hour_start + 3600:
                continue
            ident = (r["boot"], r["seq"])
            if ident in seen:
                continue
            seen.add(ident)
            out.append(r)
        return out

    def recent_ids(self) -> list[tuple[str, int]]:
        """Ids found in the tail of the two newest files (crash recovery)."""
        try:
            names = sorted(n for n in os.listdir(self.directory) if n.endswith(".jsonl"))
        except FileNotFoundError:
            return []
        ids: list[tuple[str, int]] = []
        for name in names[-2:]:
            path = os.path.join(self.directory, name)
            with open(path, "rb") as fh:
                fh.seek(0, os.SEEK_END)
                size = fh.tell()
                fh.seek(max(0, size - self.TAIL_BYTES))
                chunk = fh.read().decode("utf-8", errors="ignore")
            lines = chunk.split("\n")
            if size > self.TAIL_BYTES:
                lines = lines[1:]  # first line is probably cut in half
            for line in lines:
                try:
                    r = json.loads(line)
                    ids.append((r["boot"], int(r["seq"])))
                except (ValueError, KeyError, TypeError):
                    continue
        return ids

    def prune(self, keep_days: int, now: float) -> int:
        """Delete day files older than ``keep_days`` (0 = keep forever)."""
        if keep_days <= 0:
            return 0
        cutoff = datetime.fromtimestamp(now - keep_days * 86400, tz=timezone.utc).strftime(
            "%Y-%m-%d"
        )
        removed = 0
        try:
            names = os.listdir(self.directory)
        except FileNotFoundError:
            return 0
        for name in names:
            if name.endswith(".jsonl") and name[:-6] < cutoff:
                os.remove(os.path.join(self.directory, name))
                removed += 1
        return removed


def hour_of(ts: float) -> int:
    """Start of the UTC hour containing ``ts`` (unix seconds)."""
    return int(ts // 3600) * 3600


def aggregate_hour(
    records: list[dict[str, Any]], keys: list[str]
) -> dict[str, dict[str, float]]:
    """Mean/min/max per numeric key over one hour of samples."""
    out: dict[str, dict[str, float]] = {}
    for key in keys:
        vals = [
            float(r["data"][key])
            for r in records
            if key in r["data"] and not isinstance(r["data"][key], bool)
        ]
        if vals:
            out[key] = {"mean": sum(vals) / len(vals), "min": min(vals), "max": max(vals)}
    return out
