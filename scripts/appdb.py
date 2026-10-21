"""Shared code for the app database scripts in this directory: finding app database secrets with kubectl, running SQL
with psql and reindexing a database."""
import argparse
import base64
import csv
import getpass
import json
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field

LABEL_SELECTOR = "app.contentgrid.com/service-type=api"
CREDENTIALS_ID_ANNOTATION = "api.sp.captain.contentgrid.com/db-access-credentials-id"

# jdbc:postgresql://host[:port]/database[?params]
JDBC_URL_REGEX = re.compile(r"^jdbc:postgresql://(?P<host>[^/:?]+)(?::(?P<port>[0-9]+))?/(?P<database>[^?]+)")


class Failed(Exception):
    """Processing failed; when processing multiple items the message is reported and the next item is processed"""


def run(main):
    """Runs the main function of a script, exiting with its return value"""
    # Keep stdout and stderr in order when output is redirected
    sys.stdout.reconfigure(line_buffering=True)
    try:
        sys.exit(main())
    except Failed as e:
        sys.exit(str(e))
    except KeyboardInterrupt:
        # psql handles ^C itself (cancelling the running query); Python gets it as well, so this stops the whole script
        print("Interrupted", file=sys.stderr)
        sys.exit(130)


def argument_parser(doc):
    """Argument parser with the docstring of the script as description"""
    return argparse.ArgumentParser(description=doc, formatter_class=argparse.RawDescriptionHelpFormatter)


def add_apply_argument(parser, action):
    parser.add_argument("--apply", action="store_true",
                        help=f"actually {action} (default: dry run, only print what would be done)")


def add_kubectl_arguments(parser):
    parser.add_argument("--context", help="kubectl context to use (default: the current context)")
    parser.add_argument("--namespace", "-n", help="only process secrets in this namespace (default: all namespaces)")


def kubectl_args(args):
    return ["--context", args.context] if args.context else []


def read_password(user):
    """Password from PGPASSWORD, or asked for. Passwords are never taken as argument, those show up in the process list"""
    password = os.environ.get("PGPASSWORD")
    if password:
        return password
    if not sys.stdin.isatty():
        raise Failed(f"set PGPASSWORD to the password of {user}, or run interactively to be asked for it")
    try:
        password = getpass.getpass(f"Password for {user}: ")
    except EOFError:
        password = None
    if not password:
        raise Failed("no password given")
    return password


def require(command):
    if shutil.which(command) is None:
        raise Failed(f"{command} is required")


def indent(text, prefix="  "):
    return "\n".join(prefix + line for line in text.splitlines())


def kubectl(kubectl_args, *args):
    result = subprocess.run(["kubectl", *kubectl_args, *args], capture_output=True, text=True, stdin=subprocess.DEVNULL)
    if result.returncode != 0:
        raise Failed(f"kubectl {' '.join(args)} failed:\n{indent(result.stderr.strip())}")
    return result.stdout


@dataclass
class AppSecret:
    namespace: str
    name: str
    credentials_id: str
    data: dict = field(repr=False)

    def __str__(self):
        return f"{self.namespace}/{self.name}"

    def get(self, key):
        """Decoded value of a key, empty when missing"""
        try:
            return base64.b64decode(self.data.get(key, "")).decode()
        except ValueError as e:
            raise Failed(f"can not decode {key}: {e}")


def app_secrets(kubectl_args, namespace=None):
    """All secrets (in the namespace, or all namespaces) that have label LABEL_SELECTOR and annotation
    CREDENTIALS_ID_ANNOTATION"""
    namespace_args = ["--namespace", namespace] if namespace else ["--all-namespaces"]
    # Label filtering happens server-side; annotations can't be selected on, so filter here
    secrets = json.loads(kubectl(kubectl_args, "get", "secrets", *namespace_args, "-l", LABEL_SELECTOR, "-o", "json"))
    return [AppSecret(s["metadata"]["namespace"], s["metadata"]["name"],
                      s["metadata"]["annotations"][CREDENTIALS_ID_ANNOTATION], s.get("data") or {})
            for s in secrets["items"]
            if CREDENTIALS_ID_ANNOTATION in (s["metadata"].get("annotations") or {})]


def for_each_secret(secrets, process):
    """Calls process for each secret, reporting failures; returns the exit code"""
    failed = []
    for secret in secrets:
        print(secret)
        try:
            process(secret)
        except Failed as e:
            if str(e):
                print(indent(str(e)), file=sys.stderr)
            print("  FAILED", file=sys.stderr)
            failed.append(secret)

    print(f"Processed {len(secrets)} secret(s), {len(failed)} failed")
    for secret in failed:
        print(f"  {secret}", file=sys.stderr)
    return 1 if failed else 0


def datasource_credentials(secret):
    username = secret.get("spring.datasource.username")
    password = secret.get("spring.datasource.password")
    if not username or not password:
        raise Failed("missing spring.datasource.username or spring.datasource.password")
    return username, password


