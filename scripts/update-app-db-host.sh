#!/usr/bin/env bash
# Points every app database secret to a different database server.
#
# Iterates over all secrets (in all namespaces) that have
#   label      app.contentgrid.com/service-type=api
#   annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
# and replaces host and port in spring.datasource.url, keeping the database name and parameters.
#
# Runs in dry-run mode by default; set DRY_RUN=0 to actually update the secrets.
#
# Usage: TO=<host>[:<port>] [FROM=<host>[:<port>]] [DRY_RUN=0] update-app-db-host.sh [kubectl args, e.g. --context foo]
#
# Environment:
#   TO          host[:port] to put in spring.datasource.url
#   FROM        only update secrets currently pointing to this host[:port] (port defaults to 5432)
#   DRY_RUN=0   actually update the secrets (default: 1, only print what would be done)
set -euo pipefail

# psql handles ^C itself (cancelling the running query) and exits with a normal error, so without this the script would
# just continue with the next item
trap 'echo "Interrupted" >&2; exit 130' INT

DRY_RUN="${DRY_RUN:-1}"
: "${TO:?TO must be set to the new database host[:port]}"
FROM="${FROM:-}"

HOST_PORT_REGEX='^[^/:?]+(:[0-9]+)?$'
[[ "${TO}" =~ ${HOST_PORT_REGEX} ]] || { echo "TO must be host[:port]" >&2; exit 1; }
[[ -z "${FROM}" || "${FROM}" =~ ${HOST_PORT_REGEX} ]] || { echo "FROM must be host[:port]" >&2; exit 1; }

# Adds the default port, so host and host:5432 compare equal
with_port() {
    [[ "$1" == *:* ]] && echo "$1" || echo "$1:5432"
}

LABEL_SELECTOR='app.contentgrid.com/service-type=api'
# Dots escaped for jsonpath
ANNOTATION_PATH='api\.sp\.captain\.contentgrid\.com/db-access-credentials-id'
URL_KEY='spring.datasource.url'

if [[ "${DRY_RUN}" != "0" ]]; then
    echo "Dry run, no secrets will be changed. Set DRY_RUN=0 to apply." >&2
fi

process_secret() {
    local namespace="$1" name="$2"
    local url

    url="$(kubectl "${KUBECTL_ARGS[@]}" get secret -n "${namespace}" "${name}" \
        -o jsonpath="{.data.${URL_KEY//./\\.}}" </dev/null | base64 -d)" || return 1

    # jdbc:postgresql://host[:port]/database[?params]
    if [[ ! "${url}" =~ ^(jdbc:postgresql://)([^/:?]+(:[0-9]+)?)(/.*)$ ]]; then
        echo "  can not parse ${URL_KEY} '${url}'" >&2
        return 1
    fi
    local prefix="${BASH_REMATCH[1]}" host_port="${BASH_REMATCH[2]}" rest="${BASH_REMATCH[4]}"

    if [[ -n "${FROM}" && "$(with_port "${host_port}")" != "$(with_port "${FROM}")" ]]; then
        echo "  skipped, points to ${host_port}"
        return 0
    fi

    local new_url="${prefix}${TO}${rest}"
    if [[ "${new_url}" == "${url}" ]]; then
        echo "  already up to date"
        return 0
    fi

    echo "  ${url} -> ${new_url}"
    if [[ "${DRY_RUN}" != "0" ]]; then
        return 0
    fi

    kubectl "${KUBECTL_ARGS[@]}" patch secret -n "${namespace}" "${name}" --type merge \
        -p "{\"data\":{\"${URL_KEY}\":\"$(printf '%s' "${new_url}" | base64 -w0)\"}}" </dev/null >/dev/null
}

KUBECTL_ARGS=("$@")

# Label filtering happens server-side; annotations can't be selected on, so filter with a jsonpath existence check
secrets="$(kubectl "${KUBECTL_ARGS[@]}" get secrets --all-namespaces -l "${LABEL_SELECTOR}" \
    -o jsonpath="{range .items[?(@.metadata.annotations.${ANNOTATION_PATH})]}{.metadata.namespace}{'\t'}{.metadata.name}{'\n'}{end}")"

failed=()
count=0

while IFS=$'\t' read -r namespace name; do
    [[ -z "${namespace}" ]] && continue
    count=$((count + 1))
    echo "${namespace}/${name}"
    if ! process_secret "${namespace}" "${name}"; then
        echo "  FAILED" >&2
        failed+=("${namespace}/${name}")
    fi
done <<<"${secrets}"

echo "Processed ${count} secret(s), ${#failed[@]} failed"
if (( ${#failed[@]} > 0 )); then
    printf '  %s\n' "${failed[@]}" >&2
    exit 1
fi
