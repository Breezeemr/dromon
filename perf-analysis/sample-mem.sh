#!/usr/bin/env bash
# Sample JVM memory of the dromon test-server process every SAMPLE_INTERVAL seconds.
# Writes CSV to perf-analysis/mem-samples.csv. Mirrors
# datomic-test-server/perf-analysis/sample-mem.sh so the two runs are comparable.
set -u
OUT="$(dirname "$0")/mem-samples.csv"
INTERVAL="${SAMPLE_INTERVAL:-3}"
JCMD=/usr/lib/jvm/java-21-openjdk-amd64/bin/jcmd
[ -x "$JCMD" ] || JCMD=jcmd

echo "ts,pid,rss_kb,heap_used_mb,heap_committed_mb,heap_max_mb,nonheap_used_mb,gc_young_count,gc_young_ms,gc_old_count,gc_old_ms" > "$OUT"

while true; do
  PID="$(pgrep -f 'test-server.core/-main' | head -n1)"
  if [ -z "$PID" ]; then
    sleep "$INTERVAL"; continue
  fi
  TS=$(date -u +%s)
  RSS=$(awk '/VmRSS/ {print $2}' /proc/"$PID"/status 2>/dev/null || echo 0)
  MEM=$("$JCMD" "$PID" GC.heap_info 2>/dev/null || true)
  HEAP_USED=$(echo "$MEM" | awk '/used /{for(i=1;i<=NF;i++) if($i=="used") {gsub("K","",$(i+1)); print int($(i+1)/1024); exit}}')
  HEAP_CMT=$(echo "$MEM"  | awk '/committed /{for(i=1;i<=NF;i++) if($i=="committed") {gsub("K","",$(i+1)); print int($(i+1)/1024); exit}}')
  HEAP_MAX=$(echo "$MEM"  | awk '/reserved /{for(i=1;i<=NF;i++) if($i=="reserved") {gsub("K","",$(i+1)); print int($(i+1)/1024); exit}}')
  GCI=$("$JCMD" "$PID" PerfCounter.print 2>/dev/null || true)
  YCNT=$(echo "$GCI" | awk -F= '/sun.gc.collector.0.invocations=/ {gsub(/[^0-9]/,"",$2); print $2; exit}')
  YMS=$(echo "$GCI"  | awk -F= '/sun.gc.collector.0.time=/          {gsub(/[^0-9]/,"",$2); print $2; exit}')
  OCNT=$(echo "$GCI" | awk -F= '/sun.gc.collector.1.invocations=/ {gsub(/[^0-9]/,"",$2); print $2; exit}')
  OMS=$(echo "$GCI"  | awk -F= '/sun.gc.collector.1.time=/          {gsub(/[^0-9]/,"",$2); print $2; exit}')
  NHU=$(echo "$GCI"  | awk -F= '/java.cls.loadedClasses=/ {gsub(/[^0-9]/,"",$2); print $2; exit}')
  echo "$TS,$PID,${RSS:-0},${HEAP_USED:-0},${HEAP_CMT:-0},${HEAP_MAX:-0},${NHU:-0},${YCNT:-0},${YMS:-0},${OCNT:-0},${OMS:-0}" >> "$OUT"
  sleep "$INTERVAL"
done