def parse_jdbc_url(url):
    """Host, port (None when not given) and database from a jdbc:postgresql URL"""
    match = JDBC_URL_REGEX.match(url)
    if not match:
        raise Failed(f"can not parse spring.datasource.url '{url}'")
    return match["host"], match["port"], match["database"]


def quote_ident(name):
    return '"' + name.replace('"', '""') + '"'


def quote_literal(value):
    # Assumes standard_conforming_strings (the default), so backslashes need no escaping
    return "'" + value.replace("'", "''") + "'"


def psql(connection_args, password, sql, *psql_args, options=None):
    """Runs SQL with psql, returns its output.

    SQL goes over stdin and the password via environment, so neither shows up in the process list.
    """
    env = dict(os.environ, PGPASSWORD=password)
    if options:
        env["PGOPTIONS"] = options
    result = subprocess.run(["psql", "--no-psqlrc", "--quiet", "-v", "ON_ERROR_STOP=1", *connection_args, *psql_args],
                            input=sql, env=env, capture_output=True, text=True)
    if result.returncode != 0:
        raise Failed(result.stderr.strip() or f"psql exited with status {result.returncode}")
    return result.stdout


# Tables and materialized views with indexes in non-system schemas, as quoted identifier, with whether the current user
# may reindex them: owner (or member of the owning role), or MAINTAIN privilege on PostgreSQL 17+.
# Partitioned tables are left out, their partitions are listed as tables themselves. TOAST indexes are rebuilt with
# their table.
LIST_TABLES_SQL = r"""SELECT format('%I.%I', n.nspname, c.relname),
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
ORDER BY 1;"""


def reindex_database(connection_string, username, password, dry_run, prefix=""):
    """Runs REINDEX TABLE on every table with indexes in one database; returns whether all tables were reindexed.

    See reindex-db.py for details. Output lines are prefixed with prefix.
    """
    require("psql")

    # Nothing is sent while a REINDEX runs, so without keepalives an idle timeout in between (load balancer, NAT) can
    # silently drop the connection, leaving psql waiting forever. Keepalives from the client (libpq connection
    # parameters) keep the connection busy and make psql fail if it is dropped anyway; keepalives from the server
    # (tcp_keepalives_* settings) make the server abort the REINDEX if the client is gone.
    keepalives = {"keepalives": "1", "keepalives_idle": "30", "keepalives_interval": "10", "keepalives_count": "3"}
    if "://" in connection_string:
        separator = "&" if "?" in connection_string else "?"
        connection = connection_string + separator + "&".join(f"{k}={v}" for k, v in keepalives.items())
    else:
        connection = connection_string + "".join(f" {k}={v}" for k, v in keepalives.items())

    def run_psql(sql, *args):
        # No timeout, reindexing can take a while
        return psql(["-U", username, "-d", connection], password, sql, *args,
                    options="-c statement_timeout=0 -c tcp_keepalives_idle=30 -c tcp_keepalives_interval=10"
                            " -c tcp_keepalives_count=3")

    def error(message):
        print(indent(message, prefix), file=sys.stderr)

    # Print where we're connected without echoing the connection string, which may contain a password
    target = run_psql("SELECT :'HOST' || ':' || :'PORT' || '/' || :'DBNAME' || ' as ' || :'USER';",
                      "--no-align", "--tuples-only").strip()
    if dry_run:
        print(f"{prefix}{target} (dry run, nothing will be reindexed; use --apply to apply)")
    else:
        print(f"{prefix}{target}")

    # CSV, so table names are properly quoted whatever they contain
    tables = list(csv.reader(run_psql(LIST_TABLES_SQL, "--csv", "--tuples-only").splitlines()))

    start = time.monotonic()
    reindexed = 0
    failed = 0
    for table, can_reindex, owner in tables:
        if can_reindex != "t":
            error(f"  {table}: FAILED, {username} may not reindex it (owned by {owner})")
            failed += 1
            continue

        sql = f"REINDEX TABLE {table};"
        if dry_run:
            print(f"{prefix}  {sql}")
            reindexed += 1
            continue

        table_start = time.monotonic()
        try:
            run_psql(sql)
        except Failed as e:
            error(indent(str(e), "    "))
            error(f"  {table}: FAILED")
            failed += 1
            continue
        print(f"{prefix}  {table}: done in {time.monotonic() - table_start:.0f}s")
        reindexed += 1

    if reindexed == 0 and failed == 0:
        print(f"{prefix}no tables with indexes found")
    elif dry_run:
        print(f"{prefix}{reindexed} table(s) would be reindexed, {failed} can not be reindexed")
    else:
        print(f"{prefix}{reindexed} table(s) reindexed, {failed} failed, in {time.monotonic() - start:.0f}s")
    return failed == 0
