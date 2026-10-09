#include "esphome/core/defines.h"

#ifdef USE_ESP32
#ifdef USE_MQTT

#include "ride_data_logger.h"
#include "esphome/core/log.h"

#include "esp_random.h"
#include "mbedtls/md.h"
#include "esp_vfs_fat.h"
#include "driver/sdspi_host.h"
#include "driver/spi_common.h"
#include "ff.h"
#include <dirent.h>
#include <sys/stat.h>

#include <cinttypes>
#include <sys/time.h>
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

static const char *fr_name(FRESULT r) {
  static const char *const names[] = {"OK", "DISK_ERR", "INT_ERR", "NOT_READY", "NO_FILE", "NO_PATH",
                                      "INVALID_NAME", "DENIED", "EXIST", "INVALID_OBJECT", "WRITE_PROTECTED",
                                      "INVALID_DRIVE", "NOT_ENABLED", "NO_FILESYSTEM", "MKFS_ABORTED", "TIMEOUT",
                                      "LOCKED", "NOT_ENOUGH_CORE", "TOO_MANY_OPEN_FILES", "INVALID_PARAMETER"};
  return (int) r >= 0 && (int) r < 20 ? names[r] : "?";
}

// Talk to FatFs directly (bypassing the VFS layer) so the log shows the real
// reason when the card mounts but files/folders cannot be created.
void RideDataLogger::diagnose_sd_() {
  FATFS *fs = nullptr;
  DWORD free_clusters = 0;
  FRESULT r = f_getfree("0:", &free_clusters, &fs);
  ESP_LOGE(TAG, "diag f_getfree: %s (free clusters %u, cluster size %u sectors)", fr_name(r),
           (unsigned) free_clusters, fs != nullptr ? (unsigned) fs->csize : 0u);
  r = f_mkdir("0:/rlog");
  ESP_LOGE(TAG, "diag f_mkdir 0:/rlog: %s", fr_name(r));
  FIL f;
  r = f_open(&f, "0:/probe.tmp", FA_WRITE | FA_CREATE_ALWAYS);
  ESP_LOGE(TAG, "diag f_open(write) 0:/probe.tmp: %s", fr_name(r));
  if (r == FR_OK) {
    UINT bw = 0;
    r = f_write(&f, "x", 1, &bw);
    ESP_LOGE(TAG, "diag f_write: %s (%u bytes)", fr_name(r), (unsigned) bw);
    FRESULT c = f_close(&f);
    ESP_LOGE(TAG, "diag f_close: %s", fr_name(c));
    f_unlink("0:/probe.tmp");
  }
  DIR *d = opendir(MOUNT_POINT);
  ESP_LOGE(TAG, "diag opendir(%s): %s", MOUNT_POINT, d != nullptr ? "ok" : "FAILED");
  if (d != nullptr)
    closedir(d);
  FILE *pf = fopen("/sdcard/probe2.tmp", "wb");
  ESP_LOGE(TAG, "diag fopen(/sdcard/probe2.tmp): %s (errno %d)", pf != nullptr ? "ok" : "FAILED", errno);
  if (pf != nullptr) {
    fclose(pf);
    remove("/sdcard/probe2.tmp");
  }
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
        diagnose_sd_();
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
#ifdef RIDE_LOGGER_PHONE_LINK
  if (phone_link_ != nullptr)
    service_phone_();
#endif
}

void RideDataLogger::dump_config() {
  ESP_LOGCONFIG(TAG, "Ride Data Logger:");
  ESP_LOGCONFIG(TAG, "  SD card: %s", sd_mounted_ ? "mounted" : "NOT MOUNTED (offline buffering disabled)");
  ESP_LOGCONFIG(TAG, "  Pins: CS=%" PRId32 " MOSI=%" PRId32 " MISO=%" PRId32 " CLK=%" PRId32, cs_pin_, mosi_pin_,
                miso_pin_, clk_pin_);
  ESP_LOGCONFIG(TAG, "  Sample interval: %" PRIu32 " ms", sample_interval_ms_);
  ESP_LOGCONFIG(TAG, "  Replay topic: %s", replay_topic_.c_str());
  ESP_LOGCONFIG(TAG, "  Ack topic: %s", ack_topic_.c_str());
  ESP_LOGCONFIG(TAG, "  Ack signing: %s", ack_secret_.empty() ? "OFF (any broker client can ack)" : "on");
  ESP_LOGCONFIG(TAG, "  Status topic: %s", status_topic_.c_str());
  ESP_LOGCONFIG(TAG, "  Max sends/loop: %u, max unacked in flight: %u, ack timeout: %" PRIu32 " ms",
                max_replay_per_loop_, max_unacked_, ack_timeout_ms_);
  ESP_LOGCONFIG(TAG, "  Ring buffer size: %" PRIu32 " bytes", max_log_bytes_);
  ESP_LOGCONFIG(TAG, "  Sensors: %u, binary_sensors: %u", sensor_count_, binary_sensor_count_);
}

