#!/usr/bin/env bash

set -Eeuo pipefail

ELASTICSEARCH_IMAGE="docker.elastic.co/elasticsearch/elasticsearch:8.19.17"

DATA_ROOT="/docker"
NETWORK_NAME="rag-net"
BIND_ADDRESS="${BIND_ADDRESS:-127.0.0.1}"
ELASTICSEARCH_HOST_PORT="${ELASTICSEARCH_HOST_PORT:-19200}"
VOLUME_SUFFIX=""

ELASTICSEARCH_CONTAINER="rag-elasticsearch"

log() {
    printf '[rag-infrastructure] %s\n' "$*"
}

fail() {
    printf '[rag-infrastructure] ERROR: %s\n' "$*" >&2
    exit 1
}

container_exists() {
    docker container inspect "$1" >/dev/null 2>&1
}

container_running() {
    [ "$(docker container inspect --format '{{.State.Running}}' "$1" 2>/dev/null || true)" = "true" ]
}

start_existing_container() {
    local container_name="$1"

    if container_running "${container_name}"; then
        log "${container_name} is already running; keeping the existing container."
    else
        log "Starting existing container ${container_name}."
        docker start "${container_name}" >/dev/null
    fi
}

wait_for_health() {
    local container_name="$1"
    local timeout_seconds="$2"
    local elapsed=0
    local status

    while [ "${elapsed}" -lt "${timeout_seconds}" ]; do
        status="$(docker container inspect \
            --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' \
            "${container_name}" 2>/dev/null || true)"

        case "${status}" in
            healthy | running)
                log "${container_name} status: ${status}"
                return 0
                ;;
            unhealthy | exited | dead)
                docker logs --tail 80 "${container_name}" >&2 || true
                fail "${container_name} entered status: ${status}"
                ;;
        esac

        sleep 3
        elapsed=$((elapsed + 3))
    done

    docker logs --tail 80 "${container_name}" >&2 || true
    fail "Timed out waiting for ${container_name} to become healthy."
}

if [ "$(id -u)" -ne 0 ]; then
    fail "Run this script as root: sudo bash $0"
fi

command -v docker >/dev/null 2>&1 || fail "Docker is not installed."
docker info >/dev/null 2>&1 || fail "Docker daemon is not running."

if command -v getenforce >/dev/null 2>&1 && [ "$(getenforce)" = "Enforcing" ]; then
    log "SELinux enforcing mode detected; enabling private bind-mount labels."
    VOLUME_SUFFIX=":Z"
fi

log "Creating persistent data directories under ${DATA_ROOT}."
install -d -m 0770 -g 0 "${DATA_ROOT}/elasticsearch/data"

# Elasticsearch runs as uid:gid 1000:0. Its bind-mounted data directory must
# be writable by gid 0.
chgrp -R 0 "${DATA_ROOT}/elasticsearch"
chmod -R g+rwx "${DATA_ROOT}/elasticsearch"

log "Configuring vm.max_map_count for Elasticsearch."
printf 'vm.max_map_count=1048576\n' > /etc/sysctl.d/99-elasticsearch.conf
sysctl -w vm.max_map_count=1048576 >/dev/null

if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
    log "Creating Docker network ${NETWORK_NAME}."
    docker network create "${NETWORK_NAME}" >/dev/null
fi

log "Pulling the pinned Elasticsearch image."
docker pull "${ELASTICSEARCH_IMAGE}"

if container_exists "${ELASTICSEARCH_CONTAINER}"; then
    start_existing_container "${ELASTICSEARCH_CONTAINER}"
else
    log "Creating ${ELASTICSEARCH_CONTAINER}."
    docker run -d \
        --name "${ELASTICSEARCH_CONTAINER}" \
        --restart unless-stopped \
        --network "${NETWORK_NAME}" \
        --memory 1g \
        --memory-swap 1536m \
        --ulimit nofile=65535:65535 \
        -p "${BIND_ADDRESS}:${ELASTICSEARCH_HOST_PORT}:9200" \
        -e "discovery.type=single-node" \
        -e "xpack.security.enabled=false" \
        -e "ES_JAVA_OPTS=-Xms512m -Xmx512m" \
        -e "TZ=Asia/Shanghai" \
        -v "${DATA_ROOT}/elasticsearch/data:/usr/share/elasticsearch/data${VOLUME_SUFFIX}" \
        --health-cmd='curl -fsS http://localhost:9200/_cluster/health >/dev/null || exit 1' \
        --health-interval=10s \
        --health-timeout=5s \
        --health-retries=20 \
        --health-start-period=60s \
        "${ELASTICSEARCH_IMAGE}" >/dev/null
fi

wait_for_health "${ELASTICSEARCH_CONTAINER}" 240

log "Elasticsearch is running."
docker ps \
    --filter "name=${ELASTICSEARCH_CONTAINER}" \
    --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'

printf 'Elasticsearch: http://%s:%s\n' \
    "${BIND_ADDRESS}" "${ELASTICSEARCH_HOST_PORT}"
