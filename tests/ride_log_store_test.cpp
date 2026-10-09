// Host-side tests for esphome/components/ride_data_logger/ride_log_store.h
//   g++ -std=c++17 -I esphome/components/ride_data_logger tests/ride_log_store_test.cpp -o /tmp/rls && /tmp/rls
// (tests/test_ride_log_store.py does exactly this when g++ is available.)
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <set>
#include <unistd.h>
#include <utility>
#include <vector>

#include "ride_log_store.h"

using namespace esphome::ride_data_logger;

static int g_fail = 0;
#define CHECK(cond)                                                              \
  do {                                                                           \
    if (!(cond)) {                                                               \
      std::cerr << "FAIL line " << __LINE__ << ": " #cond "\n";                  \
      g_fail++;                                                                  \
    }                                                                            \
  } while (0)

static std::string fresh_dir() {
  char tmpl[] = "/tmp/rlsXXXXXX";
  char *d = mkdtemp(tmpl);
  return std::string(d) + "/rlog";
}

static RideRecord make(uint32_t boot, uint32_t seq) {
  RideRecord r{};
  r.boot = boot;
  r.seq = seq;
  r.epoch = 1000 + seq;
  r.uptime_ms = seq * 2000;
  for (size_t i = 0; i < MAX_SENSORS; i++)
    r.values[i] = NAN;
  r.values[0] = (float) seq;
  return r;
}

// Drain everything currently sendable, returning ids in send order.
static std::vector<std::pair<uint32_t, uint32_t>> drain(RideLogStore &s, size_t max = 100000) {
  std::vector<std::pair<uint32_t, uint32_t>> ids;
  RideRecord r;
  while (ids.size() < max && s.peek_next(r)) {
    ids.emplace_back(r.boot, r.seq);
    s.mark_sent(r);
  }
  return ids;
}

static void test_basic_send_ack_cleanup() {
  RideLogStore s(fresh_dir(), 1 << 20);
  CHECK(s.open());
  for (uint32_t i = 0; i < 10; i++)
    CHECK(s.append(make(7, i)));
  CHECK(s.unacked_records() == 10);
  auto ids = drain(s);
  CHECK(ids.size() == 10);
  for (uint32_t i = 0; i < 10; i++)
    CHECK(ids[i].second == i);
  CHECK(s.in_flight() == 10);
  CHECK(!s.ack(7, 99));  // unknown id ignored
  CHECK(s.ack(7, 4));    // acknowledges 0..4
  CHECK(s.unacked_records() == 5);
  CHECK(s.in_flight() == 5);
  CHECK(!s.ack(7, 4));  // duplicate ack ignored
  CHECK(s.ack(7, 9));
  CHECK(s.unacked_records() == 0);
}

static void test_resume_after_reboot_resends_only_unacked() {
  std::string dir = fresh_dir();
  {
    RideLogStore s(dir, 1 << 20);
    CHECK(s.open());
    for (uint32_t i = 0; i < 20; i++)
      s.append(make(1, i));
    drain(s);
    CHECK(s.ack(1, 11));  // 0..11 acknowledged, 12..19 sent but never acked
  }                       // "power cut"
  RideLogStore s(dir, 1 << 20);
  CHECK(s.open());
  CHECK(s.unacked_records() == 8);
  auto ids = drain(s);
  CHECK(ids.size() == 8);
  CHECK(ids.front() == std::make_pair(1u, 12u));  // nothing lost...
  CHECK(ids.back() == std::make_pair(1u, 19u));
  // ...and nothing already acknowledged comes back (ids are unique, the
  // receiver drops any resend of 12..19 that it had already stored).
  s.append(make(2, 0));  // new boot continues after the old backlog, in order
  auto more = drain(s);
  CHECK(more.size() == 1 && more[0] == std::make_pair(2u, 0u));
}

static void test_order_across_segments_and_boots() {
  std::string dir = fresh_dir();
  uint32_t total = RideLogStore::RECORDS_PER_SEGMENT * 3 + 17;
  {
    RideLogStore s(dir, 1 << 22);
    CHECK(s.open());
    for (uint32_t i = 0; i < total; i++)
      CHECK(s.append(make(5, i)));
  }
  RideLogStore s(dir, 1 << 22);
  CHECK(s.open());
  for (uint32_t i = 0; i < 40; i++)
    CHECK(s.append(make(6, i)));
  auto ids = drain(s);
  CHECK(ids.size() == total + 40);
  std::set<std::pair<uint32_t, uint32_t>> uniq(ids.begin(), ids.end());
  CHECK(uniq.size() == ids.size());  // no duplicates
  for (uint32_t i = 0; i < total; i++)
    CHECK(ids[i] == std::make_pair(5u, i));  // strict order
  for (uint32_t i = 0; i < 40; i++)
    CHECK(ids[total + i] == std::make_pair(6u, i));
  CHECK(s.ack(6, 39));
  CHECK(s.unacked_records() == 0);
}

