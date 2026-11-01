#!/usr/bin/env bash
# Verify the release candidate without a signing key or publishing credentials.
set -euo pipefail

python3 .github/scripts/verify-central-server-pom.py
python3 .github/scripts/audit-central-resolution.py \
  server/build/publications/maven/pom-default.xml

central_test_repo="$(mktemp -d)"
trap 'rm -rf "$central_test_repo"' EXIT
mvn --batch-mode --no-transfer-progress \
  --settings .github/maven-central-only-settings.xml \
  -Dmaven.repo.local="$central_test_repo" \
  -f server/build/publications/maven/pom-default.xml \
  org.apache.maven.plugins:maven-dependency-plugin:3.10.0:resolve

python3 .github/scripts/verify-central-server-artifacts.py
