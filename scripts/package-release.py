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


def tracked_worktree_modified():
    """Compare reported tracked changes byte-for-byte, including their Git mode.

    The baseline Windows wrapper can be reported by diff-index after checkout even when its raw
    CRLF bytes are identical to HEAD. Generated build products remain intentionally irrelevant.
    """
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
