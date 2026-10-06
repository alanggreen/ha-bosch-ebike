"""Ride data logger - ESPHome external component.

Writes every sensor / binary_sensor sample to an SD card ring buffer first,
sends it over MQTT, and only releases it once Home Assistant has acknowledged
storing it. After an outage (or a crash) the unacknowledged samples are
replayed oldest-first; every record has a unique (boot, seq) id so the
receiver can drop resends - nothing lost, nothing duplicated. Not
eBike-specific: point it at any sensors. Built for the mobile bridge
(example-bridge-mobile.yaml), where the phone hotspot / WireGuard tunnel /
MQTT broker chain can drop mid-ride. See RIDE_LOGGING.md.
"""
import esphome.codegen as cg
import esphome.config_validation as cv
from esphome import pins
from esphome.components import binary_sensor, sensor
from esphome.components import time as time_
from esphome.components.esp32 import include_builtin_idf_component, require_fatfs
from esphome.const import CONF_BINARY_SENSORS, CONF_ID, CONF_KEY, CONF_SENSORS, CONF_TIME_ID
from esphome.core import CORE

CODEOWNERS = ["@Xunil99"]
DEPENDENCIES = ["esp32", "mqtt"]
MULTI_CONF = False

ride_data_logger_ns = cg.esphome_ns.namespace("ride_data_logger")
RideDataLogger = ride_data_logger_ns.class_("RideDataLogger", cg.Component)

CONF_CS_PIN = "cs_pin"
CONF_MOSI_PIN = "mosi_pin"
CONF_MISO_PIN = "miso_pin"
CONF_CLK_PIN = "clk_pin"
CONF_SAMPLE_INTERVAL = "sample_interval"
CONF_REPLAY_TOPIC = "replay_topic"
CONF_ACK_TOPIC = "ack_topic"
CONF_STATUS_TOPIC = "status_topic"
CONF_MAX_REPLAY_PER_LOOP = "max_replay_per_loop"
CONF_MAX_UNACKED = "max_unacked"
CONF_ACK_TIMEOUT = "ack_timeout"
CONF_MAX_LOG_BYTES = "max_log_bytes"

# 512 records x 56 bytes - must match RideLogStore::SEGMENT_BYTES. The ring
# buffer needs room for at least 4 segments.
SEGMENT_BYTES = 512 * 56

# Must match MAX_SENSORS / MAX_BINARY_SENSORS in the fixed-size on-SD record
# struct (ride_log_store.h). Kept in sync manually since the
# record layout is written to disk and needs to stay simple to reason about.
MAX_SENSORS = 8
MAX_BINARY_SENSORS = 8

SENSOR_ENTRY_SCHEMA = cv.Schema(
    {
        cv.Required(CONF_ID): cv.use_id(sensor.Sensor),
        cv.Required(CONF_KEY): cv.string_strict,
    }
)

BINARY_SENSOR_ENTRY_SCHEMA = cv.Schema(
    {
        cv.Required(CONF_ID): cv.use_id(binary_sensor.BinarySensor),
        cv.Required(CONF_KEY): cv.string_strict,
    }
)


def _validate_counts(config):
    if len(config[CONF_SENSORS]) > MAX_SENSORS:
        raise cv.Invalid(f"ride_data_logger supports at most {MAX_SENSORS} entries under 'sensors'")
    if len(config[CONF_BINARY_SENSORS]) > MAX_BINARY_SENSORS:
        raise cv.Invalid(
            f"ride_data_logger supports at most {MAX_BINARY_SENSORS} entries under 'binary_sensors'"
        )
    return config


