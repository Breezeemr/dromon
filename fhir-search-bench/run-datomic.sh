#!/bin/bash
# Runs the datomic backend benchmark end-to-end against master-at-arms2's shared
# dev Datomic transactor (hasch image, :4334; `bb datomic-up` at the ma2 root).
# The transactor is started if needed and left running, since other projects use
# it. The bench writes under a random database prefix and deletes its tenant when
# it finishes.
set -uo pipefail

# Uses the ambient JVM (Java 21+). XTDB/Datomic require Java 21; the xtdb run
# also needs --sun-misc-unsafe-memory-access=allow on Java 24+ (see :xtdb alias).

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "[run-datomic] ensuring the shared transactor is up"
( cd "$BENCH_DIR/../.." && bb datomic-up ) || exit 1

echo "[run-datomic] running benchmark"
( cd "$BENCH_DIR" && clojure -X:datomic fhir-search-bench.bench/run :backend :datomic "$@" )
RC=$?

echo "[run-datomic] done rc=$RC"
exit $RC
