#pragma once

#ifdef USE_ESP32

#include "esphome/core/component.h"
#include "esphome/core/preferences.h"
#include "esphome/components/binary_sensor/binary_sensor.h"
#include "esphome/components/sensor/sensor.h"
#include "livedata_decoder.h"
#include <string>
#include <cstdint>

namespace esphome {
namespace bosch_ebike_ldi {

extern const uint8_t LDI_SERVICE_UUID128[16];
extern const uint8_t LDI_LIVE_DATA_CHR_UUID128[16];
static constexpr uint16_t LDI_APPEARANCE_CYCLING = 0x0480;

// Characteristics of the phone service that the data side may notify on.
enum PhoneChar : uint8_t { PHONE_CHR_LIVE = 0, PHONE_CHR_STATUS = 1, PHONE_CHR_LOG = 2 };

// Snapshot of the phone connection (values are updated from the Bluetooth host task).
struct PhoneState {
  bool connected{false};
  bool encrypted{false};
  uint16_t mtu{23};
  bool sub_live{false};
  bool sub_status{false};
  bool sub_log{false};
};

class BoschEbikeLdi : public Component {
 public:
  void setup() override;
  void loop() override;
  void dump_config() override;
  float get_setup_priority() const override;

  void set_device_name(const std::string &name) { this->device_name_ = name; }

  // ---- Sensor wiring (set from sensor.py / binary_sensor.py codegen) ----
  void set_speed_sensor(sensor::Sensor *s)              { speed_sensor_ = s; }
  void set_cadence_sensor(sensor::Sensor *s)            { cadence_sensor_ = s; }
  void set_rider_power_sensor(sensor::Sensor *s)        { rider_power_sensor_ = s; }
  void set_ambient_brightness_sensor(sensor::Sensor *s) { ambient_brightness_sensor_ = s; }
  void set_battery_soc_sensor(sensor::Sensor *s)        { battery_soc_sensor_ = s; }
  void set_odometer_sensor(sensor::Sensor *s)           { odometer_sensor_ = s; }

  void set_connected_sensor(binary_sensor::BinarySensor *s)         { connected_sensor_ = s; }
  void set_light_sensor(binary_sensor::BinarySensor *s)             { light_sensor_ = s; }
  void set_system_locked_sensor(binary_sensor::BinarySensor *s)     { system_locked_sensor_ = s; }
  void set_charger_connected_sensor(binary_sensor::BinarySensor *s) { charger_connected_sensor_ = s; }
  void set_light_reserve_sensor(binary_sensor::BinarySensor *s)     { light_reserve_sensor_ = s; }
  void set_diagnosis_active_sensor(binary_sensor::BinarySensor *s)  { diagnosis_active_sensor_ = s; }
  void set_bike_in_motion_sensor(binary_sensor::BinarySensor *s)    { bike_in_motion_sensor_ = s; }

  // ---- Called from NimBLE callback context ----
  void on_connect_state_change(bool connected);
  void on_live_data_notify(const uint8_t *data, size_t len);

  void clear_bonding();

  // Open the discoverable pairing window (default 5 min). While it is open the
  // bridge advertises with the LDI service solicitation so a Flow app can add
  // it; outside the window the bridge advertises privately (no solicitation,
  // non-discoverable, whitelist) so it is invisible to other users' Flow apps
  // while the bonded bike can still reconnect. Exposed as an HA button.
  void start_pairing();
  // True while the pairing window is open (for an HA status binary_sensor).
  bool is_pairing();

  // Master advertising toggle (HA switch, default ON). When OFF the bridge
  // only advertises during a pairing window (boot 5 min / button) and is
  // otherwise fully silent; when ON it additionally does private reconnect
  // advertising to the bonded bike. The 5-minute boot window runs regardless.
  void set_advertising_enabled(bool enabled);
  bool advertising_enabled();

  // ---- Phone link (Android companion app), enabled with `phone_link: true` ----
  void set_phone_link_enabled(bool enabled) { phone_link_enabled_ = enabled; }
  bool phone_link_enabled() const { return phone_link_enabled_; }
  // Open a 5 minute window in which the NEXT new device that connects is registered
  // as the phone. Closes the bike pairing window. Exposed as an HA button.
  void start_phone_pairing();
  bool is_phone_pairing();
  // True once a phone has been paired and registered (survives reboots).
  bool phone_registered();
  PhoneState phone_state();
  // Send one notification to the (encrypted, subscribed) phone. Main loop only.
  bool phone_notify(PhoneChar chr, const uint8_t *data, size_t len);
  // Next command written by the phone, 0 if none. Main loop only.
  size_t phone_pop_command(uint8_t *buf, size_t max);
  // Bytes returned when the phone reads the status characteristic.
  void phone_set_status(const uint8_t *data, size_t len);
  // Latest values from the bike and whether the bike is connected.
  const LiveData &latest() const { return latest_; }
  bool bike_connected() const { return last_published_connected_; }

 protected:
  std::string device_name_{"HA eBike Bridge"};
  bool phone_link_enabled_{false};
  ESPPreferenceObject phone_pref_;

  // Connection state plumbing
  bool pending_connected_state_{false};
  bool last_published_connected_{false};
  bool connection_dirty_{false};

  // Last decoded values (held across notifications since fields may be sparse)
  LiveData latest_;
  bool data_dirty_{false};

  // Sensors
  sensor::Sensor *speed_sensor_{nullptr};
  sensor::Sensor *cadence_sensor_{nullptr};
  sensor::Sensor *rider_power_sensor_{nullptr};
  sensor::Sensor *ambient_brightness_sensor_{nullptr};
  sensor::Sensor *battery_soc_sensor_{nullptr};
  sensor::Sensor *odometer_sensor_{nullptr};

  binary_sensor::BinarySensor *connected_sensor_{nullptr};
  binary_sensor::BinarySensor *light_sensor_{nullptr};
  binary_sensor::BinarySensor *system_locked_sensor_{nullptr};
  binary_sensor::BinarySensor *charger_connected_sensor_{nullptr};
  binary_sensor::BinarySensor *light_reserve_sensor_{nullptr};
  binary_sensor::BinarySensor *diagnosis_active_sensor_{nullptr};
  binary_sensor::BinarySensor *bike_in_motion_sensor_{nullptr};

  void publish_decoded_();
};

}  // namespace bosch_ebike_ldi
}  // namespace esphome

#endif
