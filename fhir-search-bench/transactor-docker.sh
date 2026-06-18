#!/bin/bash
# Dockerized Datomic dev transactor for the fhir-search-bench, replacing the
# in-repo bin/transactor. Isolated container on 4337 with its own data dir, so it
# never touches the :4334 PHI-realm transactor and never pkills datomic.launcher.
# The image already carries hasch on /app/lib (built by datomic-transactor-image/),
# so no DATOMIC_EXT_CLASSPATH is needed.
#
# Usage: transactor-docker.sh {start|stop|foreground}
#   start       run detached, wait for 4337/4338, return
#   stop        remove the container (idempotent)
#   foreground  run attached (Ctrl-C to stop); for manual `bb transactor`
set -uo pipefail

BENCH_DIR="$(cd "$(dirname "$0")" && pwd)"
DATOMIC_DIR="$BENCH_DIR/../../local-datomic/datomic-pro"
IMAGE="${DATOMIC_TRANSACTOR_IMAGE:-localhost/datomic-fhir-transactor:hasch-1.0.7622}"
CMD="${CONTAINER_CMD:-podman}"
NAME="fhir-bench-transactor"
DATA_DIR="$DATOMIC_DIR/data-fhir-bench"
CONF_DIR="$BENCH_DIR/target/datomic-conf"

RUN_ARGS=(--rm --name "$NAME" --network host
  -v "$DATA_DIR:/app/data"
  -v "$CONF_DIR/transactor.properties:/app/conf/transactor.properties:ro"
  -v "$CONF_DIR/logback.xml:/app/logconf/logback.xml:ro"
  "$IMAGE")

ensure_image() {
  if ! "$CMD" image exists "$IMAGE" 2>/dev/null; then
    echo "ERROR: transactor image not found: $IMAGE" >&2
    echo "  build it: (cd ../../datomic-transactor-image && ./build.bb --no-push)" >&2
    echo "  or set DATOMIC_TRANSACTOR_IMAGE to a pullable ref." >&2
    exit 1
  fi
}

write_config() {
  # Fresh database each run. data-dir is the CONTAINER path; the host DATA_DIR is
  # bind-mounted onto it.
  rm -rf "$DATA_DIR" "$CONF_DIR"
  mkdir -p "$DATA_DIR" "$CONF_DIR"
  cat > "$CONF_DIR/transactor.properties" <<EOF
protocol=dev
host=localhost
port=4337
data-dir=/app/data
memory-index-threshold=32m
memory-index-max=256m
object-cache-max=128m
EOF
  # Plain logback so the transactor does not need the prod Google Cloud Logging
  # layout (which pulls gson, absent from the image classpath).
  cat > "$CONF_DIR/logback.xml" <<'EOF'
<configuration>
  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
    <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n</pattern></encoder>
  </appender>
  <root level="INFO"><appender-ref ref="STDOUT"/></root>
</configuration>
EOF
}

# Free port 4337 by removing any isolated fhir transactor container (bench OR
# inferno). They share 4337 and must not coexist; both are throwaway dev
# containers. NEVER touches the :4334 realm.
cleanup() {
  "$CMD" rm -f "$NAME" fhir-store-transactor >/dev/null 2>&1 || true
}

wait_ports() {
  echo "[transactor] waiting for 4337/4338"
  for i in $(seq 1 60); do
    if (exec 3<>/dev/tcp/localhost/4337) 2>/dev/null \
       && (exec 4<>/dev/tcp/localhost/4338) 2>/dev/null; then
      echo "[transactor] up after ${i}s"
      return 0
    fi
    sleep 1
  done
  echo "[transactor] ERROR: not up after 60s; container logs:" >&2
  "$CMD" logs "$NAME" 2>&1 | tail -20 >&2
  return 1
}

case "${1:-foreground}" in
  start)
    ensure_image; write_config; cleanup
    "$CMD" run -d "${RUN_ARGS[@]}" >/dev/null
    wait_ports
    ;;
  stop)
    cleanup
    ;;
  foreground)
    ensure_image; write_config; cleanup
    trap cleanup EXIT INT TERM
    echo "[transactor] running $IMAGE on 4337 (Ctrl-C to stop)"
    "$CMD" run "${RUN_ARGS[@]}"
    ;;
  *)
    echo "usage: $0 {start|stop|foreground}" >&2
    exit 2
    ;;
esac
