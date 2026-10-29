#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
fork_group="${FORK_PUBLICATION_GROUP:-com.github.EndearingAI.facebook-android-sdk}"
fork_version="${FORK_PUBLICATION_VERSION:-${VERSION:-$(git rev-parse HEAD)}}"
./gradlew -PforkPublicationGroup="$fork_group" -PforkPublicationVersion="$fork_version" \
  publishReleasePublicationToMavenRepository publishReleasePublicationToMavenLocal \
  --console=plain --max-workers=2
python3 scripts/validate-fork-publication.py build/fork-maven "$fork_group" "$fork_version"
./gradlew -p scripts/publication-check \
  -PforkRepository="$(pwd)/build/fork-maven" \
  -PforkPublicationGroup="$fork_group" -PforkPublicationVersion="$fork_version" \
  verifyForkGraph --console=plain --max-workers=2
