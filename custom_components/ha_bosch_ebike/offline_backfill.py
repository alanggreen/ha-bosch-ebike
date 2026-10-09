"""Receive the ESP bridge's buffered ride samples and backfill Home Assistant.

Counterpart of the ESPHome ``ride_data_logger`` component (see
``esphome/RIDE_LOGGING.md``). For every sample received on the replay topic
this module, in this order:

1. drops it if its ``(boot, seq)`` id was already stored (a resend),
2. gives it its real timestamp (see ``backfill_core``),
3. appends it to a durable JSONL log under ``<config>/ha_bosch_ebike_ride_log/``,
4. saves the dedupe state, and only THEN
5. publishes ``{"boot", "seq"}`` on ``<topic>/ack`` so the ESP may delete it
   from its SD card.

Because the ESP keeps anything unacknowledged and resends it, and we drop
anything we already stored, the chain is exactly-once across outages,
reboots of either side and broker restarts. Hourly mean/min/max of the
numeric sensors are additionally imported into Home Assistant's long-term
statistics at the samples' *original* time, so history graphs show the
offline stretch.

Opt-in via ``configuration.yaml`` (see ``OFFLINE_BACKFILL_SCHEMA``)::

    ha_bosch_ebike:
      offline_backfill: {}
"""
from __future__ import annotations

import asyncio
import copy
import json
import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Any

import voluptuous as vol

from homeassistant.core import CALLBACK_TYPE, HomeAssistant, callback
from homeassistant.helpers import config_validation as cv
from homeassistant.helpers.event import async_track_time_interval
from homeassistant.helpers.storage import Store

from .backfill_core import BackfillCore, RawLog, aggregate_hour, ack_mac, hour_of

_LOGGER = logging.getLogger(__name__)

CONF_OFFLINE_BACKFILL = "offline_backfill"
CONF_TOPIC = "topic"
CONF_ACK_TOPIC = "ack_topic"
CONF_STATUS_TOPIC = "status_topic"
CONF_ENTITIES = "entities"
CONF_IMPORT_STATISTICS = "import_statistics"
CONF_KEEP_DAYS = "keep_days"
CONF_ACK_SECRET = "ack_secret"

# Matches ride_data_logger's default topic (<esphome node name>/ride_log) for
# example-bridge-mobile.yaml. Override `topic` if your node is named differently.
DEFAULT_TOPIC = "ebike-bridge-mobile/ride_log"

# sample key (as listed under ride_data_logger.sensors in the ESP YAML) ->
# (entity id of the MQTT-discovered sensor, fallback unit). Only numeric
# "measurement" sensors belong here: the odometer is a running total, so
# writing mean/min/max statistics for it would corrupt its sum history. It
# (and all binary flags) is still kept in the raw log.
DEFAULT_ENTITIES: dict[str, tuple[str, str]] = {
    "speed": ("sensor.ebike_speed", "km/h"),
    "cadence": ("sensor.ebike_cadence", "rpm"),
    "rider_power": ("sensor.ebike_rider_power", "W"),
    "ambient_brightness": ("sensor.ebike_ambient_brightness", "lx"),
    "battery_soc": ("sensor.ebike_battery_soc_live", "%"),
}

STORE_VERSION = 1
STORE_KEY = "ha_bosch_ebike_offline_backfill"
RAW_DIR = "ha_bosch_ebike_ride_log"
ACK_DELAY_S = 0.5  # coalesce acks of a burst into one
STATS_INTERVAL = timedelta(minutes=5)
STATS_GRACE_S = 120  # import an hour only this long after it ended

OFFLINE_BACKFILL_SCHEMA = vol.Schema(
    {
        vol.Optional(CONF_TOPIC, default=DEFAULT_TOPIC): cv.string,
        vol.Optional(CONF_ACK_TOPIC): cv.string,
        vol.Optional(CONF_STATUS_TOPIC): cv.string,
        # key -> entity id, to override/extend the default sensor mapping.
        vol.Optional(CONF_ENTITIES, default={}): {cv.string: cv.entity_id},
        vol.Optional(CONF_IMPORT_STATISTICS, default=True): cv.boolean,
        # Delete raw day files older than this many days (0 = keep forever).
        vol.Optional(CONF_KEEP_DAYS, default=180): vol.All(vol.Coerce(int), vol.Range(min=0)),
        # Shared secret that signs the acks sent to the ESP, so a stranger on the
        # broker cannot make it delete unsent data. Must equal `ack_secret` in the
        # ESP's ride_data_logger; set it on BOTH sides or neither.
        vol.Optional(CONF_ACK_SECRET): cv.string,
    }
)


