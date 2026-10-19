#!/usr/bin/env python3
"""Upgrades a Scaleway Managed Database (rdb) instance to a newer major engine version.

Uses the major upgrade workflow of the Scaleway API: a new instance running the new engine version is created from the
source instance, optionally taking over the endpoints of the source instance. The source instance is left in place.

The new instance is created without high availability; when the source instance is an HA cluster, HA is enabled on the
new instance once it is ready.

The script only returns once the upgrade is complete: the new instance (and the source instance) are ready, and HA is
enabled when needed.

All Scaleway calls go through the scw CLI, so it uses the configured scw profile (see `scw init`; select another one with
SCW_PROFILE or the --profile option of this script).

Examples:
  upgrade-db.py                                      select an instance and options interactively
  upgrade-db.py my-db --switch-endpoints             upgrade my-db to the latest version and move its endpoints
  upgrade-db.py 11111111-... --version 16 --no-switch-endpoints --yes
"""
import argparse
import json
import re
import subprocess
import sys
import time

# Instance statuses: ready is the only stable state, these are failures; all others are transient
FAILED_STATUSES = {"error", "locked", "disk_full"}


class ScwError(Exception):
    def __init__(self, message, response=None):
        super().__init__(message)
        # Parsed JSON error response of scw, if any
        self.response = response

    def transient_state(self):
        """The current state when the API refused the call because the resource is in a transient state"""
        error = (self.response or {}).get("error") if isinstance(self.response, dict) else None
        return error.get("current_state") if isinstance(error, dict) else None


class Scw:
    def __init__(self, profile=None, dry_run=False):
        self.global_args = ["--profile", profile] if profile else []
        self.dry_run = dry_run

    def run(self, *args, mutating=False):
        cmd = ["scw", *self.global_args, *args, "-o", "json"]
        if mutating and self.dry_run:
            print(f"  [dry run] {' '.join(cmd)}")
            return None
        result = subprocess.run(cmd, capture_output=True, text=True)
        if result.returncode != 0:
            output = result.stderr.strip() or result.stdout.strip()
            try:
                # The JSON error may be preceded by other output
                response = json.JSONDecoder().raw_decode(output, output.index("{"))[0]
            except ValueError:
                response = None
            raise ScwError(f"{' '.join(cmd)} failed:\n{output}", response)
        return json.loads(result.stdout) if result.stdout.strip() else None

    def list_instances(self, region):
        return self.run("rdb", "instance", "list", f"region={region}") or []

    def project_names(self):
        """Project ID -> name; empty when the projects can not be listed (e.g. missing permissions)"""
        try:
            return {p["id"]: p["name"] for p in self.run("account", "project", "list") or []}
        except ScwError as e:
            print(f"Could not list projects, showing project IDs instead: {e}", file=sys.stderr)
            return {}

    def get_instance(self, instance_id, region):
        return self.run("rdb", "instance", "get", instance_id, f"region={region}")

    def upgrade(self, instance_id, region, *params):
        return self.run("rdb", "instance", "upgrade", instance_id, f"region={region}", *params, mutating=True)


def version_key(version):
    """Sort key for an upgradable version, numerically on (minor) version: 16.4 > 16.10 is wrong, (16, 10) > (16, 4)"""
    text = version.get("minor_version") or version.get("version") or version.get("name") or ""
    return tuple(int(n) for n in re.findall(r"\d+", text))


def describe_version(version):
    return version.get("name") or f"version {version.get('minor_version') or version.get('version')}"


def is_ha(instance):
    return bool(instance.get("is_ha_cluster"))


def endpoints(instance):
    return ", ".join(f"{e.get('ip') or e.get('hostname')}:{e.get('port')}" for e in instance.get("endpoints") or []) or "-"


def prompt(question):
    try:
        return input(question).strip()
    except EOFError:
        return ""


def confirm(question, default=False):
    answer = prompt(f"{question} [{'Y/n' if default else 'y/N'}] ").lower()
    return default if not answer else answer in ("y", "yes")


def describe_project(instance, projects):
    project_id = instance.get("project_id")
    return f"{projects[project_id]} ({project_id})" if project_id in projects else project_id or "-"


