#!/bin/bash
set -euo pipefail

# Set up definitions
curl -u user:"$RABBITMQ_PASSWORD" \
    --fail-with-body \
    --silent \
    -X POST \
    -H "Content-Type: application/json" \
    -T /tmp/rabbitmq/definitions.json \
    http://renditions-rabbitmq:15672/api/definitions
