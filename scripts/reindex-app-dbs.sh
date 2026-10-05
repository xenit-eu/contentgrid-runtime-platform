#!/usr/bin/env bash
# Rebuilds all indexes in every app database.
#
# Iterates over all secrets (in all namespaces) that have
#   label      app.contentgrid.com/service-type=api
#   annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
# reads spring.datasource.{url,username,password} from them and runs reindex-db.sh on the database from the url, which
# runs REINDEX TABLE on every table with indexes; see reindex-db.sh for details.
#
# The reindex is not concurrent: writes to each table (and reads using its indexes) are blocked while its indexes are
# rebuilt, so run this in a maintenance window.
#
# Runs in dry-run mode by default; set DRY_RUN=0 to actually reindex. A dry run still logs in to list the tables.
#
# Usage: [DRY_RUN=0] reindex-app-dbs.sh [kubectl args, e.g. --context foo]
#
# Environment:
#   PGUSER, PGPASSWORD  log in with these credentials instead of the ones from the secret
#                       (tables this user may not reindex are reported as errors)
#   DRY_RUN=0           actually reindex (default: 1, only print what would be done)
set -euo pipefail

# psql handles ^C itself (cancelling the running query) and exits with a normal error, so without this the script would
# just continue with the next item
trap 'echo "Interrupted" >&2; exit 130' INT

DRY_RUN="${DRY_RUN:-1}"

LABEL_SELECTOR='app.contentgrid.com/service-type=api'
# Dots escaped for jsonpath
ANNOTATION_PATH='api\.sp\.captain\.contentgrid\.com/db-access-credentials-id'

if [[ "${DRY_RUN}" != "0" ]]; then
    echo "Dry run, no databases will be reindexed. Set DRY_RUN=0 to apply." >&2
fi

REINDEX_DB="$(dirname "${BASH_SOURCE[0]}")/reindex-db.sh"

secret_value() {
    local namespace="$1" name="$2" key="$3"
    kubectl "${KUBECTL_ARGS[@]}" get secret -n "${namespace}" "${name}" \
        -o jsonpath="{.data.${key//./\\.}}" </dev/null | base64 -d
}

process_secret() {
    local namespace="$1" name="$2"
    local url username password

    url="$(secret_value "${namespace}" "${name}" spring.datasource.url)" || return 1
    username="${PGUSER:-$(secret_value "${namespace}" "${name}" spring.datasource.username)}" || return 1
    password="${PGPASSWORD:-$(secret_value "${namespace}" "${name}" spring.datasource.password)}" || return 1

    if [[ -z "${username}" || -z "${password}" ]]; then
        echo "  missing spring.datasource.username or spring.datasource.password" >&2
        return 1
    fi

    # jdbc:postgresql://host[:port]/database[?params] is a valid psql connection string without the jdbc: prefix;
    # the params are JDBC driver options that psql may not understand, so leave them out
    if [[ "${url}" != jdbc:postgresql://* ]]; then
        echo "  can not parse spring.datasource.url '${url}'" >&2
        return 1
    fi
    local connection_string="${url#jdbc:}"
    connection_string="${connection_string%%\?*}"

    # Password via environment so it doesn't show up in the process list
    PGPASSWORD="${password}" DRY_RUN="${DRY_RUN}" "${REINDEX_DB}" "${connection_string}" "${username}" 2>&1 \
        | sed 's/^/  /'
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