def select_instance(instances, selector, projects):
    if selector:
        matches = [i for i in instances if selector in (i["id"], i["name"])]
        if not matches:
            sys.exit(f"No database instance with ID or name '{selector}' found")
        if len(matches) > 1:
            sys.exit(f"Multiple database instances named '{selector}', select one by ID:\n"
                     + "\n".join(f"  {i['id']} ({i['region']}, project {describe_project(i, projects)})"
                                   for i in matches))
        return matches[0]

    instances = [i for i in instances if i.get("upgradable_version")]
    if not instances:
        sys.exit("No database instances with upgrades available found")
    if not sys.stdin.isatty():
        sys.exit("No instance given and not running interactively")

    instances = sorted(instances, key=lambda i: (i.get("project_id", ""), i.get("region", ""), i["name"]))
    print("Database instances with upgrades available:")
    rows = [(f"{n})", i["name"], i["engine"], "HA" if is_ha(i) else "", i["status"], i["id"],
             projects.get(i.get("project_id")) or i.get("project_id") or "-")
            for n, i in enumerate(instances, 1)]
    widths = [max(len(row[c]) for row in rows) for c in range(len(rows[0]))]
    for row in rows:
        # Number right-aligned, other columns left-aligned
        cells = [row[0].rjust(widths[0])] + [cell.ljust(width) for cell, width in zip(row[1:], widths[1:])]
        print(f"  {'  '.join(cells).rstrip()}")
    while True:
        answer = prompt("Select an instance (number, name or ID): ")
        if not answer:
            sys.exit("No instance selected")
        if answer.isdigit() and 1 <= int(answer) <= len(instances):
            return instances[int(answer) - 1]
        matches = [i for i in instances if answer in (i["id"], i["name"])]
        if len(matches) == 1:
            return matches[0]
        print("  Invalid selection")


def select_version(instance, requested):
    upgradable = sorted(instance.get("upgradable_version") or [], key=version_key, reverse=True)
    if not upgradable:
        sys.exit(f"{instance['name']} ({instance['engine']}) has no versions to upgrade to")
    if not requested:
        return upgradable[0]
    for version in upgradable:
        if requested in (version.get("id"), version.get("name"), version.get("version"), version.get("minor_version")):
            return version
    sys.exit(f"Version '{requested}' is not available for {instance['name']}, available versions:\n"
             + "\n".join(f"  {describe_version(v)} (version {v.get('version')}, minor version {v.get('minor_version')}, "
                         f"ID {v.get('id')})" for v in upgradable))


def wait_ready(scw, instance_id, region, interval, timeout, description, condition=None):
    """Polls until the instance is ready (and condition holds), returns the instance"""
    start = time.monotonic()
    last = None
    while True:
        try:
            instance = scw.get_instance(instance_id, region)
            status = instance["status"]
        except ScwError as e:
            # Getting an instance fails while it is e.g. provisioning, the error tells the state
            status = e.transient_state()
            if not status:
                raise
        elapsed = int(time.monotonic() - start)
        if status != last:
            print(f"  [{elapsed // 60:02d}:{elapsed % 60:02d}] {description}: {status}")
            last = status
        if status in FAILED_STATUSES:
            raise ScwError(f"{description} ({instance_id}) is in status {status}")
        if status == "ready" and (condition is None or condition(instance)):
            return instance
        if timeout and elapsed > timeout:
            raise ScwError(f"Timed out after {elapsed}s waiting for {description} ({instance_id}), status {status}")
        time.sleep(interval)


