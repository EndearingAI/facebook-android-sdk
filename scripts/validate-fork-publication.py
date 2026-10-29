#!/usr/bin/env python3
"""Validate real POM/module metadata and AAR outputs, including every SDK edge."""
import json
from pathlib import Path
import sys
from xml.etree import ElementTree

MODULES = {
    "facebook-android-sdk", "facebook-core", "facebook-common", "facebook-bolts",
    "facebook-login", "facebook-share", "facebook-applinks", "facebook-messenger",
    "facebook-gamingservices", "facebook-testutil",
}
RUNTIME_MODULES = MODULES - {"facebook-testutil"}
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def validate(repository: Path, group: str, version: str) -> None:
    graph = {}
    for name in sorted(MODULES):
        root = repository / group.replace(".", "/") / name / version
        base = root / f"{name}-{version}"
        pom = ElementTree.parse(str(base) + ".pom").getroot()
        actual = tuple(pom.findtext("m:" + key, namespaces=NS) for key in ("groupId", "artifactId", "version"))
        assert actual == (group, name, version), (name, "wrong POM coordinates", actual)
        assert pom.find("m:licenses/m:license", NS) is not None, (name, "license missing")
        graph[name] = set()
        for dependency in pom.findall("m:dependencies/m:dependency", NS):
            artifact = dependency.findtext("m:artifactId", namespaces=NS)
            if artifact in MODULES:
                actual = (dependency.findtext("m:groupId", namespaces=NS), dependency.findtext("m:version", namespaces=NS))
                assert actual == (group, version), (name, "unpatched POM edge", artifact, actual)
                graph[name].add(artifact)
        metadata = json.loads(Path(str(base) + ".module").read_text())
        assert metadata["component"] == {"group": group, "module": name, "version": version, "attributes": {"org.gradle.status": "release"}}, (name, "wrong Gradle metadata coordinates")
        for variant in metadata["variants"]:
            for dependency in variant.get("dependencies", []):
                if dependency["module"] in MODULES:
                    assert dependency["group"] == group and dependency["version"]["requires"] == version, (name, "unpatched Gradle edge", dependency)
        assert Path(str(base) + ".aar").stat().st_size > 0, (name, "AAR missing")
        assert Path(str(base) + "-sources.jar").stat().st_size > 0, (name, "sources missing")
    reached = set()
    pending = ["facebook-android-sdk"]
    while pending:
        module = pending.pop()
        if module not in reached:
            reached.add(module)
            pending.extend(graph[module])
    assert reached == RUNTIME_MODULES, ("umbrella runtime graph incomplete", reached)
    print(f"Validated {len(MODULES)} AAR publications and {len(reached)} patched runtime modules at {group}:{version}")


if __name__ == "__main__":
    validate(Path(sys.argv[1]), sys.argv[2], sys.argv[3])
