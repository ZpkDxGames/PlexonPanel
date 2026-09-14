#!/usr/bin/env python3
"""Verify that the Host companion JAR is portable across supported Linux architectures."""

from __future__ import annotations

import argparse
import json
import platform
from pathlib import Path
import sys
import zipfile

SUPPORTED_PLATFORMS = {
    "linux-x64": {"x86_64", "amd64"},
    "linux-arm64": {"aarch64", "arm64"},
}
NATIVE_SUFFIXES = (".so", ".dll", ".dylib", ".jnilib")
REQUIRED_ENTRIES = {
    "io/github/zpkdxgames/plexonpanel/host/HostMain.class",
    "io/github/zpkdxgames/plexonpanel/host/RconMinecraftCommandChannel.class",
}
JAVA_25_CLASS_MAJOR = 69


def normalized_platform(system: str, machine: str) -> str:
    if system.lower() != "linux":
        return "unsupported"
    lowered = machine.lower()
    for name, aliases in SUPPORTED_PLATFORMS.items():
        if lowered in aliases:
            return name
    return "unsupported"


def manifest_attributes(raw: str) -> dict[str, str]:
    attributes: dict[str, str] = {}
    current_key: str | None = None
    for line in raw.replace("\r\n", "\n").split("\n"):
        if line.startswith(" ") and current_key is not None:
            attributes[current_key] += line[1:]
            continue
        if ": " not in line:
            current_key = None
            continue
        key, value = line.split(": ", 1)
        attributes[key] = value
        current_key = key
    return attributes


def class_major(data: bytes) -> int:
    if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("HostMain.class is not a valid JVM class file")
    return int.from_bytes(data[6:8], "big")


def fail(message: str) -> "NoReturn":
    print(f"Host portability verification failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("jar", type=Path)
    parser.add_argument(
        "--expected-platform",
        choices=sorted(SUPPORTED_PLATFORMS),
        help="Require the verifier itself to be running on this Linux platform.",
    )
    args = parser.parse_args()

    jar = args.jar.resolve()
    if not jar.is_file():
        fail(f"JAR does not exist: {jar}")

    system = platform.system()
    machine = platform.machine()
    actual_platform = normalized_platform(system, machine)
    if args.expected_platform and actual_platform != args.expected_platform:
        fail(
            f"runner architecture mismatch: expected {args.expected_platform}, "
            f"got {system}/{machine} ({actual_platform})"
        )

    try:
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            missing = sorted(REQUIRED_ENTRIES - names)
            if missing:
                fail("missing required Host classes: " + ", ".join(missing))

            native_entries = sorted(
                name
                for name in names
                if name.lower().endswith(NATIVE_SUFFIXES)
                or "/native/" in name.lower()
                or name.lower().startswith("native/")
            )
            if native_entries:
                fail(
                    "architecture-specific native payload found in Host JAR: "
                    + ", ".join(native_entries[:10])
                )

            try:
                manifest = manifest_attributes(
                    archive.read("META-INF/MANIFEST.MF").decode("utf-8")
                )
            except KeyError:
                fail("META-INF/MANIFEST.MF is missing")

            if manifest.get("Main-Class") != "io.github.zpkdxgames.plexonpanel.host.HostMain":
                fail("unexpected or missing Host Main-Class manifest entry")

            implementation_version = manifest.get("Implementation-Version", "").strip()
            if not implementation_version:
                fail("Implementation-Version is missing from Host manifest")

            major = class_major(archive.read("io/github/zpkdxgames/plexonpanel/host/HostMain.class"))
            if major != JAVA_25_CLASS_MAJOR:
                fail(f"HostMain.class major must be {JAVA_25_CLASS_MAJOR} for Java 25, got {major}")
    except zipfile.BadZipFile:
        fail("Host artifact is not a valid JAR/ZIP")

    report = {
        "jar": str(jar),
        "artifactType": "architecture-neutral-java-jar",
        "implementationVersion": implementation_version,
        "javaClassMajor": major,
        "runnerSystem": system,
        "runnerMachine": machine,
        "runnerPlatform": actual_platform,
        "supportedPlatforms": sorted(SUPPORTED_PLATFORMS),
        "nativePayload": False,
    }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
