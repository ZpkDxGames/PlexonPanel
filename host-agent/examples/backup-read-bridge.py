#!/usr/bin/env python3
"""PlexonPanel live-backup read bridge.

Runs only as a root-owned local service. It watches trusted top-level backup includes and repairs a
named read/traverse ACL for plexonpanel-host after applications create or atomically replace files.
It never accepts commands from the Dashboard, relay, Paper, or Host process.
"""

from __future__ import annotations

import ctypes
import errno
import json
import os
from pathlib import Path
import pwd
import select
import stat
import struct
import subprocess
import sys
import time

IN_ATTRIB = 0x00000004
IN_CLOSE_WRITE = 0x00000008
IN_MOVED_TO = 0x00000080
IN_CREATE = 0x00000100
IN_DELETE_SELF = 0x00000400
IN_MOVE_SELF = 0x00000800
IN_IGNORED = 0x00008000
WATCH_MASK = IN_ATTRIB | IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_DELETE_SELF | IN_MOVE_SELF
EVENT = struct.Struct("iIII")
MAX_WATCHES = 200_000
MAX_EVENT_BYTES = 1 << 20
SETFACL_ATTEMPTS = 3
SETFACL_RETRY_DELAY_SECONDS = 0.02

# These paths are deliberately outside the Host backup/read authority even though "plugins" is a
# configured top-level include. In particular, never grant the Host read access to device.key.
EXCLUDED_RELATIVE_PREFIXES = (
    Path("plugins/PlexonPanel/audit"),
    Path("plugins/PlexonPanel/identity"),
    Path("plugins/spark/tmp"),
)

libc = ctypes.CDLL("libc.so.6", use_errno=True)
libc.inotify_init1.argtypes = [ctypes.c_int]
libc.inotify_init1.restype = ctypes.c_int
libc.inotify_add_watch.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_uint32]
libc.inotify_add_watch.restype = ctypes.c_int


class BridgeError(RuntimeError):
    pass


def fail(message: str) -> None:
    raise BridgeError(message)


def load_config(path: Path) -> tuple[Path, str, list[str]]:
    if os.geteuid() != 0:
        fail("backup read bridge must run as root")
    st = path.lstat()
    if not stat.S_ISREG(st.st_mode) or st.st_uid != 0 or st.st_mode & 0o022:
        fail("bridge config must be a root-owned, non-writable regular file")
    if st.st_size > 16_384:
        fail("bridge config exceeds limit")
    raw = json.loads(path.read_text(encoding="utf-8"))
    if set(raw) != {"serverRoot", "hostUser", "include"}:
        fail("bridge config has unknown or missing keys")
    root = Path(raw["serverRoot"])
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        fail("serverRoot must be an existing non-symlink absolute directory")
    root = Path(os.path.realpath(root))
    user = raw["hostUser"]
    if user != "plexonpanel-host":
        fail("hostUser must be plexonpanel-host")
    pwd.getpwnam(user)
    includes = raw["include"]
    if not isinstance(includes, list) or not (1 <= len(includes) <= 64):
        fail("include must contain 1..64 top-level names")
    seen: set[str] = set()
    for item in includes:
        if (
            not isinstance(item, str)
            or not item
            or len(item) > 128
            or item in {".", ".."}
            or item.startswith(".")
            or "/" in item
            or "\\" in item
            or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-" for c in item)
        ):
            fail("include entries must be safe top-level names")
        if item in seen:
            fail("include entries must be unique")
        seen.add(item)
    return root, user, includes


def contained(root: Path, candidate: Path) -> bool:
    try:
        return os.path.commonpath([str(root), str(candidate)]) == str(root)
    except ValueError:
        return False


def lstat_safe(path: Path):
    try:
        return path.lstat()
    except (FileNotFoundError, PermissionError):
        return None


def inode_key(st) -> tuple[int, int]:
    return st.st_dev, st.st_ino


def excluded_path(root: Path, path: Path) -> bool:
    try:
        relative = path.relative_to(root)
    except ValueError:
        return True
    return any(relative == prefix or prefix in relative.parents for prefix in EXCLUDED_RELATIVE_PREFIXES)


