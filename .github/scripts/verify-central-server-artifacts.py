#!/usr/bin/env python3
"""Validate the plain server SDK and, optionally, its original GPG signatures."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

VERSION = "0.14.3-beta.2"
FINGERPRINT = "0DE435F45DAAE3CCB759E01B32F428403DE9E4D8"


def verify(build, public_key=None):
    publication = build / "publications/maven"
    jars = [build / "libs" / f"server-{VERSION}{suffix}.jar" for suffix in ("", "-sources", "-javadoc")]
    artifacts = [publication / "pom-default.xml", publication / "module.json", *jars]
    for path in artifacts:
        assert path.is_file() and path.stat().st_size > 0, f"Missing publication file: {path}"

    with zipfile.ZipFile(jars[0]) as archive:
        names = archive.namelist()
        assert archive.testzip() is None, "Corrupt SDK JAR"
        assert "org/allaymc/server/Allay.class" in names, "Missing server implementation"
        assert "META-INF/LICENSE" in names, "Missing Engine license"
        bundled = [name for name in names if name.endswith(".class") and name.startswith(
            ("org/allaymc/api/", "org/cloudburstmc/", "io/netty/", "eu/okaeri/"))]
        assert not bundled, f"Executable/shaded dependencies leaked into SDK: {bundled[:5]}"
    for path, suffix in ((jars[1], ".java"), (jars[2], ".html")):
        with zipfile.ZipFile(path) as archive:
            assert archive.testzip() is None, f"Corrupt archive: {path}"
            assert any(name.endswith(suffix) for name in archive.namelist()), f"Empty documentation/source archive: {path}"
            assert "META-INF/LICENSE" in archive.namelist(), f"Missing license: {path}"

    metadata = json.loads(artifacts[1].read_text())
    component = metadata["component"]
    assert (component["group"], component["module"], component["version"]) == (
        "io.github.ryzemcbe", "engine-server", VERSION), "Incorrect Gradle module coordinates"
    for variant in metadata["variants"]:
        assert variant.get("attributes", {}).get("org.gradle.dependency.bundling") != "shadowed", "Shadow variant leaked into SDK"
        assert not any("shaded.jar" in file["name"] for file in variant.get("files", [])), "Executable published with SDK"

    if public_key is not None:
        with tempfile.TemporaryDirectory() as directory:
            command = ["gpg", "--no-options", "--homedir", directory, "--batch"]
            subprocess.run([*command, "--import", str(public_key.resolve())], check=True, capture_output=True)
            for path in artifacts:
                signature = Path(str(path) + ".asc")
                assert signature.is_file(), f"Missing signature: {signature}"
                result = subprocess.run([*command, "--status-fd", "1", "--verify", str(signature), str(path)],
                                        check=True, capture_output=True, text=True)
                valid = [line.split() for line in result.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
                assert any(parts[2] == FINGERPRINT or parts[-1] == FINGERPRINT for parts in valid), f"Wrong signing identity: {path}"
    print(f"PASS: plain Engine Server SDK, sources, Javadoc, license and metadata{' with original GPG signatures' if public_key else ''}.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, default=Path("server/build"))
    parser.add_argument("--public-key", type=Path, help="Verify every artifact signature against the original public GPG key")
    args = parser.parse_args()
    verify(args.build, args.public_key)
