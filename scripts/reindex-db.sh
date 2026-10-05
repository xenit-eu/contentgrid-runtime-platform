#!/usr/bin/env bash
# Rebuilds all indexes in one database.
#
# Runs REINDEX TABLE on every table with indexes, as the given user. REINDEX DATABASE/SCHEMA would require owning the
# database/schema, which an app user doesn't (public is owned by the database owner); REINDEX TABLE only requires
# owning the table, or MAINTAIN privilege on PostgreSQL 17+. System schemas are skipped; tables the user may not
# reindex are reported as errors.
#
# The reindex is not concurrent: writes to each table (and reads using its indexes) are blocked while its indexes are
# rebuilt, so run this in a maintenance window.
#
# Runs in dry-run mode by default; set DRY_RUN=0 to actually reindex. A dry run still logs in to list the tables.
#
# Usage: [DRY_RUN=0] reindex-db.sh <connection string> <username> [<password>]
#
#   connection string   postgresql://host[:port]/database[?params] or 'host=... port=... dbname=...'
#   password            defaults to PGPASSWORD; prefer that, arguments show up in the process list
#
# Environment:
#   PGPASSWORD          password, when not given as argument
#   DRY_RUN=0           actually reindex (default: 1, only print what would be done)
set -euo pipefail

if (( $# < 2 || $# > 3 )); then
    sed -n '2,/^set /{/^set /d; s/^# \{0,1\}//; p}' "$0" >&2
    exit 2
fi

CONNECTION_STRING="$1"
USERNAME="$2"
PASSWORD="${3:-${PGPASSWORD:-}}"
DRY_RUN="${DRY_RUN:-1}"

[[ -n "${PASSWORD}" ]] || { echo "password must be given as argument or in PGPASSWORD" >&2; exit 2; }
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

# Password via environment so it doesn't show up in the process list; no timeout, reindexing can take a while
run_psql() {
    PGPASSWORD="${PASSWORD}" PGOPTIONS="-c statement_timeout=0" \
        psql --no-psqlrc --quiet -v ON_ERROR_STOP=1 -U "${USERNAME}" -d "${CONNECTION_STRING}" "$@"
}

# Print where we're connected without echoing the connection string, which may contain a password
target="$(run_psql <<<'\echo :HOST:PORT/:DBNAME as :USER')"
if [[ "${DRY_RUN}" != "0" ]]; then
    echo "${target} (dry run, nothing will be reindexed; set DRY_RUN=0 to apply)"
else
    echo "${target}"
fi

tables="$(run_psql --no-align --tuples-only --field-separator=$'\t' <<<"${LIST_TABLES_SQL}")"

reindexed=0
failed=0
while IFS=$'\t' read -r table can_reindex owner; do
    [[ -z "${table}" ]] && continue
    if [[ "${can_reindex}" != "t" ]]; then
        echo "  ${table}: FAILED, ${USERNAME} may not reindex it (owned by ${owner})" >&2
        failed=$((failed + 1))
        continue
    fi

    sql="REINDEX TABLE ${table};"
    if [[ "${DRY_RUN}" != "0" ]]; then
        echo "  ${sql}"
        reindexed=$((reindexed + 1))
        continue
    fi

    start=${SECONDS}
    if run_psql <<<"${sql}"; then
        echo "  ${table}: done in $((SECONDS - start))s"
        reindexed=$((reindexed + 1))
    else
        echo "  ${table}: FAILED" >&2
        failed=$((failed + 1))
    fi
done <<<"${tables}"

if (( reindexed == 0 && failed == 0 )); then
    echo "no tables with indexes found"
elif [[ "${DRY_RUN}" != "0" ]]; then
    echo "${reindexed} table(s) would be reindexed, ${failed} can not be reindexed"
else
    echo "${reindexed} table(s) reindexed, ${failed} failed"
fi
(( failed == 0 ))
