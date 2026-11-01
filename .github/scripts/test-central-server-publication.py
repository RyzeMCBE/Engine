#!/usr/bin/env python3
"""Regression checks for release blockers that must prevent an SDK upload."""
from contextlib import redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import zipfile


def load_script(filename):
    spec = importlib.util.spec_from_file_location(filename, Path(__file__).with_name(filename + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


artifacts = load_script("verify-central-server-artifacts")
prerequisites = load_script("check-central-server-prerequisites")


class SdkPublicationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.build = Path(self.directory.name)
        self.publication = self.build / "publications/maven"
        self.publication.mkdir(parents=True)
        self.libs = self.build / "libs"
        self.libs.mkdir()
        (self.publication / "pom-default.xml").write_text("<project/>")
        self.metadata = {
            "component": {"group": "io.github.ryzemcbe", "module": "engine-server", "version": artifacts.VERSION},
            "variants": [{"name": "runtimeElements", "attributes": {"org.gradle.dependency.bundling": "external"}}],
        }
        self.write_metadata()
        for suffix, source in (("", "org/allaymc/server/Allay.class"), ("-sources", "org/allaymc/server/Allay.java"),
                               ("-javadoc", "index.html")):
            with zipfile.ZipFile(self.jar(suffix), "w") as archive:
                archive.writestr("META-INF/LICENSE", "Engine license fixture")
                archive.writestr(source, "fixture")

    def jar(self, suffix=""):
        return self.libs / f"server-{artifacts.VERSION}{suffix}.jar"

    def write_metadata(self):
        (self.publication / "module.json").write_text(json.dumps(self.metadata))

    def verify(self):
        with redirect_stdout(io.StringIO()):
            artifacts.verify(self.build)

    def test_plain_sdk_is_accepted(self):
        self.verify()

    def test_bundled_api_is_rejected(self):
        with zipfile.ZipFile(self.jar(), "a") as archive:
            archive.writestr("org/allaymc/api/Server.class", "fixture")
        with self.assertRaisesRegex(AssertionError, "dependencies leaked"):
            self.verify()

    def test_shadow_variant_is_rejected(self):
        self.metadata["variants"][0]["attributes"]["org.gradle.dependency.bundling"] = "shadowed"
        self.write_metadata()
        with self.assertRaisesRegex(AssertionError, "Shadow variant"):
            self.verify()

    def test_executable_attachment_is_rejected(self):
        self.metadata["variants"][0]["files"] = [{"name": "Engine-0.14.3-shaded.jar"}]
        self.write_metadata()
        with self.assertRaisesRegex(AssertionError, "Executable published"):
            self.verify()

    def test_missing_javadoc_is_rejected(self):
        self.jar("-javadoc").unlink()
        with self.assertRaisesRegex(AssertionError, "Missing publication file"):
            self.verify()

    def test_wrong_coordinates_are_rejected(self):
        self.metadata["component"]["module"] = "server"
        self.write_metadata()
        with self.assertRaisesRegex(AssertionError, "Incorrect Gradle module"):
            self.verify()


class PublicPrerequisiteTest(unittest.TestCase):
    def test_404_is_a_release_blocker(self):
        error = urllib.error.HTTPError("https://repo.maven.apache.org/maven2/test", 404, "Not Found", {}, None)
        with patch.object(prerequisites.urllib.request, "urlopen", side_effect=error):
            ok, message = prerequisites.check_file(("bedrock-codec", "1.26.60-R1", ".jar"))
        self.assertFalse(ok)
        self.assertIn("BLOCKED HTTP 404", message)

    def test_200_is_required_for_the_native_classifier(self):
        with patch.object(prerequisites.urllib.request, "urlopen") as request:
            request.return_value.__enter__.return_value.status = 200
            ok, _ = prerequisites.check_file(("ryze-libdatachannel-java", "0.24.6.0-ryze.1", "-linux-glibc-x86_64.jar"))
        self.assertTrue(ok)
        self.assertTrue(request.call_args.args[0].full_url.endswith("-linux-glibc-x86_64.jar"))


if __name__ == "__main__":
    unittest.main()
