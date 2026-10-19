#!/usr/bin/env python3
"""Resets the database password of every app database user to the password stored in its secret, stored as a
SCRAM-SHA-256 hash. Use this to rehash passwords that are still stored with an older algorithm (md5).

Iterates over all secrets (in all namespaces, or the one given with --namespace) that have
  label      app.contentgrid.com/service-type=api
  annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
reads spring.datasource.{url,username,password} from them, connects to the database server from the url
and runs ALTER ROLE <username> PASSWORD <password> with password_encryption set to scram-sha-256.

By default it logs in as the app user itself, with the password from the secret (which must therefore already be
correct; any user may change its own password). Use --user to log in as an admin instead, e.g. when the password in
the database differs from the one in the secret; its password is taken from PGPASSWORD, or asked for.

Runs in dry-run mode by default; use --apply to actually change passwords.

libpq environment variables other than host, port, database and user still apply, e.g. PGSSLMODE.
"""
import sys

from appdb import (add_apply_argument, add_kubectl_arguments, app_secrets, argument_parser, datasource_credentials,
                   for_each_secret, kubectl_args, parse_jdbc_url, psql, quote_ident, quote_literal, read_password,
                   require, run)


def main():
    parser = argument_parser(__doc__)
    parser.add_argument("--user", "-U", help="admin user to log in as (default: the app user itself)")
    parser.add_argument("--host", "-H", help="database server host (default: host from spring.datasource.url)")
    parser.add_argument("--port", "-p", help="database server port (default: port from spring.datasource.url)")
    parser.add_argument("--database", "-d", help="database to connect to (default: database from spring.datasource.url)")
    add_apply_argument(parser, "change the passwords")
    add_kubectl_arguments(parser)
    args = parser.parse_args()

    admin_password = read_password(args.user) if args.user else None
    dry_run = not args.apply
    if dry_run:
        print("Dry run, no passwords will be changed. Use --apply to apply.", file=sys.stderr)
    else:
        require("psql")

    def process(secret):
        username, password = datasource_credentials(secret)
        url_host, url_port, url_database = parse_jdbc_url(secret.get("spring.datasource.url"))
        host = args.host or url_host
        port = args.port or url_port or "5432"
        database = args.database or url_database

        # Log in as admin if given, otherwise as the user itself
        login_user = args.user or username
        login_password = admin_password or password

        print(f"  ALTER ROLE {quote_ident(username)} on {host}:{port}/{database} as {login_user} "
              f"(credentials id {secret.credentials_id})")
        if dry_run:
            return

        psql(["-h", host, "-p", port, "-U", login_user, "-d", database], login_password,
             "SET password_encryption = 'scram-sha-256';\n"
             f"ALTER ROLE {quote_ident(username)} WITH PASSWORD {quote_literal(password)};\n")

    return for_each_secret(app_secrets(kubectl_args(args), args.namespace), process)


if __name__ == "__main__":
    run(main)
