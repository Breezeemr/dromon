#!/bin/bash
# Runs the datomic backend benchmark end-to-end: starts a fresh local Datomic
# dev transactor, waits for it, runs the bench, then stops the transactor.
# Intended to be invoked as a single background job (the transactor and bench
# then share one process lifetime).
set -uo pipefail

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
DATOMIC_DIR="$BENCH_DIR/../../local-datomic/datomic-pro"

echo "[run-datomic] cleaning previous transactor + storage"
pkill -9 -f 'datomic.launcher' 2>/dev/null || true
sleep 2
rm -rf "$DATOMIC_DIR/data"

echo "[run-datomic] starting transactor"
( cd "$DATOMIC_DIR" && exec bin/transactor -Xmx4g config/samples/dev-transactor-template.properties ) \
  > /tmp/transactor.log 2>&1 &
TX_PID=$!

echo "[run-datomic] waiting for transactor on 4334 (pid $TX_PID)"
for i in $(seq 1 60); do
  if (exec 3<>/dev/tcp/localhost/4334) 2>/dev/null; then
    echo "[run-datomic] transactor up after ${i}s"
    break
  fi
  if ! kill -0 "$TX_PID" 2>/dev/null; then
    echo "[run-datomic] ERROR: transactor died during startup. Log:"
    cat /tmp/transactor.log
    exit 1
  fi
  sleep 1
done

echo "[run-datomic] running benchmark"
( cd "$BENCH_DIR" && clojure -X:datomic fhir-search-bench.bench/run :backend :datomic "$@" )
RC=$?

echo "[run-datomic] stopping transactor"
kill "$TX_PID" 2>/dev/null || true
pkill -9 -f 'datomic.launcher' 2>/dev/null || true

echo "[run-datomic] done rc=$RC"
exit $RC