uint8_t RideDataLogger::led_state() const {
  if (store_ == nullptr || !last_write_ok_)
    return 2;
  if (record_when_ != nullptr && !(record_when_->has_state() && record_when_->state))
    return 0;
  return 1;
}

uint32_t RideDataLogger::current_epoch_() {
  if (time_ == nullptr)
    return 0;
  auto t = time_->now();
  return t.is_valid() ? (uint32_t) t.timestamp : 0;
}

// ---- Sampling ------------------------------------------------------------

void RideDataLogger::take_sample_() {
  // Optional gate: record only while the gate sensor (e.g. "eBike Connected") is
  // on. One extra sample is taken right after it turns off, so the end of the
  // ride is captured.
  //
  // Exception: the first time the clock becomes valid in this boot, always write
  // one "clock anchor" record, even with the bike off. Samples taken before the
  // clock was known carry only the uptime; this record (epoch + uptime) lets the
  // receiver work out the real time of every earlier sample of this boot, which
  // is what happens when the ESP rides away from WiFi and syncs the time at home.
  bool force_anchor = false;
  if (!clock_anchored_ && current_epoch_() != 0) {
    clock_anchored_ = true;
    force_anchor = true;
  }
  if (record_when_ != nullptr) {
    const bool on = record_when_->has_state() && record_when_->state;
    const bool trailing = !on && was_recording_;
    was_recording_ = on;
    if (!on && !trailing && !force_anchor)
      return;
  }
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
  const uint32_t t_append = millis();
  last_write_ok_ = store_->append(rec);
  const uint32_t append_ms = millis() - t_append;
  if (append_ms > max_append_ms_)
    max_append_ms_ = append_ms;
  if (append_ms > 200) {
    slow_appends_++;
    ESP_LOGW(TAG, "Slow SD write: %" PRIu32 " ms (slowest so far %" PRIu32 " ms)", append_ms, max_append_ms_);
  }
  if (!last_write_ok_) {
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

#ifdef RIDE_LOGGER_PHONE_LINK
// ---- Android companion app -----------------------------------------------------
// Wire formats (all little-endian) are documented in docs/app/PHONE_LINK_PROTOCOL.md.
static void put_u32(uint8_t *b, uint32_t v) {
  b[0] = (uint8_t) v;
  b[1] = (uint8_t) (v >> 8);
  b[2] = (uint8_t) (v >> 16);
  b[3] = (uint8_t) (v >> 24);
}
static uint32_t get_u32(const uint8_t *b) {
  return (uint32_t) b[0] | ((uint32_t) b[1] << 8) | ((uint32_t) b[2] << 16) | ((uint32_t) b[3] << 24);
}

enum PhoneCmd : uint8_t { CMD_SET_TIME = 1, CMD_SYNC_FROM = 2, CMD_ACK_UP_TO = 3, CMD_STOP_SYNC = 4, CMD_GET_STATUS = 5 };
enum PhoneResult : uint8_t {
  RES_OK = 0,
  RES_UNKNOWN_CMD = 1,
  RES_BAD_ARGS = 2,
  RES_NO_CARD = 3,
  RES_NOT_FOUND = 4,         // ACK_UP_TO: id not in the log (already released)
  RES_FELL_BACK = 5,         // SYNC_FROM: id not found, streaming from the oldest unreleased record
  RES_MTU_TOO_SMALL = 6,
};

void RideDataLogger::build_live_record_(RideRecord &rec) {
  rec = RideRecord{};
  rec.boot = boot_id_;
  rec.seq = next_seq_;
  rec.epoch = current_epoch_();
  rec.uptime_ms = millis();
  for (uint8_t i = 0; i < MAX_SENSORS; i++)
    rec.values[i] = (i < sensor_count_ && sensors_[i] != nullptr && sensors_[i]->has_state()) ? sensors_[i]->state : NAN;
  for (uint8_t i = 0; i < binary_sensor_count_; i++) {
    if (binary_sensors_[i] != nullptr && binary_sensors_[i]->has_state()) {
      rec.binary_known |= (1 << i);
      if (binary_sensors_[i]->state)
        rec.binary_state |= (1 << i);
    }
  }
  ride_seal(rec);
}

size_t RideDataLogger::build_phone_status_(uint8_t *b) {
  const bool wifi_up = wifi::global_wifi_component != nullptr && wifi::global_wifi_component->is_connected();
  uint8_t flags = 0;
  if (store_ != nullptr)
    flags |= 0x01;  // SD log usable
  if (current_epoch_() != 0)
    flags |= 0x02;  // clock valid
  if (phone_link_ != nullptr && phone_link_->bike_connected())
    flags |= 0x04;  // bike connected
  if (wifi_up)
    flags |= 0x08;
  if (wifi_up && this->is_connected())
    flags |= 0x10;  // MQTT up
  if (phone_syncing_)
    flags |= 0x20;
  if (led_state() == 1)
    flags |= 0x40;  // recording
  size_t i = 0;
  b[i++] = 1;  // format version
  b[i++] = flags;
  b[i++] = led_state();
  b[i++] = last_cmd_;
  b[i++] = last_cmd_result_;
  const uint32_t fields[9] = {boot_id_,
                              next_seq_,
                              store_ ? store_->unacked_records() : 0,
                              store_ ? store_->dropped() : 0,
                              write_failures_,
                              max_append_ms_,
                              store_ ? store_->total_bytes() : 0,
                              (uint32_t) millis(),
                              current_epoch_()};
  for (uint32_t f : fields) {
    put_u32(b + i, f);
    i += 4;
  }
  return i;  // 41 bytes
}

void RideDataLogger::handle_phone_command_(const uint8_t *c, size_t n) {
  uint8_t res = RES_OK;
  switch (c[0]) {
    case CMD_SET_TIME: {
      const uint32_t e = n == 5 ? get_u32(c + 1) : 0;
      if (n != 5 || e < 1577836800u || e > 4102444800u) {  // 2020-01-01 .. 2100-01-01
        res = RES_BAD_ARGS;
        break;
      }
      struct timeval tv;
      tv.tv_sec = (time_t) e;
      tv.tv_usec = 0;
      settimeofday(&tv, nullptr);
      ESP_LOGI(TAG, "Clock set by the phone: %" PRIu32, e);
      break;
    }
    case CMD_SYNC_FROM: {
      if (store_ == nullptr) {
        res = RES_NO_CARD;
        break;
      }
      if (n != 9) {
        res = RES_BAD_ARGS;
        break;
      }
      const bool found = store_->seek_after(get_u32(c + 1), get_u32(c + 5), phone_cursor_);
      phone_syncing_ = true;
      res = found ? RES_OK : RES_FELL_BACK;
      ESP_LOGI(TAG, "Phone sync started (%s)", found ? "resuming" : "from the oldest unreleased record");
      break;
    }
    case CMD_ACK_UP_TO: {
      if (store_ == nullptr) {
        res = RES_NO_CARD;
        break;
      }
      if (n != 9) {
        res = RES_BAD_ARGS;
        break;
      }
      res = store_->ack_through(get_u32(c + 1), get_u32(c + 5)) ? RES_OK : RES_NOT_FOUND;
      break;
    }
    case CMD_STOP_SYNC:
      phone_syncing_ = false;
      break;
    case CMD_GET_STATUS:
      break;
    default:
      res = RES_UNKNOWN_CMD;
      break;
  }
  last_cmd_ = c[0];
  last_cmd_result_ = res;
  phone_status_dirty_ = true;
}

void RideDataLogger::service_phone_() {
  const uint32_t now = millis();
  const bosch_ebike_ldi::PhoneState st = phone_link_->phone_state();

  uint8_t cmd[24];
  size_t n;
  while ((n = phone_link_->phone_pop_command(cmd, sizeof(cmd))) > 0)
    handle_phone_command_(cmd, n);

  if (!st.connected || !st.encrypted) {
    phone_syncing_ = false;  // a new connection must ask again
    return;
  }

  // Status: keep the readable copy fresh, and notify when asked/changed.
  if (phone_status_dirty_ || now - last_phone_status_ms_ >= 1000) {
    uint8_t buf[48];
    const size_t len = build_phone_status_(buf);
    phone_link_->phone_set_status(buf, len);
    if (st.sub_status && phone_link_->phone_notify(bosch_ebike_ldi::PHONE_CHR_STATUS, buf, len)) {
      phone_status_dirty_ = false;
      last_phone_status_ms_ = now;
    } else if (!st.sub_status) {
      phone_status_dirty_ = false;
      last_phone_status_ms_ = now;
    }
  }

  // Live data: the current sample in the same 56-byte record format as the log.
  if (st.sub_live && now - last_phone_live_ms_ >= 1000) {
    RideRecord rec;
    build_live_record_(rec);
    if (phone_link_->phone_notify(bosch_ebike_ldi::PHONE_CHR_LIVE, reinterpret_cast<const uint8_t *>(&rec),
                                  sizeof(rec)))
      last_phone_live_ms_ = now;
  }

  // Record stream: [count u8][count x 56-byte records]; count 0 = caught up (end of sync).
  if (phone_syncing_ && st.sub_log && store_ != nullptr) {
    const int payload = st.mtu > 3 ? st.mtu - 3 : 20;
    int per = (payload - 1) / (int) sizeof(RideRecord);
    if (per < 1) {
      last_cmd_result_ = RES_MTU_TOO_SMALL;
      phone_status_dirty_ = true;
      phone_syncing_ = false;
      return;
    }
    if (per > 4)
      per = 4;
    const RideLogStore::Pos before = phone_cursor_;
    RideRecord recs[4];
    const int got = store_->read_batch(phone_cursor_, recs, per);
    uint8_t pkt[1 + 4 * sizeof(RideRecord)];
    pkt[0] = (uint8_t) got;
    memcpy(pkt + 1, recs, (size_t) got * sizeof(RideRecord));
    const size_t len = 1 + (size_t) got * sizeof(RideRecord);
    if (!phone_link_->phone_notify(bosch_ebike_ldi::PHONE_CHR_LOG, pkt, len)) {
      phone_cursor_ = before;  // not sent: read the same records again next time
    } else if (got == 0) {
      phone_syncing_ = false;
      phone_status_dirty_ = true;
      ESP_LOGI(TAG, "Phone sync complete");
    }
  }
}
#endif  // RIDE_LOGGER_PHONE_LINK

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
  // "MQTT connected" stays true for a while after the WiFi is gone, and a send then
  // blocks until the network gives up, which stalls the main loop and trips the task
  // watchdog. So only send while the WiFi link itself is up.
  const bool wifi_up = wifi::global_wifi_component != nullptr && wifi::global_wifi_component->is_connected();
  const bool connected = wifi_up && this->is_connected();

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

// Constant-time check of the HMAC-SHA256 over "<boot>:<seq>" (see ack_mac() in
// backfill_core.py). Only called when a secret is configured.
bool RideDataLogger::ack_valid_(uint32_t boot, uint32_t seq, const char *mac_hex) const {
  if (mac_hex == nullptr || strlen(mac_hex) != 64)
    return false;
  char msg[32];
  int n = snprintf(msg, sizeof(msg), "%" PRIu32 ":%" PRIu32, boot, seq);
  unsigned char digest[32];
  if (n <= 0 || mbedtls_md_hmac(mbedtls_md_info_from_type(MBEDTLS_MD_SHA256),
                                reinterpret_cast<const unsigned char *>(ack_secret_.data()), ack_secret_.size(),
                                reinterpret_cast<const unsigned char *>(msg), (size_t) n, digest) != 0)
    return false;
  static const char *const hex = "0123456789abcdef";
  unsigned char diff = 0;
  for (int i = 0; i < 32; i++) {
    diff |= (unsigned char) (hex[digest[i] >> 4] ^ mac_hex[2 * i]);
    diff |= (unsigned char) (hex[digest[i] & 0x0f] ^ mac_hex[2 * i + 1]);
  }
  return diff == 0;
}

void RideDataLogger::on_ack_(JsonObject root) {
  if (store_ == nullptr || !root["boot"].is<uint32_t>() || !root["seq"].is<uint32_t>())
    return;
  const uint32_t ack_boot = root["boot"].as<uint32_t>();
  const uint32_t ack_seq = root["seq"].as<uint32_t>();
  if (!ack_secret_.empty() && !ack_valid_(ack_boot, ack_seq, root["mac"].as<const char *>())) {
    ESP_LOGW(TAG, "Ignoring an ack with a missing or wrong signature (boot %" PRIu32 ", seq %" PRIu32 ")", ack_boot,
             ack_seq);
    return;
  }
  if (store_->ack(ack_boot, ack_seq)) {
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
    root["max_append_ms"] = max_append_ms_;
    root["slow_appends"] = slow_appends_;
    root["used_bytes"] = store_->total_bytes();
  });
}

}  // namespace ride_data_logger
}  // namespace esphome

#endif  // USE_MQTT
#endif  // USE_ESP32
