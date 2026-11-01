#!/usr/bin/env python3
"""Check public release prerequisites before compiling or signing Engine Server."""
from concurrent.futures import ThreadPoolExecutor
import sys
import urllib.error
import urllib.request

CENTRAL = "https://repo.maven.apache.org/maven2/io/github/ryzemcbe"
RELEASES = (
    ("engine-api", "0.30.0"),
    ("common", "0.1.1"),
    ("block-updater", "1.26.60-R1"),
    ("item-updater", "1.26.50-R1"),
    ("cloudburst-math-api", "2.0.0-ryze.1"),
    ("cloudburst-math-immutable", "2.0.0-ryze.1"),
    ("cloudburst-raknet", "1.0.0.CR3-ryze.1"),
    ("nukkit-natives-java", "1.0.3-ryze.1"),
    ("bedrock-codec", "1.26.60-R1"),
    ("bedrock-connection", "1.26.60-R1"),
    ("ryze-transport-common", "2.0.0.CR6-ryze.1"),
    ("ryze-netty-transport-nethernet", "2.0.0.CR6-ryze.1"),
    ("ryze-okaeri-configs-core", "5.0.13-ryze.1"),
    ("ryze-okaeri-configs-yaml-snakeyaml", "5.0.13-ryze.1"),
    ("ryze-libdatachannel-java", "0.24.6.0-ryze.1"),
)


def check_file(coordinate):
    artifact, version, suffix = coordinate
    url = f"{CENTRAL}/{artifact}/{version}/{artifact}-{version}{suffix}"
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "RyzeMCBE-Central-Preflight/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=25) as response:
            status = response.status
    except urllib.error.HTTPError as error:
        status = error.code
    except (urllib.error.URLError, TimeoutError) as error:
        return False, f"BLOCKED {artifact}:{version}{suffix}: {error}"
    return status == 200, f"{'OK' if status == 200 else 'BLOCKED'} HTTP {status}: {url}"


def main():
    files = [(artifact, version, suffix) for artifact, version in RELEASES for suffix in (".pom", ".jar")]
    files.append(("ryze-libdatachannel-java", "0.24.6.0-ryze.1", "-linux-glibc-x86_64.jar"))
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(check_file, files))
    for _, message in results:
        print(message)
    missing = sum(not ok for ok, _ in results)
    if missing:
        print(f"BLOCKED: {missing} required public files are unavailable. No Engine Server upload is allowed.", file=sys.stderr)
        print("Publish Protocol codec/connection, then Engine original-source dependencies, then retry Engine Server.", file=sys.stderr)
        return 1
    print("PASS: all release prerequisites, including the Linux JNI classifier, are public in Maven Central.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