static void test_rewind_resends_unacked_window() {
  RideLogStore s(fresh_dir(), 1 << 20);
  CHECK(s.open());
  for (uint32_t i = 0; i < 6; i++)
    s.append(make(3, i));
  drain(s);
  CHECK(s.ack(3, 1));
  s.rewind_to_acked();  // e.g. MQTT reconnected, acks may have been lost
  auto ids = drain(s);
  CHECK(ids.size() == 4 && ids.front().second == 2 && ids.back().second == 5);
}

static void test_ring_buffer_evicts_oldest_and_keeps_recording() {
  std::string dir = fresh_dir();
  RideLogStore s(dir, 4 * RideLogStore::SEGMENT_BYTES);  // tiny budget: 4 segments
  CHECK(s.open());
  uint32_t n = RideLogStore::RECORDS_PER_SEGMENT * 10;
  for (uint32_t i = 0; i < n; i++)
    CHECK(s.append(make(9, i)));  // never refuses
  CHECK(s.dropped() > 0);
  CHECK(s.total_bytes() <= 4 * RideLogStore::SEGMENT_BYTES);
  auto ids = drain(s);
  CHECK(!ids.empty());
  CHECK(ids.back() == std::make_pair(9u, n - 1));  // newest data is kept
  CHECK(ids.size() + s.dropped() == n);            // every record is either sent or counted as dropped
  for (size_t i = 1; i < ids.size(); i++)
    CHECK(ids[i].second == ids[i - 1].second + 1);  // contiguous tail, no reorder
}

static void test_corrupt_record_is_skipped_not_fatal() {
  std::string dir = fresh_dir();
  {
    RideLogStore s(dir, 1 << 20);
    CHECK(s.open());
    for (uint32_t i = 0; i < 5; i++)
      s.append(make(4, i));
  }
  // flip a byte inside record #2 of the only segment
  std::string seg = dir + "/00000001.seg";
  FILE *f = fopen(seg.c_str(), "r+b");
  CHECK(f != nullptr);
  fseek(f, 2 * sizeof(RideRecord) + 5, SEEK_SET);
  fputc(0xAB, f);
  fclose(f);

  RideLogStore s(dir, 1 << 20);
  CHECK(s.open());
  auto ids = drain(s);
  CHECK(ids.size() == 4);
  CHECK(s.corrupt() == 1);
  for (auto &id : ids)
    CHECK(id.second != 2);
}

static void test_torn_tail_after_crash() {
  std::string dir = fresh_dir();
  {
    RideLogStore s(dir, 1 << 20);
    CHECK(s.open());
    for (uint32_t i = 0; i < 3; i++)
      s.append(make(8, i));
  }
  // simulate power loss in the middle of writing record #3: 20 stray bytes
  FILE *f = fopen((dir + "/00000001.seg").c_str(), "ab");
  const char junk[20] = {1};
  fwrite(junk, 1, sizeof(junk), f);
  fclose(f);

  RideLogStore s(dir, 1 << 20);
  CHECK(s.open());
  s.append(make(10, 0));  // a new boot writes into a FRESH segment, not after the junk
  auto ids = drain(s);
  CHECK(ids.size() == 4);
  CHECK(ids[2] == std::make_pair(8u, 2u));
  CHECK(ids[3] == std::make_pair(10u, 0u));
}

static void test_meta_slot_corruption_falls_back_safely() {
  std::string dir = fresh_dir();
  {
    RideLogStore s(dir, 1 << 20);
    CHECK(s.open());
    for (uint32_t i = 0; i < 6; i++)
      s.append(make(2, i));
    drain(s);
    s.ack(2, 2);  // gen 1 -> slot 1
    s.ack(2, 4);  // gen 2 -> slot 0
  }
  // Destroy the newest slot (slot 0); the older valid one must still be used.
  FILE *f = fopen((dir + "/meta.bin").c_str(), "r+b");
  CHECK(f != nullptr);
  fseek(f, 3, SEEK_SET);
  fputc(0x55, f);
  fclose(f);

  RideLogStore s(dir, 1 << 20);
  CHECK(s.open());
  auto ids = drain(s);
  // Falls back to the older acknowledged position: may resend 3,4 (receiver
  // dedupes by id) but must NEVER lose 5.
  CHECK(!ids.empty() && ids.back() == std::make_pair(2u, 5u));
  CHECK(ids.front().second <= 3);
}

static void test_ack_position_survives_numbering_after_full_drain() {
  std::string dir = fresh_dir();
  {
    RideLogStore s(dir, 1 << 20);
    CHECK(s.open());
    for (uint32_t i = 0; i < RideLogStore::RECORDS_PER_SEGMENT + 3; i++)
      s.append(make(1, i));
    drain(s);
    CHECK(s.ack(1, RideLogStore::RECORDS_PER_SEGMENT + 2));
  }
  RideLogStore s(dir, 1 << 20);
  CHECK(s.open());
  CHECK(s.unacked_records() == 0);
  CHECK(drain(s).empty());  // nothing is replayed twice
  s.append(make(2, 0));
  auto ids = drain(s);
  CHECK(ids.size() == 1 && ids[0] == std::make_pair(2u, 0u));
}

