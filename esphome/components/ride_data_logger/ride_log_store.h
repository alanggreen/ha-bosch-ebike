#pragma once

// Crash-safe, acknowledged, ring-buffered sample log on a FAT filesystem.
//
// Deliberately free of ESPHome / ESP-IDF includes (plain C++ + stdio/POSIX
// dir calls) so the exact same code is unit-tested on a host machine
// (tests/ride_log_store_test.cpp) and runs on the ESP unchanged.
//
// Design - why nothing is lost and nothing is duplicated:
//   * Every sample is appended to the SD log FIRST, whether or not we are
//     online. "Online" only decides whether the sender also transmits it.
//   * A record is only forgotten once the receiver (Home Assistant) has
//     acknowledged it end to end, i.e. after the receiver stored it. The
//     acknowledged position is persisted on the card (two CRC'd slots, so a
//     torn write cannot corrupt it).
//   * Every record carries a (boot, seq) id that is unique for all time. After
//     any crash, timeout or reconnect the sender simply resends from the
//     acknowledged position; the receiver recognises ids it has already
//     stored and drops them. Resend + dedupe = exactly-once.
//   * The log is a ring of fixed-size segment files. When the card budget is
//     full the OLDEST segment is deleted (and counted in dropped()), so
//     recording never stops.
//   * FAT without long-file-name support only allows 8.3 names, so segments are
//     "00000001.seg" and the position file is "meta.bin".

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <dirent.h>
#include <string>
#include <sys/stat.h>

namespace esphome {
namespace ride_data_logger {

static constexpr size_t MAX_SENSORS = 8;
static constexpr size_t MAX_BINARY_SENSORS = 8;

// One ride sample. Fixed size: "record N of a segment" lives at byte N * 56.
struct RideRecord {
  uint32_t boot;              // random per power-up; (boot, seq) is the global record id
  uint32_t seq;               // 0,1,2.. within one boot, strictly increasing
  uint32_t epoch;             // unix time at sample time, 0 if the clock was not synced yet
  uint32_t uptime_ms;         // millis() at sample time
  float values[MAX_SENSORS];  // NAN for unset slots
  uint8_t binary_state;       // bit i = binary_sensors[i]
  uint8_t binary_known;       // bit i = binary_sensors[i] had a state
  uint16_t reserved;
  uint32_t crc;               // CRC32 over all preceding bytes
};
static_assert(sizeof(RideRecord) == 56, "RideRecord layout is part of the on-card format");

inline uint32_t ride_crc32(const void *data, size_t len) {
  const uint8_t *p = static_cast<const uint8_t *>(data);
  uint32_t c = 0xFFFFFFFFu;
  for (size_t i = 0; i < len; i++) {
    c ^= p[i];
    for (int k = 0; k < 8; k++)
      c = (c >> 1) ^ (0xEDB88320u & (0u - (c & 1u)));
  }
  return ~c;
}

inline void ride_seal(RideRecord &r) { r.crc = ride_crc32(&r, offsetof(RideRecord, crc)); }
inline bool ride_valid(const RideRecord &r) { return r.crc == ride_crc32(&r, offsetof(RideRecord, crc)); }

class RideLogStore {
 public:
  static constexpr uint32_t RECORDS_PER_SEGMENT = 512;
  static constexpr uint32_t SEGMENT_BYTES = RECORDS_PER_SEGMENT * sizeof(RideRecord);

  struct Pos {
    uint32_t seg{1};
    uint32_t idx{0};
    bool operator==(const Pos &o) const { return seg == o.seg && idx == o.idx; }
  };

  RideLogStore(std::string dir, uint32_t max_bytes) : dir_(std::move(dir)), max_bytes_(max_bytes) {
    if (max_bytes_ < 4 * SEGMENT_BYTES)
      max_bytes_ = 4 * SEGMENT_BYTES;
  }

