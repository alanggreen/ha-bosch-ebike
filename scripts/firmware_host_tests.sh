#!/usr/bin/env bash
# Host tests that need no hardware: the SD ring-buffer store (C++) and the dependency-free Python tests (same as CI).
set -u
cd "$(dirname "$0")/.."
fail=0

echo "== ride_log_store (C++) =="
g++ -std=c++17 -Wall -Wextra -I esphome/components/ride_data_logger tests/ride_log_store_test.cpp -o /tmp/ride_log_store_test \
  && /tmp/ride_log_store_test || fail=1

echo "== Python tests =="
for f in tests/test_*.py; do
  python3 "$f" > /tmp/pytest_out.txt 2>&1 && echo "ok   $f" || { echo "FAIL $f"; tail -15 /tmp/pytest_out.txt; fail=1; }
done

[ "$fail" = 0 ] && echo "ALL HOST TESTS PASSED" || echo "SOME HOST TESTS FAILED"
exit $fail
