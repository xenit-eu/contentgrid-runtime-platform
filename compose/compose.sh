#!/bin/bash

set -e

PLATFORM_COMPOSE_FILE=docker-compose.rtp.yml
# these can contain overrides for the platform compose file
INCLUDE_COMPOSE_FILES=("${PLATFORM_COMPOSE_FILE}")
for file in docker-compose.*.yml; do
    if [ "${file}" != "${PLATFORM_COMPOSE_FILE}" ]; then
        INCLUDE_COMPOSE_FILES+=("${file}")
    fi
done

do_all() {
    local args=""
    local file=""

    for file in "${INCLUDE_COMPOSE_FILES[@]}"; do
        args+=" --file ${file}"
    done

    exec docker compose $args "$@"
}

do_all "$@"
