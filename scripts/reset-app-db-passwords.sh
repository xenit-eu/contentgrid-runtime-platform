#!/usr/bin/env bash
# Resets the database password of every app database user to the password stored in its secret.
#
# Iterates over all secrets (in all namespaces) that have
#   label      app.contentgrid.com/service-type=api
#   annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
# reads spring.datasource.{url,username,password} from them, connects to the database server from the url
# and runs ALTER ROLE <username> PASSWORD <password>.
#
# Runs in dry-run mode by default; set DRY_RUN=0 to actually change passwords.
#
# Usage: [DRY_RUN=0 PGUSER=<admin> PGPASSWORD=<admin password>] reset-app-db-passwords.sh [kubectl args, e.g. --context foo]
#
# Environment:
#   PGUSER, PGPASSWORD  admin credentials used to connect to the database server (any libpq env var works, e.g. PGSSLMODE)
#   PGHOST, PGPORT      override host/port parsed from spring.datasource.url
#   PGDATABASE          database to connect to (default: database from spring.datasource.url)
#   DRY_RUN=0           actually change the passwords (default: 1, only print what would be done)
set -euo pipefail

DRY_RUN="${DRY_RUN:-1}"

LABEL_SELECTOR='app.contentgrid.com/service-type=api'
# Dots escaped for jsonpath
ANNOTATION_PATH='api\.sp\.captain\.contentgrid\.com/db-access-credentials-id'

if [[ "${DRY_RUN}" != "0" ]]; then
    echo "Dry run, no passwords will be changed. Set DRY_RUN=0 to apply." >&2
else
    : "${PGUSER:?PGUSER must be set to the database admin user}"
    : "${PGPASSWORD:?PGPASSWORD must be set to the database admin password}"
    command -v psql >/dev/null || { echo "psql is required" >&2; exit 1; }
fi

secret_value() {
    local namespace="$1" name="$2" key="$3"
    kubectl "${KUBECTL_ARGS[@]}" get secret -n "${namespace}" "${name}" \
        -o jsonpath="{.data.${key//./\\.}}" </dev/null | base64 -d
}

process_secret() {
    local namespace="$1" name="$2" credentials_id="$3"
    local url username password

    url="$(secret_value "${namespace}" "${name}" spring.datasource.url)" || return 1
    username="$(secret_value "${namespace}" "${name}" spring.datasource.username)" || return 1
    password="$(secret_value "${namespace}" "${name}" spring.datasource.password)" || return 1

    if [[ -z "${username}" || -z "${password}" ]]; then
        echo "  missing spring.datasource.username or spring.datasource.password" >&2
        return 1
    fi

    # jdbc:postgresql://host[:port]/database[?params]
    if [[ ! "${url}" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
        echo "  can not parse spring.datasource.url '${url}'" >&2
        return 1
    fi
    local host="${PGHOST:-${BASH_REMATCH[1]}}"
    local port="${PGPORT:-${BASH_REMATCH[3]:-5432}}"
    local database="${PGDATABASE:-${BASH_REMATCH[4]}}"

    echo "  ALTER ROLE \"${username}\" on ${host}:${port}/${database} (credentials id ${credentials_id})"
    if [[ "${DRY_RUN}" != "0" ]]; then
        return 0
    fi

    # Escape for use as SQL identifier/literal; SQL goes over stdin so the password doesn't show up in the process list
    local quoted_username="\"${username//\"/\"\"}\""
    local quoted_password="'${password//\'/\'\'}'"
    psql --no-psqlrc --quiet -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -d "${database}" <<<"ALTER ROLE ${quoted_username} WITH PASSWORD ${quoted_password};"
}

KUBECTL_ARGS=("$@")

# Label filtering happens server-side; annotations can't be selected on, so filter with a jsonpath existence check
secrets="$(kubectl "${KUBECTL_ARGS[@]}" get secrets --all-namespaces -l "${LABEL_SELECTOR}" \
    -o jsonpath="{range .items[?(@.metadata.annotations.${ANNOTATION_PATH})]}{.metadata.namespace}{'\t'}{.metadata.name}{'\t'}{.metadata.annotations.${ANNOTATION_PATH}}{'\n'}{end}")"

failed=()
count=0

while IFS=$'\t' read -r namespace name credentials_id; do
    [[ -z "${namespace}" ]] && continue
    count=$((count + 1))
    echo "${namespace}/${name}"
    if ! process_secret "${namespace}" "${name}" "${credentials_id}"; then
        echo "  FAILED" >&2
        failed+=("${namespace}/${name}")
    fi
done <<<"${secrets}"

echo "Processed ${count} secret(s), ${#failed[@]} failed"
if (( ${#failed[@]} > 0 )); then
    printf '  %s\n' "${failed[@]}" >&2
    exit 1
fi
