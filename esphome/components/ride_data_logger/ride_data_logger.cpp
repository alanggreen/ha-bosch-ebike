#include "esphome/core/defines.h"

#ifdef USE_ESP32
#ifdef USE_MQTT

#include "ride_data_logger.h"
#include "esphome/core/log.h"

#include "esp_random.h"
#include "esp_vfs_fat.h"
#include "driver/sdspi_host.h"
#include "driver/spi_common.h"

#include <cinttypes>
#include <cmath>
#include <cstring>

namespace esphome {
namespace ride_data_logger {

static const char *const TAG = "ride_data_logger";

static const char *const MOUNT_POINT = "/sdcard";
static const char *const LOG_DIR = "/sdcard/rlog";  // 8.3 name: FAT without LFN

void RideDataLogger::add_sensor(sensor::Sensor *s, const std::string &key) {
  if (sensor_count_ >= MAX_SENSORS) {
    ESP_LOGE(TAG, "Dropping sensor '%s': at most %u sensors are supported", key.c_str(), (unsigned) MAX_SENSORS);
    return;
  }
  sensors_[sensor_count_] = s;
  sensor_keys_[sensor_count_] = key;
  sensor_count_++;
}

void RideDataLogger::add_binary_sensor(binary_sensor::BinarySensor *s, const std::string &key) {
  if (binary_sensor_count_ >= MAX_BINARY_SENSORS) {
    ESP_LOGE(TAG, "Dropping binary_sensor '%s': at most %u binary_sensors are supported", key.c_str(),
             (unsigned) MAX_BINARY_SENSORS);
    return;
  }
  binary_sensors_[binary_sensor_count_] = s;
  binary_sensor_keys_[binary_sensor_count_] = key;
  binary_sensor_count_++;
}

// ---- SD card mounting -------------------------------------------------------
// Raw ESP-IDF SDSPI + FATFS. ESPHome has no core `sd_card:` component to lean
// on, and this only ever needs plain file I/O, so we own the SPI bus directly -
// the same reasoning bosch_ebike_ldi uses for talking to NimBLE directly.
bool RideDataLogger::mount_sd_() {
  spi_bus_config_t bus_cfg = {};
  bus_cfg.mosi_io_num = mosi_pin_;
  bus_cfg.miso_io_num = miso_pin_;
  bus_cfg.sclk_io_num = clk_pin_;
  bus_cfg.quadwp_io_num = -1;
  bus_cfg.quadhd_io_num = -1;
  bus_cfg.max_transfer_sz = 4000;

  sdmmc_host_t host = SDSPI_HOST_DEFAULT();

  esp_err_t err = spi_bus_initialize((spi_host_device_t) host.slot, &bus_cfg, SDSPI_DEFAULT_DMA);
  if (err != ESP_OK) {
    ESP_LOGE(TAG, "spi_bus_initialize failed: %s", esp_err_to_name(err));
    return false;
  }

  sdspi_device_config_t slot_config = SDSPI_DEVICE_CONFIG_DEFAULT();
  slot_config.gpio_cs = (gpio_num_t) cs_pin_;
  slot_config.host_id = (spi_host_device_t) host.slot;

  esp_vfs_fat_sdmmc_mount_config_t mount_config = {};
  mount_config.format_if_mount_failed = false;
  mount_config.max_files = 3;
  mount_config.allocation_unit_size = 16 * 1024;

  err = esp_vfs_fat_sdspi_mount(MOUNT_POINT, &host, &slot_config, &mount_config, &card_);
  if (err != ESP_OK) {
    if (err == ESP_FAIL) {
      ESP_LOGE(TAG, "Failed to mount the SD card filesystem - is it formatted as FAT32?");
    } else {
      ESP_LOGE(TAG, "Failed to initialize the SD card (0x%x): %s", err, esp_err_to_name(err));
    }
    spi_bus_free((spi_host_device_t) host.slot);
    return false;
  }

  ESP_LOGI(TAG, "SD card mounted (%s, %" PRIu64 " MB)", card_->cid.name,
           ((uint64_t) card_->csd.capacity) * card_->csd.sector_size / (1024 * 1024));
  return true;
}

// ---- Lifecycle ---------------------------------------------------------------

void RideDataLogger::setup() {
  boot_id_ = esp_random();
  if (ack_topic_.empty())
    ack_topic_ = replay_topic_ + "/ack";
  if (status_topic_.empty())
    status_topic_ = replay_topic_ + "/status";

  sd_mounted_ = mount_sd_();
  if (sd_mounted_) {
    store_ = new RideLogStore(LOG_DIR, max_log_bytes_);
    if (!store_->open()) {
      ESP_LOGW(TAG, "Could not create the folder %s (errno %d: %s) - trying the card's root folder instead", LOG_DIR,
               store_->last_errno(), strerror(store_->last_errno()));
      delete store_;
      // Segment files are 8.3 named, so they can live in the root of the card too.
      store_ = new RideLogStore(MOUNT_POINT, max_log_bytes_);
      if (!store_->open()) {
        ESP_LOGE(TAG, "Could not use the SD card for the ride log (errno %d: %s) - is it write protected or read-only?",
                 store_->last_errno(), strerror(store_->last_errno()));
        delete store_;
        store_ = nullptr;
        sd_mounted_ = false;
      }
    }
  }
  if (!sd_mounted_) {
    ESP_LOGE(TAG, "Offline buffering is DISABLED: no usable SD card. Samples are only published live (QoS1, no "
                  "acknowledgement tracking) while connected; nothing survives an outage.");
  } else {
    ESP_LOGI(TAG, "Ride log ready: %" PRIu32 " unacknowledged record(s) from earlier, boot id %08" PRIx32,
             store_->unacked_records(), boot_id_);
    // Re-subscribed automatically after every reconnect.
    this->subscribe_json(ack_topic_, &RideDataLogger::on_ack_, 1);
  }
}

void RideDataLogger::loop() {
  const uint32_t now = millis();
  if (now - last_sample_ms_ >= sample_interval_ms_) {
    last_sample_ms_ = now;
    take_sample_();
  }
  if (store_ != nullptr)
    service_send_();
}

void RideDataLogger::dump_config() {
  ESP_LOGCONFIG(TAG, "Ride Data Logger:");
  ESP_LOGCONFIG(TAG, "  SD card: %s", sd_mounted_ ? "mounted" : "NOT MOUNTED (offline buffering disabled)");
  ESP_LOGCONFIG(TAG, "  Pins: CS=%" PRId32 " MOSI=%" PRId32 " MISO=%" PRId32 " CLK=%" PRId32, cs_pin_, mosi_pin_,
                miso_pin_, clk_pin_);
  ESP_LOGCONFIG(TAG, "  Sample interval: %" PRIu32 " ms", sample_interval_ms_);
  ESP_LOGCONFIG(TAG, "  Replay topic: %s", replay_topic_.c_str());
  ESP_LOGCONFIG(TAG, "  Ack topic: %s", ack_topic_.c_str());
  ESP_LOGCONFIG(TAG, "  Status topic: %s", status_topic_.c_str());
  ESP_LOGCONFIG(TAG, "  Max sends/loop: %u, max unacked in flight: %u, ack timeout: %" PRIu32 " ms",
                max_replay_per_loop_, max_unacked_, ack_timeout_ms_);
  ESP_LOGCONFIG(TAG, "  Ring buffer size: %" PRIu32 " bytes", max_log_bytes_);
  ESP_LOGCONFIG(TAG, "  Sensors: %u, binary_sensors: %u", sensor_count_, binary_sensor_count_);
}

uint32_t RideDataLogger::current_epoch_() {
  if (time_ == nullptr)
    return 0;
  auto t = time_->now();
  return t.is_valid() ? (uint32_t) t.timestamp : 0;
}

// ---- Sampling ------------------------------------------------------------

void RideDataLogger::take_sample_() {
  RideRecord rec{};
  rec.boot = boot_id_;
  rec.seq = next_seq_++;
  rec.epoch = current_epoch_();
  rec.uptime_ms = millis();
  for (uint8_t i = 0; i < MAX_SENSORS; i++) {
    if (i < sensor_count_ && sensors_[i] != nullptr && sensors_[i]->has_state()) {
      rec.values[i] = sensors_[i]->state;
    } else {
      rec.values[i] = NAN;
    }
  }
  for (uint8_t i = 0; i < binary_sensor_count_; i++) {
    if (binary_sensors_[i] != nullptr && binary_sensors_[i]->has_state()) {
      rec.binary_known |= (1 << i);
      if (binary_sensors_[i]->state)
        rec.binary_state |= (1 << i);
    }
  }

  if (store_ == nullptr) {
    // No card: best effort, live only. Sequence numbers still increase, so
    // the receiver's duplicate filter keeps working.
    if (this->is_connected())
      publish_record_(rec);
    return;
  }

  // Always persist first, online or not. Sending is a separate step
  // (service_send_) that only reads from the card, so a live sample can never
  // overtake older buffered ones, and a crash right after this line cannot
  // lose the sample.
  if (!store_->append(rec)) {
    // Do NOT publish it directly: it would arrive ahead of older, still
    // buffered records and the receiver would then discard those as stale.
    write_failures_++;
    static uint32_t last_warn = 0;
    if (millis() - last_warn > 30000) {
      last_warn = millis();
      ESP_LOGW(TAG, "SD write failed (%" PRIu32 " sample(s) lost so far) - card removed or full of errors?",
               write_failures_);
    }
  }
}

// ---- Acknowledged replay -------------------------------------------------

bool RideDataLogger::publish_record_(const RideRecord &rec) {
  return this->publish_json(
      replay_topic_,
      [this, &rec](JsonObject root) {
        root["boot"] = rec.boot;
        root["seq"] = rec.seq;
        if (rec.epoch != 0)
          root["epoch"] = rec.epoch;
        root["uptime_ms"] = rec.uptime_ms;
        for (uint8_t i = 0; i < sensor_count_; i++) {
          if (!std::isnan(rec.values[i]))
            root[sensor_keys_[i].c_str()] = rec.values[i];
        }
        for (uint8_t i = 0; i < binary_sensor_count_; i++) {
          if (rec.binary_known & (1 << i))
            root[binary_sensor_keys_[i].c_str()] = (bool) (rec.binary_state & (1 << i));
        }
      },
      1, false);
}

void RideDataLogger::service_send_() {
  const uint32_t now = millis();
  const bool connected = this->is_connected();

  // Any transition invalidates what we believe is "in flight": QoS1 messages
  // and acks may have been lost with the old session. Resending from the last
  // acknowledged position is always safe - the receiver drops ids it has.
  if (connected != was_connected_) {
    was_connected_ = connected;
    store_->rewind_to_acked();
    window_limit_ = 1;
    last_progress_ms_ = now;
    if (connected)
      ESP_LOGI(TAG, "MQTT up - %" PRIu32 " record(s) waiting to be acknowledged", store_->unacked_records());
  }
  if (!connected)
    return;

  // Acks stopped coming (receiver down/slow, lost message): go back and resend.
  if (store_->in_flight() > 0 && now - last_progress_ms_ > ack_timeout_ms_) {
    ESP_LOGW(TAG, "No acknowledgement for %" PRIu32 " ms - resending %u in-flight record(s)", ack_timeout_ms_,
             (unsigned) store_->in_flight());
    store_->rewind_to_acked();
    window_limit_ = 1;
    last_progress_ms_ = now;
  }

  uint8_t sent = 0;
  RideRecord rec;
  while (sent < max_replay_per_loop_ && store_->in_flight() < window_limit_ && store_->peek_next(rec)) {
    const uint32_t t0 = millis();
    if (!publish_record_(rec))
      break;  // not accepted by the client; same record is offered again next loop
    if (store_->in_flight() == 0)
      last_progress_ms_ = now;
    store_->mark_sent(rec);
    sent++;
    if (millis() - t0 > 50)
      break;  // the network is slow right now; do not stall the main loop further
  }

  if (now - last_status_ms_ > 30000) {
    last_status_ms_ = now;
    publish_status_();
  }
}

void RideDataLogger::on_ack_(JsonObject root) {
  if (store_ == nullptr || !root["boot"].is<uint32_t>() || !root["seq"].is<uint32_t>())
    return;
  if (store_->ack(root["boot"].as<uint32_t>(), root["seq"].as<uint32_t>())) {
    last_progress_ms_ = millis();
    window_limit_ = window_limit_ >= max_unacked_ / 2 ? max_unacked_ : window_limit_ * 2;
  }
}

void RideDataLogger::publish_status_() {
  const uint32_t dropped = store_->dropped();
  if (dropped != last_warn_dropped_) {
    last_warn_dropped_ = dropped;
    ESP_LOGW(TAG, "Ring buffer full: %" PRIu32 " oldest unacknowledged record(s) overwritten so far", dropped);
  }
  this->publish_json(status_topic_, [this, dropped](JsonObject root) {
    root["sd"] = true;
    root["unacked"] = store_->unacked_records();
    root["inflight"] = (uint32_t) store_->in_flight();
    root["dropped"] = dropped;
    root["corrupt"] = store_->corrupt();
    root["write_failures"] = write_failures_;
    root["used_bytes"] = store_->total_bytes();
  });
}

}  // namespace ride_data_logger
}  // namespace esphome

#endif  // USE_MQTT
#endif  // USE_ESP32