  // Scan the card, restore the acknowledged position, start a FRESH write
  // segment (so a torn tail from a previous crash is never appended to).
  bool open() {
    // Do not trust mkdir's result alone: on a FAT mount it can fail without a usable
    // errno when the folder already exists (and always for the card root). What
    // matters is whether the folder can be opened and written to.
    if (mkdir(dir_.c_str(), 0775) != 0) {
      int mk_errno = errno;
      DIR *probe_dir = opendir(dir_.c_str());
      if (probe_dir == nullptr) {
        last_errno_ = errno != 0 ? errno : mk_errno;
        return false;
      }
      closedir(probe_dir);
    }
    {
      // Prove the card is writable now, so a read-only or failing card is
      // reported at start-up with a real error instead of later at the first sample.
      std::string probe = dir_ + "/probe.tmp";
      FILE *pf = fopen(probe.c_str(), "wb");
      if (pf == nullptr) {
        last_errno_ = errno;
        return false;
      }
      bool wrote = fputc('x', pf) != EOF;
      int cl = fclose(pf);
      remove(probe.c_str());
      if (!wrote || cl != 0) {
        last_errno_ = errno;
        return false;
      }
    }

    uint32_t lo = 0, hi = 0;
    bool any = false;
    total_bytes_ = 0;
    if (DIR *d = opendir(dir_.c_str())) {
      while (struct dirent *e = readdir(d)) {
        uint32_t n;
        if (!parse_seg_name_(e->d_name, n))
          continue;
        uint32_t sz = file_size_(seg_path_(n));
        if (sz == 0) {
          remove(seg_path_(n).c_str());  // empty leftovers are noise
          continue;
        }
        total_bytes_ += sz;
        if (!any || n < lo)
          lo = n;
        if (!any || n > hi)
          hi = n;
        any = true;
      }
      closedir(d);
    }

    bool meta_ok = load_meta_(ack_);
    if (!any) {
      // Empty card / everything already acknowledged: keep numbering monotonic.
      first_seg_ = write_seg_ = meta_ok && ack_.seg > 0 ? ack_.seg : 1;
      ack_ = Pos{write_seg_, 0};
    } else {
      first_seg_ = lo;
      write_seg_ = hi + 1;
      if (!meta_ok || ack_.seg < lo || ack_.seg > hi + 1)
        ack_ = Pos{lo, 0};  // unknown position: resend everything, receiver dedupes
    }
    write_count_ = 0;
    send_ = ack_;
    window_.clear();
    recount_();
    opened_ = true;
    return true;
  }

  // Append a sample. Never blocks recording: if the card is full the oldest
  // data makes room. Returns false only on an I/O error.
  bool append(RideRecord rec) {
    if (!opened_)
      return false;
    if (write_count_ >= RECORDS_PER_SEGMENT) {
      write_seg_++;
      write_count_ = 0;
    }
    if (write_count_ == 0)
      make_room_();
    ride_seal(rec);
    FILE *f = fopen(seg_path_(write_seg_).c_str(), "ab");
    if (f == nullptr)
      return false;
    size_t n = fwrite(&rec, sizeof(rec), 1, f);
    int cl = fclose(f);
    if (n != 1 || cl != 0) {
      write_count_ = RECORDS_PER_SEGMENT;  // never append after a possibly torn write
      return false;
    }
    write_count_++;
    total_bytes_ += sizeof(rec);
    unacked_++;
    return true;
  }

  // ---- sender side --------------------------------------------------------
  bool has_unsent() const {
    return send_.seg < write_seg_ || (send_.seg == write_seg_ && send_.idx < write_count_);
  }

