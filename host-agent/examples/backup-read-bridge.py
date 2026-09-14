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

IN_ATTRIB = 0x00000004
IN_CLOSE_WRITE = 0x00000008
IN_MOVED_TO = 0x00000080
IN_CREATE = 0x00000100
IN_DELETE_SELF = 0x00000400
IN_MOVE_SELF = 0x00000800
IN_IGNORED = 0x00008000
IN_ISDIR = 0x40000000
WATCH_MASK = IN_ATTRIB | IN_CLOSE_WRITE | IN_MOVED_TO | IN_CREATE | IN_DELETE_SELF | IN_MOVE_SELF
EVENT = struct.Struct("iIII")
MAX_WATCHES = 200_000
MAX_EVENT_BYTES = 1 << 20
PANEL_ACCESS_RELATIVE = Path("plugins") / "PlexonPanel" / "access"

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


def panel_access_path(root: Path, path: Path) -> bool:
    """Return true for PlexonPanel's Host/Paper shared writable registry subtree.

    That path has its own group/ACL contract and must never receive the bridge's read-only named
    user ACL, because a named user ACL overrides the host user's writable group membership.
    """
    access_root = root / PANEL_ACCESS_RELATIVE
    absolute = path.absolute()
    return absolute == access_root or access_root in absolute.parents


def lstat_safe(path: Path):
    try:
        return path.lstat()
    except (FileNotFoundError, PermissionError):
        return None


def run_setfacl(path: Path, acl: str) -> bool:
    """Apply one ACL update, tolerating only a target that vanished during event processing."""
    try:
        subprocess.run(
            ["/usr/bin/setfacl", "-m", acl, "--", str(path)],
            check=True,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=10,
        )
        return True
    except subprocess.CalledProcessError:
        # Paper and plugins commonly create, rename and delete temp/WAL/SHM files within the same
        # inotify turn. A vanished target is normal churn, not a bridge failure. Persistent targets
        # must still fail closed so a real ACL/permission problem remains operator-visible.
        st = lstat_safe(path)
        if st is None or stat.S_ISLNK(st.st_mode):
            return False
        raise


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
    if panel_access_path(root, path):
        return False
    if root_entry:
        # The service needs traverse only on the server root itself.
        if not run_setfacl(path, f"u:{user}:--x"):
            return False
        return stat.S_ISDIR(st.st_mode)
    if stat.S_ISDIR(st.st_mode):
        return set_acl(path, user, True)
    if stat.S_ISREG(st.st_mode):
        set_acl(path, user, False)
    return False


def scan_tree(root: Path, include_path: Path, user: str, add_watch) -> None:
    if panel_access_path(root, include_path):
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
        if panel_access_path(root, current_path):
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
                and not panel_access_path(root, child)
            ):
                safe_dirs.append(name)
        dirs[:] = safe_dirs
        for name in files:
            repair(root, current_path / name, user)


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
