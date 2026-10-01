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
from datetime import datetime, timezone

root = Path(__file__).resolve().parent.parent
version_match = re.search(
    r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?)"\s*$',
    (root / "build.gradle.kts").read_text(),
    re.MULTILINE,
)
if not version_match:
    raise SystemExit("Cannot read the release version from build.gradle.kts")
version = version_match.group(1)

HOST_ARTIFACT_TYPE = "architecture-neutral-java-jar"
HOST_SUPPORTED_PLATFORMS = ["linux-x64", "linux-arm64"]

contract_path = root / f"docs/release-gates-{version}.json"
if not contract_path.is_file():
    raise SystemExit(f"Missing coordinated release gate file: {contract_path.relative_to(root)}")
contract = json.loads(contract_path.read_text())
if contract.get("version") != version or contract.get("protocolVersion") != 3:
    raise SystemExit("Release gate version/protocol does not match the source candidate")
dashboard_commit = contract.get("dashboardCommit", "")
dashboard_ci_run = contract.get("dashboardCiRun")
if not re.fullmatch(r"[0-9a-f]{40}", dashboard_commit):
    raise SystemExit("Release gates require the exact 40-character Dashboard commit")
if not isinstance(dashboard_ci_run, int) or dashboard_ci_run < 1:
    raise SystemExit("Release gates require the passing Dashboard CI run ID")
certification = contract.get("certification")
if not isinstance(certification, dict):
    raise SystemExit("Release gates require explicit certification states")

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
    *[
        root / "docs" / name
        for name in [
            "ACCESS.md",
            "BACKUPS.md",
            "BACKUP_READ_CONTRACT.md",
            "CONFIGURATION.md",
            "FULL_CONTROL.md",
            "HOST_AGENT.md",
            "HOST_AUTHORIZATION_MIRROR.md",
            "INSTALLATION.md",
            "OPERATIONS.md",
            "PERMISSIONS.md",
            "PLEXONCORE.md",
            "PROTOCOL.md",
            "TROUBLESHOOTING.md",
            "VALIDATION.md",
            "ADR-4.0-ARCHITECTURE.md",
            "4.0-source-audit.md",
            f"release-{version}.md",
        ]
    ],
    root / "scripts/verify-host-portability.py",
    root / "README.md",
    root / "CHANGELOG.md",
    root / "PRIVACY.md",
]
with zipfile.ZipFile(archive, "w") as zip_file:
    for path in (candidate for candidate in examples if candidate.is_file()):
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
test_summary = out / "test-summary.txt"
if test_summary.is_file():
    artifacts.append(test_summary)
build_timestamp = os.environ.get("PLEXON_BUILD_TIMESTAMP", "").strip()
try:
    parsed_timestamp = datetime.fromisoformat(build_timestamp.replace("Z", "+00:00"))
except ValueError:
    source_timestamp = subprocess.check_output(
        ["git", "show", "-s", "--format=%cI", "HEAD"], cwd=root, text=True
    ).strip()
    parsed_timestamp = datetime.fromisoformat(source_timestamp.replace("Z", "+00:00"))
build_timestamp = parsed_timestamp.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
backend_ci_run = os.environ.get("GITHUB_RUN_ID", "").strip()
artifact_details = {
    path.name: {
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "bytes": path.stat().st_size,
    }
    for path in artifacts
}
manifest = out / "release-manifest.json"
manifest.write_text(
    json.dumps(
        {
            "version": version,
            "protocolVersion": 3,
            "java": 25,
            "paperApi": "26.2.build.121-stable",
            "sourceCommit": commit,
            "buildCommit": commit,
            "javaCandidateCommit": commit,
            "backendCiRun": int(backend_ci_run) if backend_ci_run.isdigit() else None,
            "dashboardCandidateCommit": dashboard_commit,
            "dashboardSourceCommit": dashboard_commit,
            "relaySourceCommit": dashboard_commit,
            "dashboardCiRun": dashboard_ci_run,
            "buildTimestamp": build_timestamp,
            "hostArtifactType": HOST_ARTIFACT_TYPE,
            "hostSupportedPlatforms": HOST_SUPPORTED_PLATFORMS,
            "rollback": contract.get("rollback"),
            "certification": certification,
            "runtimeCertification": certification.get("runtime", "NOT_EXECUTED"),
            "securityReview": certification.get("security", "NOT_EXECUTED"),
            "workingTreeModified": dirty,
            "artifacts": artifact_details,
            "productionAcceptance": f"see docs/release-{version}.md and {contract_path.name}",
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
print(
    f"Packaged PlexonPanel {version} JARs, examples, manifest, test summary and checksums "
    f"for Host platforms {', '.join(HOST_SUPPORTED_PLATFORMS)}"
)
