#!/bin/bash
# Runs the datomic backend benchmark end-to-end against the DOCKERIZED Datomic
# transactor (datomic-transactor-image, hasch baked in). The container is
# isolated on 4337 with its own data dir -- it never touches the :4334 PHI-realm
# transactor and never pkills datomic.launcher. The container is always removed
# on exit (trap), even if the bench fails or is interrupted.
set -uo pipefail

# Uses the ambient JVM (Java 21+). XTDB/Datomic require Java 21; the xtdb run
# also needs --sun-misc-unsafe-memory-access=allow on Java 24+ (see :xtdb alias).

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"

cleanup() { "$BENCH_DIR/transactor-docker.sh" stop; }
trap cleanup EXIT

echo "[run-datomic] starting dockerized transactor"
"$BENCH_DIR/transactor-docker.sh" start || exit 1

echo "[run-datomic] running benchmark"
( cd "$BENCH_DIR" && clojure -X:datomic fhir-search-bench.bench/run :backend :datomic "$@" )
RC=$?

echo "[run-datomic] done rc=$RC"
exit $RC
