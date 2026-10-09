#pragma once

// USE_ESP32 is a real compiler -D flag (platform-wide), but USE_MQTT only
// exists inside this generated header - it must be included before the
// #ifdef below can see it (same reason esphome/components/mqtt/custom_mqtt_device.h
// includes it first).
#include "esphome/core/defines.h"

#ifdef USE_ESP32
#ifdef USE_MQTT

#include "esphome/core/component.h"
#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/sensor/sensor.h"
#include "esphome/components/mqtt/custom_mqtt_device.h"
#include "esphome/components/time/real_time_clock.h"
#include "esphome/components/wifi/wifi_component.h"

#include "ride_log_store.h"
#ifdef RIDE_LOGGER_PHONE_LINK
#include "esphome/components/bosch_ebike_ldi/bosch_ebike_ldi.h"
#endif
#include "sdmmc_cmd.h"

#include <cstdint>
#include <string>

namespace esphome {
namespace ride_data_logger {

// Delivery protocol (see RIDE_LOGGING.md):
//   ESP  -> <replay_topic>        QoS1  {"boot","seq","epoch"?,"uptime_ms",<keys>...}
//   HA   -> <replay_topic>/ack    QoS1  {"boot","seq"}   "stored everything up to and incl. this id"
//   ESP  -> <replay_topic>/status QoS0  {"sd","unacked","inflight","dropped","corrupt","used_bytes"}
// Every sample goes to the SD card first; it is only released once acked.
class RideDataLogger : public Component, public mqtt::CustomMQTTDevice {
 public:
  void setup() override;
  void loop() override;
  void dump_config() override;
  // After WIFI/MQTT so is_connected() reflects reality by the time we first sample.
  float get_setup_priority() const override { return setup_priority::AFTER_WIFI; }

  void set_pins(int32_t cs, int32_t mosi, int32_t miso, int32_t clk) {
    cs_pin_ = cs;
    mosi_pin_ = mosi;
    miso_pin_ = miso;
    clk_pin_ = clk;
  }
  void set_sample_interval(uint32_t ms) { sample_interval_ms_ = ms; }
  void set_replay_topic(const std::string &topic) { replay_topic_ = topic; }
  void set_ack_topic(const std::string &topic) { ack_topic_ = topic; }
  void set_ack_secret(const std::string &secret) { ack_secret_ = secret; }
  void set_status_topic(const std::string &topic) { status_topic_ = topic; }
  void set_max_replay_per_loop(uint8_t n) { max_replay_per_loop_ = n; }
  void set_max_unacked(uint16_t n) { max_unacked_ = n; }
  void set_ack_timeout(uint32_t ms) { ack_timeout_ms_ = ms; }
  void set_max_log_bytes(uint32_t n) { max_log_bytes_ = n; }
  void set_time(time::RealTimeClock *time) { time_ = time; }
#ifdef RIDE_LOGGER_PHONE_LINK
  void set_phone_link(bosch_ebike_ldi::BoschEbikeLdi *ldi) { phone_link_ = ldi; }
#endif
  void set_record_when(binary_sensor::BinarySensor *b) { record_when_ = b; }

  // For a status LED: 0 = idle (bike not connected), 1 = recording to the card,
  // 2 = problem (no usable card or writes failing).
  uint8_t led_state() const;

  void add_sensor(sensor::Sensor *s, const std::string &key);
  void add_binary_sensor(binary_sensor::BinarySensor *s, const std::string &key);

 protected:
  // ---- SD card (raw ESP-IDF SDSPI + FATFS) ----
  bool mount_sd_();
  void diagnose_sd_();  // prints raw FatFs results when the log cannot be set up
  bool sd_mounted_{false};
  sdmmc_card_t *card_{nullptr};

  int32_t cs_pin_{-1};
  int32_t mosi_pin_{-1};
  int32_t miso_pin_{-1};
  int32_t clk_pin_{-1};

  // ---- Sampling ----
  sensor::Sensor *sensors_[MAX_SENSORS]{};
  std::string sensor_keys_[MAX_SENSORS];
  uint8_t sensor_count_{0};

  binary_sensor::BinarySensor *binary_sensors_[MAX_BINARY_SENSORS]{};
  std::string binary_sensor_keys_[MAX_BINARY_SENSORS];
  uint8_t binary_sensor_count_{0};

  binary_sensor::BinarySensor *record_when_{nullptr};  // optional gate, see take_sample_()
  bool was_recording_{false};
  // True once a record carrying a real wall-clock time was written in this boot.
  bool clock_anchored_{false};
  uint32_t sample_interval_ms_{2000};
  uint32_t last_sample_ms_{0};
  time::RealTimeClock *time_{nullptr};
  uint32_t current_epoch_();

  // Random per power-up; together with seq it is a record id that is unique
  // for all time, which is what lets the receiver drop resends exactly.
  uint32_t boot_id_{0};
  uint32_t next_seq_{0};
  uint32_t write_failures_{0};
  bool last_write_ok_{true};
  // Slowest SD append seen since boot and how many took over 200 ms; a stalled
  // write blocks the main loop, and the task watchdog restarts the ESP after 5 s.
  uint32_t max_append_ms_{0};
  uint32_t slow_appends_{0};

  void take_sample_();

  // ---- Acknowledged replay ----
  RideLogStore *store_{nullptr};
  std::string replay_topic_;
  std::string ack_topic_;
  std::string ack_secret_;  // empty = acks are not authenticated
  std::string status_topic_;
  uint8_t max_replay_per_loop_{4};
  uint16_t max_unacked_{16};
  // Slow start: send 1 record at a time, double after each ack up to max_unacked_,
  // fall back to 1 on an ack timeout. Keeps a missing/slow receiver from
  // triggering resend floods that stall the main loop.
  uint16_t window_limit_{1};
  uint32_t ack_timeout_ms_{10000};
  uint32_t max_log_bytes_{16 * 1024 * 1024};

  bool was_connected_{false};
  uint32_t last_progress_ms_{0};
  uint32_t last_status_ms_{0};
  uint32_t last_warn_dropped_{0};

#ifdef RIDE_LOGGER_PHONE_LINK
  // ---- Android companion app (see docs/app/PHONE_LINK_PROTOCOL.md) ----
  bosch_ebike_ldi::BoschEbikeLdi *phone_link_{nullptr};
  RideLogStore::Pos phone_cursor_{};
  bool phone_syncing_{false};
  bool phone_status_dirty_{true};
  uint8_t last_cmd_{0};
  uint8_t last_cmd_result_{0};
  uint32_t last_phone_live_ms_{0};
  uint32_t last_phone_status_ms_{0};
  void service_phone_();
  void handle_phone_command_(const uint8_t *cmd, size_t len);
  size_t build_phone_status_(uint8_t *out);
  void build_live_record_(RideRecord &rec);
#endif

  void service_send_();
  void publish_status_();
  void on_ack_(JsonObject root);
  bool ack_valid_(uint32_t boot, uint32_t seq, const char *mac_hex) const;
  bool publish_record_(const RideRecord &rec);
};

}  // namespace ride_data_logger
}  // namespace esphome

#endif  // USE_MQTT
#endif  // USE_ESP32
