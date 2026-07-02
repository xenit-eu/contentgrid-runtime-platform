#!/bin/bash

HOST="localhost"
PORT=9000
PATH_="/health/ready"
TIMEOUT=5

response=$(
    exec 3<>"/dev/tcp/$HOST/$PORT" || exit 1
    printf 'GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "$PATH_" "$HOST" >&3
    timeout "$TIMEOUT" cat <&3
)

status_line=$(echo "$response" | head -n1)
status_line=${status_line%$'\r'}      # strip trailing \r
status_code=${status_line#* }          # remove "HTTP/1.1 " prefix
status_code=${status_code%% *}         # remove trailing " OK" etc.

if [[ "$status_code" -ge 200 && "$status_code" -lt 300 ]]; then
    echo "OK: $status_code"
    exit 0
else
    echo "FAIL: $status_code"
    exit 1
fi
