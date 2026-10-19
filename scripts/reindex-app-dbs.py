#!/usr/bin/env python3
"""Rebuilds all indexes in every app database.

Iterates over all secrets (in all namespaces, or the one given with --namespace) that have
  label      app.contentgrid.com/service-type=api
  annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
reads spring.datasource.{url,username,password} from them and reindexes the database from the url, running
REINDEX TABLE on every table with indexes; see reindex-db.py for details.

The reindex is not concurrent: writes to each table (and reads using its indexes) are blocked while its indexes are
rebuilt, so run this in a maintenance window.

Runs in dry-run mode by default; use --apply to actually reindex. A dry run still logs in to list the tables.

By default it logs in with the credentials from the secret. With --user it logs in as that user instead (tables this
user may not reindex are reported as errors); its password is taken from PGPASSWORD, or asked for.
"""
import sys

from appdb import (Failed, add_apply_argument, add_kubectl_arguments, app_secrets, argument_parser,
                   datasource_credentials, for_each_secret, kubectl_args, read_password, reindex_database, run)


def main():
    parser = argument_parser(__doc__)
    parser.add_argument("--user", "-U", help="log in as this user instead of the one from the secret")
    add_apply_argument(parser, "reindex")
    add_kubectl_arguments(parser)
    args = parser.parse_args()

    login_password = read_password(args.user) if args.user else None
    dry_run = not args.apply
    if dry_run:
        print("Dry run, no databases will be reindexed. Use --apply to apply.", file=sys.stderr)

    def process(secret):
        url = secret.get("spring.datasource.url")
        username, password = datasource_credentials(secret)

        # jdbc:postgresql://host[:port]/database[?params] is a valid psql connection string without the jdbc: prefix;
        # the params are JDBC driver options that psql may not understand, so leave them out
        if not url.startswith("jdbc:postgresql://"):
            raise Failed(f"can not parse spring.datasource.url '{url}'")
        connection_string = url.removeprefix("jdbc:").split("?", 1)[0]

        if not reindex_database(connection_string, args.user or username, login_password or password, dry_run,
                                prefix="  "):
            raise Failed()

    return for_each_secret(app_secrets(kubectl_args(args), args.namespace), process)


if __name__ == "__main__":
    run(main)