static void test_open_failure_reports_errno() {
  RideLogStore s("/nonexistent-parent/rlog", 1 << 20);
  CHECK(!s.open());
  CHECK(s.last_errno() != 0);
  RideRecord r{};
  CHECK(!s.append(r));  // an unopened store never pretends to write
}

static void test_phone_sync_reads_without_moving_the_mqtt_cursor() {
  RideLogStore s(fresh_dir(), 1 << 22);
  CHECK(s.open());
  uint32_t total = RideLogStore::RECORDS_PER_SEGMENT + 40;  // crosses a segment boundary
  for (uint32_t i = 0; i < total; i++)
    s.append(make(5, i));
  RideLogStore::Pos cur;
  CHECK(s.seek_after(0, 0, cur));  // from the oldest unreleased record
  RideRecord buf[16];
  uint32_t seen = 0;
  for (;;) {
    int n = s.read_batch(cur, buf, 16);
    if (n == 0)
      break;
    for (int i = 0; i < n; i++)
      CHECK(buf[i].seq == seen + (uint32_t) i);  // in order, nothing skipped
    seen += (uint32_t) n;
  }
  CHECK(seen == total);
  CHECK(s.unacked_records() == total);  // reading is not releasing
  CHECK(drain(s).size() == total);      // MQTT sender is unaffected
}

static void test_phone_resume_after_a_known_id() {
  RideLogStore s(fresh_dir(), 1 << 20);
  CHECK(s.open());
  for (uint32_t i = 0; i < 30; i++)
    s.append(make(2, i));
  RideLogStore::Pos cur;
  CHECK(s.seek_after(2, 9, cur));  // "I have everything up to seq 9"
  RideRecord buf[64];
  int n = s.read_batch(cur, buf, 64);
  CHECK(n == 20 && buf[0].seq == 10 && buf[19].seq == 29);
  CHECK(!s.seek_after(2, 999, cur));  // unknown id: falls back to the oldest unreleased
  CHECK(s.read_batch(cur, buf, 64) == 30 && buf[0].seq == 0);
}

static void test_ack_through_releases_exactly_that_much_and_is_idempotent() {
  std::string dir = fresh_dir();
  {
    RideLogStore s(dir, 1 << 22);
    CHECK(s.open());
    uint32_t total = RideLogStore::RECORDS_PER_SEGMENT * 2 + 25;
    for (uint32_t i = 0; i < total; i++)
      s.append(make(8, i));
    CHECK(s.ack_through(8, RideLogStore::RECORDS_PER_SEGMENT + 9));  // inside the 2nd segment
    CHECK(s.unacked_records() == total - (RideLogStore::RECORDS_PER_SEGMENT + 10));
    CHECK(!s.ack_through(8, 5));  // older id: already released, no effect
    CHECK(s.unacked_records() == total - (RideLogStore::RECORDS_PER_SEGMENT + 10));
    auto ids = drain(s);  // the MQTT sender continues exactly after the released part
    CHECK(!ids.empty() && ids.front() == std::make_pair(8u, RideLogStore::RECORDS_PER_SEGMENT + 10));
  }
  RideLogStore s2(dir, 1 << 22);  // survives a restart
  CHECK(s2.open());
  auto ids = drain(s2);
  CHECK(!ids.empty() && ids.front() == std::make_pair(8u, RideLogStore::RECORDS_PER_SEGMENT + 10));
}

static void test_ack_through_clears_the_mqtt_inflight_window() {
  RideLogStore s(fresh_dir(), 1 << 20);
  CHECK(s.open());
  for (uint32_t i = 0; i < 12; i++)
    s.append(make(4, i));
  drain(s, 8);  // 8 in flight over MQTT
  CHECK(s.in_flight() == 8);
  CHECK(s.ack_through(4, 5));  // the phone path acks 0..5
  CHECK(s.in_flight() == 2);   // 6 and 7 remain in flight
  CHECK(s.ack(4, 7));          // the MQTT path can still ack the rest
  CHECK(s.in_flight() == 0);
}

int main() {
  test_open_failure_reports_errno();
  test_phone_sync_reads_without_moving_the_mqtt_cursor();
  test_phone_resume_after_a_known_id();
  test_ack_through_releases_exactly_that_much_and_is_idempotent();
  test_ack_through_clears_the_mqtt_inflight_window();
  test_basic_send_ack_cleanup();
  test_resume_after_reboot_resends_only_unacked();
  test_order_across_segments_and_boots();
  test_rewind_resends_unacked_window();
  test_ring_buffer_evicts_oldest_and_keeps_recording();
  test_corrupt_record_is_skipped_not_fatal();
  test_torn_tail_after_crash();
  test_meta_slot_corruption_falls_back_safely();
  test_ack_position_survives_numbering_after_full_drain();
  if (g_fail == 0)
    std::cout << "ride_log_store: all tests passed\n";
  return g_fail == 0 ? 0 : 1;
}
