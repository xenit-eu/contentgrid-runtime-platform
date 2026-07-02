#!/bin/bash
set -euo pipefail
for i in jwt-delegated jwt-external jwt-system; do
    mkdir -p "$i"
    pushd "$i"
    openssl genrsa 2048 > active-1.pem
    popd
done