def find_new_instance(scw, source, existing_ids, interval, timeout):
    """Fallback when the upgrade response does not identify the new instance: wait for it to appear in the list"""
    start = time.monotonic()
    while True:
        new = [i for i in scw.list_instances(source["region"])
               if i["id"] not in existing_ids and i.get("project_id") == source.get("project_id")]
        if len(new) == 1:
            return new[0]
        if len(new) > 1:
            raise ScwError("Multiple new instances appeared, can not determine the upgraded instance:\n"
                           + "\n".join(f"  {i['name']} ({i['id']})" for i in new))
        if timeout and time.monotonic() - start > timeout:
            raise ScwError("Timed out waiting for the upgraded instance to appear")
        time.sleep(interval)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("instance", nargs="?", help="ID or name of the instance to upgrade (default: select interactively)")
    parser.add_argument("--region", default="all", help="region to look for the instance in (default: all)")
    parser.add_argument("--version", help="version to upgrade to: major version (e.g. 16), minor version, name "
                                          "(e.g. PostgreSQL-16) or upgradable version ID (default: most recent)")
    parser.add_argument("--switch-endpoints", action=argparse.BooleanOptionalAction,
                        help="move the endpoints of the source instance to the upgraded instance (default: ask)")
    parser.add_argument("--yes", "-y", action="store_true", help="do not ask for confirmation")
    parser.add_argument("--dry-run", action="store_true", help="only print what would be done")
    parser.add_argument("--profile", help="scw config profile to use")
    parser.add_argument("--poll-interval", type=int, default=15, help="seconds between status checks (default: 15)")
    parser.add_argument("--timeout", type=int, default=0,
                        help="maximum seconds to wait for each step, 0 for no limit (default: no limit)")
    args = parser.parse_args()

    scw = Scw(profile=args.profile, dry_run=args.dry_run)

    instances = scw.list_instances(args.region)
    projects = scw.project_names()
    source = select_instance(instances, args.instance, projects)
    region = source["region"]
    # The list may be stale or incomplete, get the current state
    source = scw.get_instance(source["id"], region)
    version = select_version(source, args.version)

    if source["status"] != "ready":
        sys.exit(f"{source['name']} is in status {source['status']}, it must be ready to upgrade")

    switch_endpoints = args.switch_endpoints
    if switch_endpoints is None:
        if args.yes or not sys.stdin.isatty():
            sys.exit("Specify --switch-endpoints or --no-switch-endpoints")
        switch_endpoints = confirm(f"Move the endpoints of {source['name']} ({endpoints(source)}) to the upgraded instance?")

    print()
    print(f"Instance:          {source['name']} ({source['id']}, {region})")
    print(f"Project:           {describe_project(source, projects)}")
    print(f"Upgrade:           {source['engine']} -> {describe_version(version)} "
          f"(minor version {version.get('minor_version')})")
    print(f"Node type:         {source.get('node_type')}{', HA (will be enabled on the new instance)' if is_ha(source) else ''}")
    print(f"Endpoints:         {endpoints(source)}{' (will be moved to the new instance)' if switch_endpoints else ''}")
    print()
    if args.dry_run:
        print("Dry run, nothing will be changed")
    elif not args.yes and not confirm("Start the upgrade?"):
        sys.exit("Aborted")

    existing_ids = {i["id"] for i in scw.list_instances(region)}
    print(f"Starting upgrade of {source['name']}")
    response = scw.upgrade(source["id"], region,
                           f"major-upgrade-workflow.upgradable-version-id={version['id']}",
                           f"major-upgrade-workflow.with-endpoints={str(switch_endpoints).lower()}")
    if args.dry_run:
        if is_ha(source):
            scw.upgrade("<new instance ID>", region, "enable-ha=true")
        return

    # Only monitor a target explicitly identified by the upgrade response.
    if response and response.get("id") and response["id"] != source["id"]:
        new_id = response["id"]
    else:
        raise ScwError(
            f"The upgrade response did not identify the target for {source['id']}. "
            "The upgrade may still be running; inspect it in Scaleway before retrying. "
            "No further readiness or HA checks will be performed."
        )
    print(f"Upgraded instance: {new_id}")

    target = wait_ready(scw, new_id, region, args.poll_interval, args.timeout, "upgraded instance")
    print(f"Upgraded instance {target['name']} ({target['engine']}) is ready")

    if is_ha(source) and not is_ha(target):
        print(f"Enabling HA on {target['name']}")
        scw.upgrade(new_id, region, "enable-ha=true")
        # The status can still be ready right after the request, so also wait for the HA flag
        target = wait_ready(scw, new_id, region, args.poll_interval, args.timeout, "enabling HA", condition=is_ha)
        print(f"HA enabled on {target['name']}")

    # Moving the endpoints changes the source instance too; make sure it has settled
    wait_ready(scw, source["id"], region, args.poll_interval, args.timeout, "source instance")

    print()
    print(f"Upgrade complete: {target['name']} ({target['id']}), {target['engine']}"
          f"{', HA' if is_ha(target) else ''}, endpoints {endpoints(target)}")
    print(f"The source instance {source['name']} ({source['id']}) still exists, delete it once it is no longer needed")


if __name__ == "__main__":
    try:
        main()
    except ScwError as e:
        sys.exit(str(e))
    except KeyboardInterrupt:
        sys.exit("Interrupted; an upgrade that was already started continues in the background")
