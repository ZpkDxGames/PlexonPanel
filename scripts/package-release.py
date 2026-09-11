"""Package an explicit allowlist of JARs and examples; never runtime data."""

from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import zipfile

root = Path(__file__).resolve().parent.parent
version_match = re.search(
    r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)"\s*$',
    (root / "build.gradle.kts").read_text(),
    re.MULTILINE,
)
if not version_match:
    raise SystemExit("Cannot read the release version from build.gradle.kts")
version = version_match.group(1)

DASHBOARD_CANDIDATE = "bb2c1d76f38ce9ce49aa7f3ece278cc0df2d02f2"
DASHBOARD_CI_RUN = 34524470086
JAVA_ROLLBACK_VERSION = "v3.1.1"
JAVA_ROLLBACK_COMMIT = "e0984b625d692de6076afa7e20c4fe4b35f07e9a"
DASHBOARD_ROLLBACK_COMMIT = "03777c7dc108b54dda625c7f56f5e723ca35124f"
RUNTIME_CERTIFICATION = "NOT_EXECUTED"

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
    *sorted((root / "agent/examples").glob("*")),
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


def tracked_worktree_modified():
    """Compare reported tracked changes byte-for-byte, including their Git mode."""
    untracked = subprocess.check_output(
        ["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=root
    ).split(b"\0")
    if any(untracked):
        return True
    changed = subprocess.check_output(
        ["git", "diff-index", "--name-only", "-z", "HEAD", "--"], cwd=root
    ).split(b"\0")
    for encoded in (value for value in changed if value):
        relative = Path(os.fsdecode(encoded))
        path = root / relative
        if not path.exists() and not path.is_symlink():
            return True
        try:
            committed = subprocess.check_output(
                ["git", "show", "HEAD:" + relative.as_posix()], cwd=root
            )
            tree_entry = subprocess.check_output(
                ["git", "ls-tree", "HEAD", "--", relative.as_posix()],
                cwd=root,
                text=True,
            )
        except subprocess.CalledProcessError:
            return True
        if path.is_symlink():
            current = os.readlink(path).encode()
            current_mode = "120000"
        elif path.is_file():
            current = path.read_bytes()
            current_mode = "100755" if path.stat().st_mode & stat.S_IXUSR else "100644"
        else:
            return True
        committed_mode = tree_entry.split(maxsplit=1)[0] if tree_entry else ""
        if current != committed or current_mode != committed_mode:
            return True
    return False


dirty = tracked_worktree_modified()
manifest = out / "release-manifest.json"
manifest.write_text(
    json.dumps(
        {
            "version": version,
            "protocolVersion": 3,
            "java": 25,
            "sourceCommit": commit,
            "javaCandidateCommit": commit,
            "dashboardCandidateCommit": DASHBOARD_CANDIDATE,
            "dashboardCiRun": DASHBOARD_CI_RUN,
            "rollback": {
                "plexonPanelVersion": JAVA_ROLLBACK_VERSION,
                "plexonPanelCommit": JAVA_ROLLBACK_COMMIT,
                "dashboardCommit": DASHBOARD_ROLLBACK_COMMIT,
                "dashboardRelay": "deployed Worker relay rollback path",
            },
            "runtimeCertification": RUNTIME_CERTIFICATION,
            "securityReview": "PASS_ZERO_KNOWN_HIGH_CRITICAL_PRODUCT_BLOCKERS",
            "workingTreeModified": dirty,
            "productionAcceptance": "see docs/PHASE2_RUNTIME_GATES.md",
        },
        indent=2,
    )
    + "\n"
)
artifacts.append(manifest)

test_summary = out / "test-summary.txt"
if test_summary.is_file():
    artifacts.append(test_summary)

(out / "SHA256SUMS.txt").write_text(
    "".join(
        hashlib.sha256(path.read_bytes()).hexdigest() + "  " + path.name + "\n"
        for path in artifacts
    )
)
print(f"Packaged PlexonPanel {version} JARs, examples, manifest, test summary and checksums")