def run_setfacl(path: Path, acl: str) -> bool:
    """Best-effort bounded ACL repair for a single mutable pathname.

    SQLite WAL/SHM and atomic-save paths can disappear or change inode between inotify delivery,
    lstat and setfacl. Such per-path churn must never terminate the guardian. A later inotify event
    or the authoritative backup preflight will catch any durable unreadable file.
    """
    for attempt in range(SETFACL_ATTEMPTS):
        before = lstat_safe(path)
        if before is None or stat.S_ISLNK(before.st_mode):
            return False
        before_key = inode_key(before)
        try:
            result = subprocess.run(
                ["/usr/bin/setfacl", "-m", acl, "--", str(path)],
                check=False,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
            result = None
        if result is not None and result.returncode == 0:
            return True

        after = lstat_safe(path)
        if after is None or stat.S_ISLNK(after.st_mode):
            return False
        if inode_key(after) != before_key:
            if attempt + 1 < SETFACL_ATTEMPTS:
                time.sleep(SETFACL_RETRY_DELAY_SECONDS)
            continue

        # Persistent failure on one path is not a service-fatal condition. The Host's preflight is
        # the fail-closed authority for durable unreadable data. Keep watching the remaining tree.
        return False
    return False


def set_acl(path: Path, user: str, directory: bool) -> bool:
    # No shell. setfacl recalculates the ACL mask to include the named user without granting write.
    permission = "r-x" if directory else "r--"
    if not run_setfacl(path, f"u:{user}:{permission}"):
        return False
    if directory:
        # Defaults help normal creates; event repair still handles chmod(0600)/atomic replacements.
        if not run_setfacl(path, f"d:u:{user}:r-x"):
            return False
    return True


def repair(root: Path, path: Path, user: str, root_entry: bool = False) -> bool:
    st = lstat_safe(path)
    if st is None or stat.S_ISLNK(st.st_mode):
        return False
    resolved = Path(os.path.realpath(path))
    if not contained(root, resolved):
        return False
    if excluded_path(root, path):
        return False
    if root_entry:
        # Java backup/preflight walkers enumerate the server root, so the Host needs read + traverse
        # here as well as on configured include directories. Never grant write.
        if not run_setfacl(path, f"u:{user}:r-x"):
            return False
        return stat.S_ISDIR(st.st_mode)
    if stat.S_ISDIR(st.st_mode):
        return set_acl(path, user, True)
    if stat.S_ISREG(st.st_mode):
        set_acl(path, user, False)
    return False


def scan_tree(root: Path, include_path: Path, user: str, add_watch) -> None:
    if excluded_path(root, include_path):
        return
    st = lstat_safe(include_path)
    if st is None or stat.S_ISLNK(st.st_mode):
        return
    resolved = Path(os.path.realpath(include_path))
    if not contained(root, resolved):
        return
    if stat.S_ISREG(st.st_mode):
        repair(root, include_path, user)
        return
    if not stat.S_ISDIR(st.st_mode):
        return
    for current, dirs, files in os.walk(include_path, topdown=True, followlinks=False):
        current_path = Path(current)
        if excluded_path(root, current_path):
            dirs[:] = []
            continue
        if not contained(root, Path(os.path.realpath(current_path))):
            dirs[:] = []
            continue
        repair(root, current_path, user)
        add_watch(current_path)
        safe_dirs = []
        for name in dirs:
            child = current_path / name
            st_child = lstat_safe(child)
            if (
                st_child is not None
                and not stat.S_ISLNK(st_child.st_mode)
                and not excluded_path(root, child)
            ):
                safe_dirs.append(name)
        dirs[:] = safe_dirs
        for name in files:
            child = current_path / name
            if not excluded_path(root, child):
                repair(root, child, user)


def main() -> int:
    if len(sys.argv) != 2:
        fail("usage: backup-read-bridge.py /etc/plexonpanel-host/read-bridge.json")
    config_path = Path(sys.argv[1]).absolute()
    root, user, includes = load_config(config_path)
    if not Path("/usr/bin/setfacl").is_file():
        fail("/usr/bin/setfacl is required; install the acl package")

    repair(root, root, user, root_entry=True)
    fd = libc.inotify_init1(os.O_CLOEXEC | os.O_NONBLOCK)
    if fd < 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))
    watches: dict[int, Path] = {}

    def add_watch(directory: Path) -> None:
        if excluded_path(root, directory):
            return
        if len(watches) >= MAX_WATCHES:
            fail("inotify watch safety limit exceeded")
        encoded = os.fsencode(directory)
        wd = libc.inotify_add_watch(fd, encoded, WATCH_MASK)
        if wd < 0:
            error = ctypes.get_errno()
            if error in (errno.ENOENT, errno.EACCES):
                return
            raise OSError(error, os.strerror(error), str(directory))
        watches[wd] = directory

    for include in includes:
        scan_tree(root, root / include, user, add_watch)

    poller = select.poll()
    poller.register(fd, select.POLLIN)
    while True:
        poller.poll()
        try:
            data = os.read(fd, MAX_EVENT_BYTES)
        except BlockingIOError:
            continue
        offset = 0
        while offset + EVENT.size <= len(data):
            wd, mask, _cookie, name_length = EVENT.unpack_from(data, offset)
            offset += EVENT.size
            name_raw = data[offset : offset + name_length]
            offset += name_length
            base = watches.get(wd)
            if base is None:
                continue
            if mask & IN_IGNORED:
                watches.pop(wd, None)
                continue
            name = os.fsdecode(name_raw.split(b"\0", 1)[0]) if name_length else ""
            target = base / name if name else base
            if mask & (IN_DELETE_SELF | IN_MOVE_SELF):
                watches.pop(wd, None)
                continue
            if excluded_path(root, target):
                continue
            is_dir = repair(root, target, user)
            if is_dir and mask & (IN_CREATE | IN_MOVED_TO):
                scan_tree(root, target, user, add_watch)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except BridgeError as exc:
        print(f"plexonpanel-backup-read-bridge: {exc}", file=sys.stderr)
        raise SystemExit(2)
