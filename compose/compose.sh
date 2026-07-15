#!/bin/bash

set -e

INCLUDE_COMPOSE_FILES=(docker-compose.*.yml)

do_all() {
    local args=""
    local file=""

    for file in "${INCLUDE_COMPOSE_FILES[@]}"; do
        args+=" --file ${file}"
    done

    exec docker compose $args "$@"
}

do_all "$@"
