#!/usr/bin/env python3
"""Points every app database secret to a different database server.

Iterates over all secrets (in all namespaces, or the one given with --namespace) that have
  label      app.contentgrid.com/service-type=api
  annotation api.sp.captain.contentgrid.com/db-access-credentials-id (any value)
and replaces host and port in spring.datasource.url, keeping the database name and parameters.

Runs in dry-run mode by default; use --apply to actually update the secrets.
"""
import argparse
import base64
import json
import re
import sys

from appdb import (Failed, add_apply_argument, add_kubectl_arguments, app_secrets, argument_parser, for_each_secret,
                   kubectl, kubectl_args, run)

HOST_PORT_REGEX = re.compile(r"^[^/:?]+(:[0-9]+)?$")
# jdbc:postgresql://host[:port]/database[?params]
URL_REGEX = re.compile(r"^(jdbc:postgresql://)([^/:?]+(?::[0-9]+)?)(/.*)$", re.DOTALL)
URL_KEY = "spring.datasource.url"


def host_port(value):
    if not HOST_PORT_REGEX.match(value):
        raise argparse.ArgumentTypeError(f"'{value}' is not host[:port]")
    return value


def with_port(host_port):
    """Adds the default port, so host and host:5432 compare equal"""
    return host_port if ":" in host_port else f"{host_port}:5432"


def main():
    parser = argument_parser(__doc__)
    parser.add_argument("--to", required=True, type=host_port, metavar="HOST[:PORT]",
                        help="host[:port] to put in spring.datasource.url")
    parser.add_argument("--from", dest="from_", type=host_port, metavar="HOST[:PORT]",
                        help="only update secrets currently pointing to this host[:port] (port defaults to 5432)")
    add_apply_argument(parser, "update the secrets")
    add_kubectl_arguments(parser)
    args = parser.parse_args()

    dry_run = not args.apply
    if dry_run:
        print("Dry run, no secrets will be changed. Use --apply to apply.", file=sys.stderr)

    def process(secret):
        url = secret.get(URL_KEY)
        match = URL_REGEX.match(url)
        if not match:
            raise Failed(f"can not parse {URL_KEY} '{url}'")
        prefix, current_host_port, rest = match.groups()

        if args.from_ and with_port(current_host_port) != with_port(args.from_):
            print(f"  skipped, points to {current_host_port}")
            return

        new_url = f"{prefix}{args.to}{rest}"
        if new_url == url:
            print("  already up to date")
            return

        print(f"  {url} -> {new_url}")
        if dry_run:
            return

        patch = {"data": {URL_KEY: base64.b64encode(new_url.encode()).decode()}}
        kubectl(kubectl_args(args), "patch", "secret", "-n", secret.namespace, secret.name, "--type", "merge",
                "-p", json.dumps(patch))

    return for_each_secret(app_secrets(kubectl_args(args), args.namespace), process)


if __name__ == "__main__":
    run(main)
