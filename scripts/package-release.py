"""Package an explicit allowlist of JARs and examples; never runtime data."""

from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
version_match = re.search(
    r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$',
    (root / "build.gradle.kts").read_text(),
    re.MULTILINE,
)
if not version_match:
    raise SystemExit("Cannot read the release version from build.gradle.kts")
version = version_match.group(1)

out = root / "build/release"
out.mkdir(parents=True, exist_ok=True)
artifacts = []
for rel in [
    f"agent/build/libs/PlexonPanel-{version}.jar",
    f"host-agent/build/libs/plexonpanel-host-{version}.jar",
]:
    source = root / rel
    if not source.is_file():
        raise SystemExit("Build the JAR first: " + rel)
    dest = out / source.name
    shutil.copyfile(source, dest)
    artifacts.append(dest)

archive = out / f"PlexonPanel-{version}-examples.zip"
examples = [
    root / "agent/src/main/resources/config.yml",
    *sorted((root / "host-agent/examples").glob("*")),
    *sorted((root / "docs").glob("*.md")),
    root / "README.md",
    root / "CHANGELOG.md",
    root / "PRIVACY.md",
]
with zipfile.ZipFile(archive, "w") as zip_file:
    for path in examples:
        info = zipfile.ZipInfo(path.relative_to(root).as_posix(), (1980, 1, 1, 0, 0, 0))
        info.external_attr = 0o100644 << 16
        info.compress_type = zipfile.ZIP_DEFLATED
        zip_file.writestr(info, path.read_bytes())
artifacts.append(archive)

commit = subprocess.check_output(
    ["git", "rev-parse", "HEAD"], cwd=root, text=True
).strip()
dirty = bool(
    subprocess.check_output(
        ["git", "status", "--porcelain"], cwd=root, text=True
    ).strip()
)
manifest = out / "release-manifest.json"
manifest.write_text(
    json.dumps(
        {
            "version": version,
            "protocolVersion": 3,
            "java": 25,
            "sourceCommit": commit,
            "workingTreeModified": dirty,
            "productionAcceptance": "see docs/release-gates.json",
        },
        indent=2,
    )
    + "\n"
)
artifacts.append(manifest)
(out / "SHA256SUMS.txt").write_text(
    "".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
        for path in artifacts
    )
)
print(f"Packaged PlexonPanel {version} JARs, examples, manifest and checksums")