  // Next valid, not-yet-sent record. false = nothing to send right now.
  bool peek_next(RideRecord &out) {
    if (!opened_)
      return false;
    while (has_unsent()) {
      FILE *f = fopen(seg_path_(send_.seg).c_str(), "rb");
      if (f == nullptr) {
        if (send_.seg < write_seg_) {
          send_ = Pos{send_.seg + 1, 0};
          continue;
        }
        return false;
      }
      bool seek_ok = fseek(f, (long) send_.idx * (long) sizeof(RideRecord), SEEK_SET) == 0;
      size_t got = seek_ok ? fread(&out, sizeof(out), 1, f) : 0;
      fclose(f);
      if (got != 1) {
        if (send_.seg < write_seg_) {  // end of an older segment (or torn tail)
          send_ = Pos{send_.seg + 1, 0};
          continue;
        }
        return false;
      }
      if (!ride_valid(out)) {
        corrupt_++;
        if (unacked_ > 0)
          unacked_--;
        send_.idx++;
        if (window_.empty())
          ack_ = send_;  // nothing in flight: the skipped record cannot be pending
        continue;
      }
      return true;
    }
    return false;
  }

  // The record returned by peek_next() was handed to the transport.
  void mark_sent(const RideRecord &rec) {
    window_.push_back(InFlight{rec.boot, rec.seq, Pos{send_.seg, send_.idx + 1}});
    send_.idx++;
  }

  size_t in_flight() const { return window_.size(); }

  // Forget what was in flight and resend from the acknowledged position.
  void rewind_to_acked() {
    window_.clear();
    send_ = ack_;
  }

  // Receiver confirmed it stored record (boot, seq) and everything before it.
  // Returns true if the acknowledged position advanced.
  bool ack(uint32_t boot, uint32_t seq) {
    size_t hit = window_.size();
    for (size_t i = 0; i < window_.size(); i++) {
      if (window_[i].boot == boot && window_[i].seq == seq) {
        hit = i;
        break;
      }
    }
    if (hit == window_.size())
      return false;  // stale or duplicate ack - ignore

    Pos after = window_[hit].after;
    for (size_t i = 0; i <= hit; i++) {
      window_.pop_front();
      if (unacked_ > 0)
        unacked_--;
    }
    ack_ = after;
    normalize_ack_();
    save_meta_();
    drop_finished_segments_();
    return true;
  }

  int last_errno() const { return last_errno_; }  // set when open() fails
  // ---- phone sync (independent of the MQTT send cursor) --------------------
  // The phone reads the same log with its own cursor and later tells us which
  // records Home Assistant has confirmed. Nothing here moves the MQTT cursor
  // except ack_through(), which is the one place data is released.

  // Position just after record (boot, seq), searching from the oldest unreleased
  // record. (0, 0) means "from the oldest unreleased record". Returns false if
  // the id is not in the log (already released, or from another card): the caller
  // then gets the oldest unreleased position, which is always safe (resend).
  bool seek_after(uint32_t boot, uint32_t seq, Pos &out) {
    out = ack_;
    if (boot == 0 && seq == 0)
      return true;
    Pos p = ack_;
    RideRecord r;
    while (read_one_(p, r)) {
      if (r.boot == boot && r.seq == seq) {
        out = p;
        return true;
      }
    }
    return false;
  }

  // Read up to max valid records from `cursor`, advancing it. Returns how many
  // were read (0 = nothing more to send right now).
  int read_batch(Pos &cursor, RideRecord *out, int max) {
    int n = 0;
    while (n < max && read_one_(cursor, out[n]))
      n++;
    return n;
  }

  // Release everything up to and including record (boot, seq). Safe to call with
  // an id that was already released (returns false, changes nothing).
  bool ack_through(uint32_t boot, uint32_t seq) {
    Pos p = ack_;
    RideRecord r;
    bool found = false;
    while (read_one_(p, r)) {
      if (r.boot == boot && r.seq == seq) {
        found = true;
        break;
      }
    }
    if (!found)
      return false;
    ack_ = p;
    normalize_ack_();
    // The MQTT sender may still hold in-flight entries that are now released.
    while (!window_.empty() && !(window_.front().after.seg > ack_.seg ||
                                 (window_.front().after.seg == ack_.seg && window_.front().after.idx > ack_.idx)))
      window_.pop_front();
    if (send_.seg < ack_.seg || (send_.seg == ack_.seg && send_.idx < ack_.idx))
      send_ = ack_;
    recount_all_();
    save_meta_();
    drop_finished_segments_();
    return true;
  }

