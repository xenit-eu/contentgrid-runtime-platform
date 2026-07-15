#!/bin/bash
set -euo pipefail
for i in jwt-apps jwt-authentication jwt-authentication-encryption-keys; do
    mkdir -p "$i"
    pushd "$i"
    openssl genrsa 2048 > active-1.pem
    popd
done