class OfflineBackfill:
    """Owns the MQTT subscriptions, the persistence and the ack publishing."""

    def __init__(self, hass: HomeAssistant, conf: dict[str, Any]) -> None:
        self.hass = hass
        self.topic: str = conf[CONF_TOPIC]
        self.ack_topic: str = conf.get(CONF_ACK_TOPIC) or f"{self.topic}/ack"
        self.status_topic: str = conf.get(CONF_STATUS_TOPIC) or f"{self.topic}/status"
        self.import_statistics: bool = conf[CONF_IMPORT_STATISTICS]
        self.keep_days: int = conf[CONF_KEEP_DAYS]
        self.ack_secret: str | None = conf.get(CONF_ACK_SECRET)
        self.entities: dict[str, tuple[str, str | None]] = dict(DEFAULT_ENTITIES)
        for key, entity_id in conf[CONF_ENTITIES].items():
            self.entities[key] = (entity_id, self.entities.get(key, (None, None))[1])

        self._store: Store = Store(hass, STORE_VERSION, STORE_KEY)
        self._raw = RawLog(hass.config.path(RAW_DIR))
        self._core = BackfillCore()
        self._dirty_hours: set[int] = set()
        self._queue: asyncio.Queue[Any] = asyncio.Queue(maxsize=5000)
        self._worker: asyncio.Task | None = None
        self._unsubs: list[CALLBACK_TYPE] = []
        self._ack_handle: asyncio.TimerHandle | None = None
        self._ack_id: tuple[int, int] | None = None
        self._last_dropped = 0
        self.last_status: dict[str, Any] = {}

    # ------------------------------------------------------------- lifecycle
    async def async_start(self) -> None:
        from homeassistant.components import mqtt

        try:
            available = await mqtt.async_wait_for_mqtt_client(self.hass)
        except Exception:  # noqa: BLE001 - older cores / not configured
            available = False
        if not available:
            _LOGGER.warning(
                "offline_backfill is configured but the MQTT integration is not available; "
                "buffered ride data will stay on the ESP's SD card until it is"
            )
            return

        data = await self._store.async_load() or {}
        self._core = BackfillCore(data.get("core"))
        self._dirty_hours = {int(h) for h in data.get("dirty_hours", [])}

        # Crash recovery: records that reached the log but not the saved state.
        for boot, seq in await self.hass.async_add_executor_job(self._raw.recent_ids):
            self._core.note_stored(boot, seq)
        if self.keep_days:
            await self.hass.async_add_executor_job(self._raw.prune, self.keep_days, time.time())

        self._worker = self.hass.async_create_background_task(
            self._run_worker(), "ha_bosch_ebike offline backfill"
        )
        self._unsubs.append(await mqtt.async_subscribe(self.hass, self.topic, self._on_message, 1))
        self._unsubs.append(
            await mqtt.async_subscribe(self.hass, self.status_topic, self._on_status, 0)
        )
        if self.import_statistics:
            self._unsubs.append(
                async_track_time_interval(self.hass, self._async_import_due, STATS_INTERVAL)
            )
        _LOGGER.info(
            "Offline ride backfill listening on %s (acks on %s)", self.topic, self.ack_topic
        )

    async def async_stop(self, *_: Any) -> None:
        for unsub in self._unsubs:
            unsub()
        self._unsubs.clear()
        if self._ack_handle:
            self._ack_handle.cancel()
        if self._worker:
            self._worker.cancel()
            self._worker = None
        await self._store.async_save(self._snapshot())

    def _snapshot(self) -> dict[str, Any]:
        return {"core": self._core.state(), "dirty_hours": sorted(self._dirty_hours)}

    # ------------------------------------------------------------------- MQTT
    async def _on_message(self, msg: Any) -> None:
        try:
            payload = json.loads(msg.payload)
        except (TypeError, ValueError):
            _LOGGER.warning("Ignoring non-JSON ride sample on %s", msg.topic)
            return
        try:
            self._queue.put_nowait(payload)
        except asyncio.QueueFull:
            # Not acknowledged -> the ESP keeps it on the card and resends.
            _LOGGER.warning("Ride backfill queue full; sample will be resent by the bridge")

    @callback
    def _on_status(self, msg: Any) -> None:
        try:
            status = json.loads(msg.payload)
        except (TypeError, ValueError):
            return
        if not isinstance(status, dict):
            return
        self.last_status = status
        dropped = status.get("dropped")
        if isinstance(dropped, int) and dropped > self._last_dropped:
            _LOGGER.warning(
                "The eBike bridge's SD ring buffer overflowed: %d unsent sample(s) were "
                "overwritten while offline. Use a larger card/max_log_bytes or reconnect sooner.",
                dropped,
            )
        if isinstance(dropped, int):
            self._last_dropped = dropped

    # ----------------------------------------------------------------- worker
    async def _run_worker(self) -> None:
        while True:
            batch = [await self._queue.get()]
            while not self._queue.empty():
                batch.append(self._queue.get_nowait())
            try:
                await self._process(batch)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001
                # No ack is sent for this batch, so the ESP resends it.
                _LOGGER.exception("Failed to store ride samples; the bridge will resend them")

    async def _process(self, batch: list[Any]) -> None:
        # ingest() advances the in-memory duplicate filter. If storing then fails
        # (disk full, I/O error), roll that back, otherwise the resend would be
        # dropped as a "duplicate" of something that was never saved.
        before = copy.deepcopy(self._core.state())
        try:
            await self._process_batch(batch)
        except BaseException:
            self._core = BackfillCore(before)
            raise

    async def _process_batch(self, batch: list[Any]) -> None:
        resolved: list[dict[str, Any]] = []
        last_id: tuple[int, int] | None = None
        for payload in batch:
            rec = self._core.parse(payload)
            if rec is None:
                ident = self._core.identify(payload)
                _LOGGER.warning("Discarding malformed ride sample: %.200s", payload)
                if ident is not None:
                    last_id = ident  # ack it so the ESP does not resend it forever
                continue
            _, out = self._core.ingest(rec)
            resolved.extend(out)
            last_id = (int(rec["boot"]), rec["seq"])

        if resolved:
            await self.hass.async_add_executor_job(self._raw.append, resolved)
            self._dirty_hours.update(hour_of(r["ts"]) for r in resolved)
        if resolved or self._core.dirty:
            # Durable BEFORE the ack: a crash here means resend + dedupe, never loss.
            await self._store.async_save(self._snapshot())
            self._core.dirty = False
        if last_id is not None:
            self._schedule_ack(last_id)

    # -------------------------------------------------------------------- ack
    def _schedule_ack(self, ident: tuple[int, int]) -> None:
        self._ack_id = ident
        if self._ack_handle is None:
            self._ack_handle = self.hass.loop.call_later(ACK_DELAY_S, self._fire_ack)

    def _fire_ack(self) -> None:
        self._ack_handle = None
        ident, self._ack_id = self._ack_id, None
        if ident is not None:
            self.hass.async_create_task(self._async_publish_ack(ident))

    async def _async_publish_ack(self, ident: tuple[int, int]) -> None:
        from homeassistant.components import mqtt

        body: dict[str, Any] = {"boot": ident[0], "seq": ident[1]}
        if self.ack_secret:
            body["mac"] = ack_mac(self.ack_secret, ident[0], ident[1])
        payload = json.dumps(body, separators=(",", ":"))
        try:
            await mqtt.async_publish(self.hass, self.ack_topic, payload, 1, False)
        except Exception:  # noqa: BLE001 - unacked data is simply resent
            _LOGGER.warning("Could not publish ride-log ack; the bridge will resend", exc_info=True)

    # ------------------------------------------------------------- statistics
    async def _async_import_due(self, _now: datetime | None = None) -> None:
        cutoff = time.time() - 3600 - STATS_GRACE_S
        due = sorted(h for h in self._dirty_hours if h <= cutoff)
        if not due:
            return
        try:
            from homeassistant.components.recorder import get_instance  # noqa: F401
        except ImportError:
            return
        for hour in due:
            try:
                records = await self.hass.async_add_executor_job(self._raw.read_hour, hour)
                self._import_hour(hour, records)
            except Exception:  # noqa: BLE001 - stays dirty, retried next interval
                _LOGGER.warning("Statistics import for hour %s failed", hour, exc_info=True)
                continue
            self._dirty_hours.discard(hour)
        await self._store.async_save(self._snapshot())

    def _import_hour(self, hour: int, records: list[dict[str, Any]]) -> None:
        from homeassistant.components.recorder.models import StatisticData, StatisticMetaData
        from homeassistant.components.recorder.statistics import async_import_statistics

        agg = aggregate_hour(records, list(self.entities))
        start = datetime.fromtimestamp(hour, tz=timezone.utc)
        meta_fields = getattr(StatisticMetaData, "__annotations__", {})
        for key, values in agg.items():
            entity_id, default_unit = self.entities[key]
            state = self.hass.states.get(entity_id)
            unit = (state.attributes.get("unit_of_measurement") if state else None) or default_unit
            meta: dict[str, Any] = {
                "source": "recorder",
                "statistic_id": entity_id,
                "name": None,
                "unit_of_measurement": unit,
                "has_sum": False,
            }
            # StatisticMetaData changed shape across HA releases; fill what exists.
            if "mean_type" in meta_fields:
                from homeassistant.components.recorder.models import StatisticMeanType

                meta["mean_type"] = StatisticMeanType.ARITHMETIC
            if "has_mean" in meta_fields:
                meta["has_mean"] = True
            if "unit_class" in meta_fields:
                meta["unit_class"] = None
            stat: StatisticData = {
                "start": start,
                "mean": values["mean"],
                "min": values["min"],
                "max": values["max"],
            }
            async_import_statistics(self.hass, meta, [stat])  # type: ignore[arg-type]


async def async_setup_offline_backfill(hass: HomeAssistant, conf: dict[str, Any]) -> OfflineBackfill:
    """Create and start the backfill; stop it with Home Assistant."""
    from homeassistant.const import EVENT_HOMEASSISTANT_STOP
    from homeassistant.helpers.start import async_at_started

    backfill = OfflineBackfill(hass, conf)

    async def _start(_hass: HomeAssistant) -> None:
        await backfill.async_start()

    async_at_started(hass, _start)
    hass.bus.async_listen_once(EVENT_HOMEASSISTANT_STOP, backfill.async_stop)
    return backfill
