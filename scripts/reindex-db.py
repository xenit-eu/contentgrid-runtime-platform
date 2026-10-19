#!/usr/bin/env python3
"""Rebuilds all indexes in one database.

Runs REINDEX TABLE on every table with indexes, as the given user. REINDEX DATABASE/SCHEMA would require owning the
database/schema, which an app user doesn't (public is owned by the database owner); REINDEX TABLE only requires
owning the table, or MAINTAIN privilege on PostgreSQL 17+. System schemas are skipped; tables the user may not
reindex are reported as errors.

The reindex is not concurrent: writes to each table (and reads using its indexes) are blocked while its indexes are
rebuilt, so run this in a maintenance window.

Runs in dry-run mode by default; use --apply to actually reindex. A dry run still logs in to list the tables.

The password is taken from PGPASSWORD, or asked for.
"""
from appdb import add_apply_argument, argument_parser, read_password, reindex_database, run


def main():
    parser = argument_parser(__doc__)
    parser.add_argument("connection_string",
                        help="postgresql://host[:port]/database[?params] or 'host=... port=... dbname=...'")
    parser.add_argument("username", help="user to log in as")
    add_apply_argument(parser, "reindex")
    args = parser.parse_args()

    password = read_password(args.username)
    return 0 if reindex_database(args.connection_string, args.username, password, not args.apply) else 1


if __name__ == "__main__":
    run(main)