CONFIG_SCHEMA = cv.All(
    cv.Schema(
        {
            cv.GenerateID(): cv.declare_id(RideDataLogger),
            cv.Required(CONF_CS_PIN): pins.internal_gpio_output_pin_number,
            cv.Required(CONF_MOSI_PIN): pins.internal_gpio_output_pin_number,
            cv.Required(CONF_MISO_PIN): pins.internal_gpio_input_pin_number,
            cv.Required(CONF_CLK_PIN): pins.internal_gpio_output_pin_number,
            cv.Optional(CONF_SAMPLE_INTERVAL, default="2s"): cv.All(
                cv.positive_time_period_milliseconds,
                cv.Range(min=cv.TimePeriod(milliseconds=200)),
            ),
            cv.Optional(CONF_REPLAY_TOPIC, default=lambda: f"{CORE.name}/ride_log"): cv.publish_topic,
            # Defaults to <replay_topic>/ack and <replay_topic>/status.
            cv.Optional(CONF_ACK_TOPIC): cv.subscribe_topic,
            cv.Optional(CONF_STATUS_TOPIC): cv.publish_topic,
            cv.Optional(CONF_MAX_REPLAY_PER_LOOP, default=4): cv.int_range(min=1, max=50),
            # Records sent but not yet acknowledged; bounds the resend burst.
            cv.Optional(CONF_MAX_UNACKED, default=16): cv.int_range(min=1, max=256),
            # No ack for this long while records are in flight -> resend them.
            cv.Optional(CONF_ACK_TIMEOUT, default="10s"): cv.All(
                cv.positive_time_period_milliseconds,
                cv.Range(min=cv.TimePeriod(seconds=2)),
            ),
            # Ring-buffer budget on the card. When it is full the OLDEST
            # unacknowledged data is overwritten so recording never stops.
            cv.Optional(CONF_MAX_LOG_BYTES, default=16 * 1024 * 1024): cv.int_range(
                min=4 * SEGMENT_BYTES, max=1024 * 1024 * 1024
            ),
            # Optional: fills the epoch field of buffered/replayed records. Without
            # it (or before the first sync) records just carry epoch=0 and rely on
            # replay order + uptime_ms for sequencing.
            cv.Optional(CONF_TIME_ID): cv.use_id(time_.RealTimeClock),
            cv.Optional(CONF_SENSORS, default=[]): cv.ensure_list(SENSOR_ENTRY_SCHEMA),
            cv.Optional(CONF_BINARY_SENSORS, default=[]): cv.ensure_list(BINARY_SENSOR_ENTRY_SCHEMA),
        }
    ).extend(cv.COMPONENT_SCHEMA),
    _validate_counts,
)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)

    cg.add(
        var.set_pins(
            config[CONF_CS_PIN],
            config[CONF_MOSI_PIN],
            config[CONF_MISO_PIN],
            config[CONF_CLK_PIN],
        )
    )
    cg.add(var.set_sample_interval(config[CONF_SAMPLE_INTERVAL]))
    cg.add(var.set_replay_topic(config[CONF_REPLAY_TOPIC]))
    if ack_topic := config.get(CONF_ACK_TOPIC):
        cg.add(var.set_ack_topic(ack_topic))
    if status_topic := config.get(CONF_STATUS_TOPIC):
        cg.add(var.set_status_topic(status_topic))
    cg.add(var.set_max_replay_per_loop(config[CONF_MAX_REPLAY_PER_LOOP]))
    cg.add(var.set_max_unacked(config[CONF_MAX_UNACKED]))
    cg.add(var.set_ack_timeout(config[CONF_ACK_TIMEOUT]))
    cg.add(var.set_max_log_bytes(config[CONF_MAX_LOG_BYTES]))

    if time_id := config.get(CONF_TIME_ID):
        time_var = await cg.get_variable(time_id)
        cg.add(var.set_time(time_var))

    for entry in config[CONF_SENSORS]:
        sens = await cg.get_variable(entry[CONF_ID])
        cg.add(var.add_sensor(sens, entry[CONF_KEY]))

    for entry in config[CONF_BINARY_SENSORS]:
        bsens = await cg.get_variable(entry[CONF_ID])
        cg.add(var.add_binary_sensor(bsens, entry[CONF_KEY]))

    # FATFS is excluded from the build by default to save compile time (see
    # esp32/__init__.py DEFAULT_EXCLUDED_IDF_COMPONENTS). We mount a real FAT
    # filesystem on the SD card, so pull it back in.
    require_fatfs()
    include_builtin_idf_component("fatfs")
