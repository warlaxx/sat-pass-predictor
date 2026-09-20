#!/usr/bin/env bash
# Measures what a call to /api/passes costs, so that a price is set on a measured number
# rather than on an impression. Two figures come out of it:
#
#   * latency as the caller sees it, p50 and p95, including HTTP and JSON serialisation;
#   * time spent inside the propagation, read from the server's own timer. That is
#     elapsed time, not CPU time: a Micrometer Timer measures a duration, and nothing
#     here samples the thread's CPU clock. On a single-threaded run of this kind the two
#     are close, but they are not the same number and the output does not pretend they
#     are.
#
# Run it twice, once with PREDICTION_CACHE_ENABLED=false on the server and once with the
# default, and the difference is milestone 12. The endpoint is /api/passes: the anonymous
# demo identity, so the run does not consume a customer's quota.
set -euo pipefail

URL="http://localhost:8080"
CALLS=200
NORAD=25544
LAT=45.7578
LON=4.8320
HOURS=48
DISTINCT=1

usage() {
  cat <<'USAGE'
Usage: measure-passes.sh [options]
  --url URL        base address of the API (default http://localhost:8080)
  --calls N        number of requests (default 200)
  --norad ID       NORAD number (default 25544, the ISS)
  --lat / --lon    observer, in degrees (default Lyon)
  --hours H        window, in hours (default 48)
  --distinct N     number of distinct observers to cycle through (default 1).
                   1 measures the repeated call the cache exists for; a number equal to
                   --calls measures the propagation with the cache present but useless,
                   which is the honest upper bound on a cold workload.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --calls) CALLS="$2"; shift 2 ;;
    --norad) NORAD="$2"; shift 2 ;;
    --lat) LAT="$2"; shift 2 ;;
    --lon) LON="$2"; shift 2 ;;
    --hours) HOURS="$2"; shift 2 ;;
    --distinct) DISTINCT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

# Both are divisors further down - the call count for the percentiles, the observer
# cycle in the request loop. Zero would not produce a wrong measurement, it would
# produce an arithmetic error halfway through one.
positive() { # positive <flag> <value>
  if ! [[ "$2" =~ ^[0-9]+$ ]] || [[ "$2" -lt 1 ]]; then
    echo "$1 must be a positive integer (got '$2')" >&2
    exit 2
  fi
}
positive --calls "$CALLS"
positive --distinct "$DISTINCT"

TIMINGS="$(mktemp)"
trap 'rm -f "$TIMINGS"' EXIT

# The actuator returns one JSON object per measurement. Splitting records on braces
# gives one record per measurement, so the statistic and its value are read together
# rather than by counting lines.
metric() { # metric <path> <statistic>
  curl -fsS "$URL/actuator/metrics/$1" 2>/dev/null | awk -v want="$2" '
    BEGIN { RS = "[{}]" }
    $0 ~ "\"statistic\":\"" want "\"" && match($0, /"value":[-0-9.eE+]+/) {
      print substr($0, RSTART + 8, RLENGTH - 8); exit
    }'
}

counter() { # counter <result tag>
  metric "satpass.predictions?tag=result:$1" COUNT
}

echo "Warming up (JIT, Orekit frame tables, the first TLE fetch) ..."
curl -fsS -o /dev/null "$URL/api/passes?noradId=$NORAD&lat=$LAT&lon=$LON&hours=$HOURS" \
  || { echo "the API did not answer at $URL" >&2; exit 1; }

BEFORE_HIT="$(counter hit)"; BEFORE_MISS="$(counter miss)"
BEFORE_TOTAL="$(metric satpass.prediction.duration TOTAL_TIME)"

echo "Measuring $CALLS calls over $DISTINCT distinct observer(s) ..."
for ((i = 0; i < CALLS; i++)); do
  # Nudging the longitude by whole ten-thousandths of a degree makes a genuinely
  # different request without moving the observer more than a few metres.
  offset=$(awk -v i="$i" -v d="$DISTINCT" 'BEGIN { printf "%.4f", (i % d) * 0.0001 }')
  lon=$(awk -v l="$LON" -v o="$offset" 'BEGIN { printf "%.4f", l + o }')
  curl -fsS -o /dev/null -w '%{time_total}\n' \
    "$URL/api/passes?noradId=$NORAD&lat=$LAT&lon=$lon&hours=$HOURS" >> "$TIMINGS"
done

AFTER_HIT="$(counter hit)"; AFTER_MISS="$(counter miss)"
AFTER_TOTAL="$(metric satpass.prediction.duration TOTAL_TIME)"

sort -g "$TIMINGS" | awk -v calls="$CALLS" '
  { t[NR] = $1 * 1000; sum += $1 * 1000 }
  END {
    printf "\nLatency as the caller sees it, over %d calls\n", NR
    printf "  p50 %8.1f ms\n", t[int(NR * 0.50) + (NR > 1)]
    printf "  p95 %8.1f ms\n", t[int(NR * 0.95) + (int(NR * 0.95) < NR)]
    printf "  max %8.1f ms\n", t[NR]
    printf "  avg %8.1f ms\n", sum / NR
  }'

awk -v bh="${BEFORE_HIT:-0}" -v ah="${AFTER_HIT:-0}" \
    -v bm="${BEFORE_MISS:-0}" -v am="${AFTER_MISS:-0}" \
    -v bt="${BEFORE_TOTAL:-0}" -v at="${AFTER_TOTAL:-0}" -v calls="$CALLS" '
  BEGIN {
    hits = ah - bh; misses = am - bm; seconds = at - bt;
    printf "\nServer side\n"
    if (hits + misses == 0) {
      print "  no metrics: is management.endpoints.web.exposure.include missing \"metrics\"?"
      exit
    }
    printf "  cache      %d hit / %d miss  (%.1f%% served without propagating)\n",
           hits, misses, 100 * hits / (hits + misses)
    printf "  propagation %8.3f s elapsed for %d calls (not CPU time)\n", seconds, calls
    printf "  per 1000 calls %6.2f s of propagation\n", 1000 * seconds / calls
  }'
