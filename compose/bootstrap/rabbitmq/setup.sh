#!/bin/bash
set -euo pipefail

for username in slingshot surveyor apps; do
    generated_pwd="$(openssl rand -base64 30)"

    echo "$generated_pwd" | jq -R '{ password: ., tags: [] }' | \
        curl -u user:"$RABBITMQ_PASSWORD" \
        --fail-with-body \
        --silent \
        -X PUT \
        --json @- \
        http://rabbitmq:15672/api/users/$username
done

# Set up definitions
curl -u user:"$RABBITMQ_PASSWORD" \
    --fail-with-body \
    --silent \
    -X POST \
    -H "Content-Type: application/json" \
    -T /tmp/rabbitmq/definitions.json \
    http://rabbitmq:15672/api/definitions
