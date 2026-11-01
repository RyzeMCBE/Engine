#!/usr/bin/env python3
"""Audit direct non-test dependencies of release POMs against Maven Central.

Pending first-party coordinates are explicitly allowlisted until the owner publishes
them. This is a direct-dependency gate, not proof of complete recursive closure.
"""
import argparse
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

CENTRAL = "https://repo.maven.apache.org/maven2"

def child(element, name):
    return element.find("{*}" + name)

def text(element, name):
    node = child(element, name)
    return (node.text or "").strip() if node is not None else ""

def deps(root, section="./{*}dependencies/{*}dependency"):
    return root.findall(section)

def coordinate(group, artifact, version):
    return f"{group}:{artifact}:{version}"

def xml_for(group, artifact, version):
    rel = f"{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"
    url = f"{CENTRAL}/{rel}"
    request = urllib.request.Request(url, headers={"User-Agent": "RyzeMCBE-Central-POM-Audit/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=18) as response:
            return ET.fromstring(response.read()), url
    except (urllib.error.URLError, TimeoutError, ET.ParseError) as error:
        raise RuntimeError(f"Cannot retrieve {url}: {error}") from error

def interpolate(value, properties):
    for _ in range(8):
        previous = value
        for name, replacement in properties.items():
            value = value.replace("${" + name + "}", replacement)
        if value == previous:
            break
    return value

def bom_versions(root):
    mapping = {}
    props = {"project.version": text(root, "version"), "pom.version": text(root, "version")}
    section = child(root, "properties")
    if section is not None:
        for item in section:
            props[item.tag.split("}")[-1]] = item.text or ""
    for dep in deps(root, "./{*}dependencyManagement/{*}dependencies/{*}dependency"):
        mapping[(text(dep, "groupId"), text(dep, "artifactId"))] = interpolate(text(dep, "version"), props)
    return mapping

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("poms", nargs="+", type=Path)
    parser.add_argument("--pending", action="append", default=[], help="Exact first-party group:artifact:version not yet published")
    args = parser.parse_args()
    pending = set(args.pending)
    failures = []
    inspected = set()
    for pom in args.poms:
        root = ET.parse(pom).getroot()
        managed = {}
        for dependency in deps(root, "./{*}dependencyManagement/{*}dependencies/{*}dependency"):
            group, artifact, version = (text(dependency, name) for name in ("groupId", "artifactId", "version"))
            if text(dependency, "type") == "pom" and text(dependency, "scope") == "import":
                key = coordinate(group, artifact, version)
                try:
                    bom, url = xml_for(group, artifact, version)
                    print(f"OK BOM {key} -> {url}")
                    managed.update(bom_versions(bom))
                except RuntimeError as error:
                    failures.append(str(error))
            elif version:
                managed[(group, artifact)] = version
        for dependency in deps(root):
            if text(dependency, "scope") == "test":
                continue
            group, artifact, version = (text(dependency, name) for name in ("groupId", "artifactId", "version"))
            version = version or managed.get((group, artifact), "")
            if not group or not artifact or not version or "${" in version:
                failures.append(f"{pom}: unresolved direct dependency {group}:{artifact}:{version}")
                continue
            key = coordinate(group, artifact, version)
            if key in pending:
                print(f"PENDING FIRST-PARTY {key} (requires publication before consumer validation)")
                continue
            if key in inspected:
                continue
            inspected.add(key)
            try:
                _, url = xml_for(group, artifact, version)
                print(f"OK Central {key} -> {url}")
            except RuntimeError as error:
                failures.append(f"{pom}: {error}")
    if failures:
        for failure in failures:
            print(f"BLOCKED: {failure}", file=sys.stderr)
        print(f"FAIL: {len(failures)} unresolved direct Maven Central dependencies", file=sys.stderr)
        return 1
    print("PASS: direct dependencies and imported BOMs are reachable via Maven Central.")
    print("Note: recursive transitive resolution must still be tested from a Central-only consumer.")
    return 0

if __name__ == "__main__":
    sys.exit(main())