  uint32_t unacked_records() const { return unacked_; }
  uint32_t dropped() const { return dropped_; }
  uint32_t corrupt() const { return corrupt_; }
  uint32_t total_bytes() const { return total_bytes_; }
  uint32_t max_bytes() const { return max_bytes_; }
  Pos ack_pos() const { return ack_; }

 private:
  struct InFlight {
    uint32_t boot;
    uint32_t seq;
    Pos after;  // position of the record following this one
  };
  struct MetaSlot {
    uint32_t gen;
    uint32_t seg;
    uint32_t idx;
    uint32_t crc;
  };

  // Read the next valid record at p across segment boundaries, advancing p.
  // false = no more written data. Corrupt records are skipped (counted once).
  bool read_one_(Pos &p, RideRecord &out) {
    while (p.seg < write_seg_ || (p.seg == write_seg_ && p.idx < write_count_)) {
      FILE *f = fopen(seg_path_(p.seg).c_str(), "rb");
      if (f == nullptr) {
        if (p.seg < write_seg_) {
          p = Pos{p.seg + 1, 0};
          continue;
        }
        return false;
      }
      bool seek_ok = fseek(f, (long) p.idx * (long) sizeof(RideRecord), SEEK_SET) == 0;
      size_t got = seek_ok ? fread(&out, sizeof(out), 1, f) : 0;
      fclose(f);
      if (got != 1) {
        if (p.seg < write_seg_) {
          p = Pos{p.seg + 1, 0};
          continue;
        }
        return false;
      }
      p.idx++;
      if (!ride_valid(out))
        continue;
      return true;
    }
    return false;
  }

  std::string seg_path_(uint32_t n) const {
    char buf[24];
    snprintf(buf, sizeof(buf), "/%08u.seg", (unsigned) n);
    return dir_ + buf;
  }
  std::string meta_path_() const { return dir_ + "/meta.bin"; }

  // FAT returns upper-case short names ("00000001.SEG"); match either case.
  static bool parse_seg_name_(const char *name, uint32_t &n) {
    if (strlen(name) != 12 || name[8] != '.')
      return false;
    for (int i = 0; i < 8; i++)
      if (name[i] < '0' || name[i] > '9')
        return false;
    if (!((name[9] | 0x20) == 's' && (name[10] | 0x20) == 'e' && (name[11] | 0x20) == 'g'))
      return false;
    n = 0;
    for (int i = 0; i < 8; i++)
      n = n * 10 + (uint32_t)(name[i] - '0');
    return true;
  }

  static uint32_t file_size_(const std::string &path) {
    struct stat st;
    if (stat(path.c_str(), &st) != 0)
      return 0;
    return st.st_size > 0 ? (uint32_t) st.st_size : 0;
  }
  uint32_t seg_records_(uint32_t n) const { return file_size_(seg_path_(n)) / sizeof(RideRecord); }

  void recount_() {
    unacked_ = 0;
    for (uint32_t s = ack_.seg; s < write_seg_; s++) {
      uint32_t c = seg_records_(s);
      if (s == ack_.seg)
        c = c > ack_.idx ? c - ack_.idx : 0;
      unacked_ += c;
    }
  }

  // Records between the acknowledged position and the end of what has been written,
  // including the segment currently being appended to (recount_() runs at start-up,
  // when that segment is still empty, and deliberately leaves it out).
  void recount_all_() {
    uint32_t total = 0;
    for (uint32_t sg = ack_.seg; sg <= write_seg_; sg++) {
      uint32_t c = (sg == write_seg_) ? write_count_ : seg_records_(sg);
      if (sg == ack_.seg)
        c = c > ack_.idx ? c - ack_.idx : 0;
      total += c;
    }
    unacked_ = total;
  }

