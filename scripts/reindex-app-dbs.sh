#!/usr/bin/env bash
# Rebuilds all indexes in every app database.
#
# Iterates over all secrets (in all namespaces) that have
#   label      app.contentgrid.com/service-type=api
#   annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
# reads spring.datasource.{url,username,password} from them, logs in to the database from the url
# and runs REINDEX TABLE on every table with indexes that the user may reindex (owner, or MAINTAIN privilege on
# PostgreSQL 17+). REINDEX DATABASE/SCHEMA would require owning the database/schema, which the app user doesn't
# (public is owned by the database owner). System schemas are skipped; tables the user may not reindex are reported as
# errors.
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
#   PGHOST, PGPORT      override host/port parsed from spring.datasource.url
#   DRY_RUN=0           actually reindex (default: 1, only print what would be done)
set -euo pipefail

DRY_RUN="${DRY_RUN:-1}"

LABEL_SELECTOR='app.contentgrid.com/service-type=api'
# Dots escaped for jsonpath
ANNOTATION_PATH='api\.sp\.captain\.contentgrid\.com/db-access-credentials-id'

if [[ "${DRY_RUN}" != "0" ]]; then
    echo "Dry run, no databases will be reindexed. Set DRY_RUN=0 to apply." >&2
fi
command -v psql >/dev/null || { echo "psql is required" >&2; exit 1; }

# Tables and materialized views with indexes in non-system schemas, as quoted identifier, with whether the current user
# may reindex them: owner (or member of the owning role), or MAINTAIN privilege on PostgreSQL 17+.
# Partitioned tables are left out, their partitions are listed as tables themselves. TOAST indexes are rebuilt with
# their table.
LIST_TABLES_SQL="SELECT format('%I.%I', n.nspname, c.relname),
       CASE WHEN current_setting('server_version_num')::int >= 170000
            THEN has_table_privilege(c.oid, 'MAINTAIN')
            ELSE pg_has_role(c.relowner, 'USAGE') END,
       pg_get_userbyid(c.relowner)
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'm')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
  AND n.nspname NOT LIKE 'pg\_toast%'
  AND n.nspname NOT LIKE 'pg\_temp\_%'
  AND EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid = c.oid)
ORDER BY 1;"

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

    # jdbc:postgresql://host[:port]/database[?params]
    if [[ ! "${url}" =~ ^jdbc:postgresql://([^/:?]+)(:([0-9]+))?/([^?]+) ]]; then
        echo "  can not parse spring.datasource.url '${url}'" >&2
        return 1
    fi
    local host="${PGHOST:-${BASH_REMATCH[1]}}"
    local port="${PGPORT:-${BASH_REMATCH[3]:-5432}}"
    local database="${BASH_REMATCH[4]}"

    echo "  ${host}:${port}/${database} as ${username}"

    # Password via environment so it doesn't show up in the process list; no timeout, reindexing can take a while
    run_psql() {
        PGPASSWORD="${password}" PGOPTIONS="-c statement_timeout=0" \
            psql --no-psqlrc --quiet -v ON_ERROR_STOP=1 -h "${host}" -p "${port}" -U "${username}" -d "${database}" "$@"
    }

    local tables
    tables="$(run_psql --no-align --tuples-only --field-separator=$'\t' <<<"${LIST_TABLES_SQL}")" || return 1

    local table can_reindex owner reindexed=0 failed=0
    while IFS=$'\t' read -r table can_reindex owner; do
        [[ -z "${table}" ]] && continue
        if [[ "${can_reindex}" != "t" ]]; then
            echo "    ${table}: FAILED, ${username} may not reindex it (owned by ${owner})" >&2
            failed=$((failed + 1))
            continue
        fi

        local sql="REINDEX TABLE ${table};"
        if [[ "${DRY_RUN}" != "0" ]]; then
            echo "    ${sql}"
            reindexed=$((reindexed + 1))
            continue
        fi

        local start=${SECONDS}
        if run_psql <<<"${sql}"; then
            echo "    ${table}: done in $((SECONDS - start))s"
            reindexed=$((reindexed + 1))
        else
            echo "    ${table}: FAILED" >&2
            failed=$((failed + 1))
        fi
    done <<<"${tables}"

    if (( reindexed == 0 && failed == 0 )); then
        echo "  no tables with indexes found"
        return 0
    fi
    if [[ "${DRY_RUN}" != "0" ]]; then
        echo "  ${reindexed} table(s) would be reindexed, ${failed} can not be reindexed"
    else
        echo "  ${reindexed} table(s) reindexed, ${failed} failed"
    fi
    (( failed == 0 ))
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
