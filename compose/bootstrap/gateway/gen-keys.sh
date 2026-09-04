#!/bin/bash
set -euo pipefail
for i in jwt-apps jwt-authentication; do
    mkdir -p "$i"
    pushd "$i"
    openssl genrsa 2048 > active-1.pem
    popd
done
mkdir -p jwt-authentication-encryption
openssl rand 32 > jwt-authentication-encryption/active-1.bin