  // Skip over positions that are at/after the end of an already-closed segment.
  void normalize_ack_() {
    while (ack_.seg < write_seg_ && ack_.idx >= seg_records_(ack_.seg))
      ack_ = Pos{ack_.seg + 1, 0};
  }

  void drop_finished_segments_() {
    while (first_seg_ < ack_.seg && first_seg_ < write_seg_) {
      delete_seg_(first_seg_);
      first_seg_++;
    }
  }

  void delete_seg_(uint32_t n) {
    uint32_t sz = file_size_(seg_path_(n));
    if (sz > 0) {
      remove(seg_path_(n).c_str());
      total_bytes_ = total_bytes_ > sz ? total_bytes_ - sz : 0;
    }
  }

  // Called before starting a new segment: evict the oldest ones if the next
  // segment would exceed the card budget (ring buffer behaviour).
  void make_room_() {
    bool changed = false;
    while (total_bytes_ + SEGMENT_BYTES > max_bytes_ && first_seg_ < write_seg_) {
      uint32_t n = first_seg_;
      uint32_t recs = seg_records_(n);
      if (ack_.seg <= n) {  // this segment still holds unacknowledged records
        uint32_t lost = (ack_.seg == n) ? (recs > ack_.idx ? recs - ack_.idx : 0) : recs;
        dropped_ += lost;
        unacked_ = unacked_ > lost ? unacked_ - lost : 0;
        ack_ = Pos{n + 1, 0};
        changed = true;
      }
      delete_seg_(n);
      first_seg_++;
      // Anything in flight that pointed into the evicted data is void.
      while (!window_.empty() && window_.front().after.seg <= n)
        window_.pop_front();
      if (send_.seg < ack_.seg || (send_.seg == ack_.seg && send_.idx < ack_.idx))
        send_ = ack_;
    }
    if (changed)
      save_meta_();
  }

  // ---- position persistence: two slots, newest valid one wins ----
  static uint32_t slot_crc_(const MetaSlot &s) { return ride_crc32(&s, offsetof(MetaSlot, crc)); }

  bool load_meta_(Pos &out) {
    FILE *f = fopen(meta_path_().c_str(), "rb");
    if (f == nullptr)
      return false;
    MetaSlot slots[2];
    size_t got = fread(slots, sizeof(MetaSlot), 2, f);
    fclose(f);
    bool have = false;
    uint32_t best_gen = 0;
    for (size_t i = 0; i < got && i < 2; i++) {
      if (slots[i].crc != slot_crc_(slots[i]))
        continue;
      if (!have || slots[i].gen > best_gen) {
        have = true;
        best_gen = slots[i].gen;
        out = Pos{slots[i].seg, slots[i].idx};
      }
    }
    meta_gen_ = best_gen;
    return have;
  }

  void save_meta_() {
    MetaSlot s{};
    s.gen = ++meta_gen_;
    s.seg = ack_.seg;
    s.idx = ack_.idx;
    s.crc = slot_crc_(s);
    FILE *f = fopen(meta_path_().c_str(), "r+b");
    if (f == nullptr) {
      f = fopen(meta_path_().c_str(), "w+b");
      if (f == nullptr)
        return;
      MetaSlot blank{};
      fwrite(&blank, sizeof(blank), 1, f);
      fwrite(&blank, sizeof(blank), 1, f);
    }
    fseek(f, (long) (s.gen & 1u) * (long) sizeof(MetaSlot), SEEK_SET);
    fwrite(&s, sizeof(s), 1, f);
    fclose(f);
  }

  std::string dir_;
  uint32_t max_bytes_;
  bool opened_{false};
  uint32_t first_seg_{1};
  uint32_t write_seg_{1};
  uint32_t write_count_{0};
  uint32_t total_bytes_{0};
  uint32_t unacked_{0};
  uint32_t dropped_{0};
  uint32_t corrupt_{0};
  uint32_t meta_gen_{0};
  int last_errno_{0};
  Pos ack_{};
  Pos send_{};
  std::deque<InFlight> window_;
};

}  // namespace ride_data_logger
}  // namespace esphome
