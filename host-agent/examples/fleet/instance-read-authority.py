#!/usr/bin/env python3
"""Root-only per-instance read ACL repair; no pathname ACL publication or secret output."""
from __future__ import annotations

import ctypes
import ctypes.util
import errno
import json
import os
from pathlib import Path
import pwd
import re
import stat
import sys
import time
import uuid

MAX_ENTRIES = 200_000
EXCLUDED = (('plugins', 'PlexonPanel', 'identity'), ('plugins', 'PlexonPanel', 'audit'), ('plugins', 'spark', 'tmp'))
OPEN = os.O_RDONLY | os.O_CLOEXEC | os.O_NOFOLLOW | os.O_NONBLOCK

class AuthorityError(RuntimeError):
    pass

def deny(code):
    raise AuthorityError(code)

class Acl:
    def __init__(self):
        name = ctypes.util.find_library('acl')
        if not name:
            deny('ACL_LIBRARY_UNAVAILABLE')
        self.lib = ctypes.CDLL(name, use_errno=True)
        for name, args, result in (
            ('acl_get_fd', [ctypes.c_int], ctypes.c_void_p),
            ('acl_to_any_text', [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char, ctypes.c_int], ctypes.c_void_p),
            ('acl_from_text', [ctypes.c_char_p], ctypes.c_void_p),
            ('acl_calc_mask', [ctypes.POINTER(ctypes.c_void_p)], ctypes.c_int),
            ('acl_set_fd', [ctypes.c_int, ctypes.c_void_p], ctypes.c_int),
            ('acl_free', [ctypes.c_void_p], ctypes.c_int),
        ):
            method = getattr(self.lib, name); method.argtypes = args; method.restype = result

    def text(self, fd):
        acl = self.lib.acl_get_fd(fd)
        if not acl: deny('ACL_READ_FAILED')
        output = None
        try:
            output = self.lib.acl_to_any_text(acl, None, b'\n', 0x08)  # TEXT_NUMERIC_IDS
            if not output: deny('ACL_READ_FAILED')
            return ctypes.string_at(output).decode('ascii')
        finally:
            if output: self.lib.acl_free(output)
            self.lib.acl_free(acl)

    def grant(self, fd, host_uid, directory):
        wanted = 'r-x' if directory else 'r--'
        selector = f'user:{host_uid}:'
        before = self.text(fd)
        lines = [line.split('#', 1)[0].strip() for line in before.splitlines() if line.strip()]
        if any(line == selector + wanted for line in lines):
            # chmod(0600) can mask an existing named entry; recalculate it below.
            masks = [line.partition('mask::')[2] for line in lines if line.startswith('mask::')]
            if masks and all(c == '-' or c == masks[0][i] for i, c in enumerate(wanted)):
                return False
        masks = [line.partition('mask::')[2] for line in lines if line.startswith('mask::')]
        old_mask = masks[0] if masks else 'rwx'
        # Adding the paired Host must not unmask other principals' previously ineffective rights.
        preserved = []
        for line in lines:
            if line.startswith(selector) or line.startswith('mask::'): continue
            parts = line.split(':')
            if parts[0] == 'group' or parts[0] == 'user' and parts[1]:
                parts[2] = ''.join(c if c == old_mask[i] else '-' for i, c in enumerate(parts[2]))
            preserved.append(':'.join(parts))
        lines = preserved
        lines.append(selector + wanted)
        acl = ctypes.c_void_p(self.lib.acl_from_text(('\n'.join(lines) + '\n').encode('ascii')))
        if not acl: deny('ACL_PARSE_FAILED')
        try:
            if self.lib.acl_calc_mask(ctypes.byref(acl)) != 0 or self.lib.acl_set_fd(fd, acl) != 0:
                deny('ACL_WRITE_FAILED')
        finally:
            self.lib.acl_free(acl)
        return True

def trusted(path, directory=False):
    path = Path(path)
    for part in (path, *path.parents):
        info = part.lstat()
        if stat.S_ISLNK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            deny('ROOT_AUTHORITY_INVALID')
    if directory and not path.is_dir(): deny('ROOT_DIRECTORY_INVALID')
    return path

def read_public(path, maximum):
    trusted(path)
    with os.fdopen(os.open(path, OPEN), 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_size > maximum: deny('PUBLIC_FILE_INVALID')
        data = stream.read(maximum + 1)
        if len(data) > maximum: deny('PUBLIC_FILE_INVALID')
        return data.decode('ascii').strip()

def node_id():
    value = read_public('/etc/plexonpanel/node-id', 64)
    parsed = uuid.UUID(value)
    if str(parsed) != value or parsed.version not in range(1, 6) or parsed.variant != uuid.RFC_4122:
        deny('NODE_IDENTITY_INVALID')
    return value

def scope(key):
    if not isinstance(key, str) or not re.fullmatch(r'[a-z][a-z0-9-]{0,27}', key): deny('INSTANCE_KEY_INVALID')
    node = node_id()
    raw = json.loads(read_public('/etc/plexonpanel/instances.json', 131072))
    if type(raw.get('schemaVersion')) is not int or raw['schemaVersion'] != 5 or raw.get('nodeId') != node:
        deny('REGISTRY_INVALID')
    entries = raw.get('instances')
    if not isinstance(entries, list) or not 1 <= len(entries) <= 256:
        deny('REGISTRY_INVALID')
    matches = [entry for entry in entries if entry.get('instanceKey') == key]
    if len(matches) != 1: deny('INSTANCE_NOT_REGISTERED')
    mc = pwd.getpwnam('mc-' + key); host = pwd.getpwnam('pph-' + key)
    if mc.pw_uid == host.pw_uid or mc.pw_uid == 0 or host.pw_uid == 0: deny('INSTANCE_ACCOUNT_INVALID')
    return mc.pw_uid, host.pw_uid

def scan(root, owner_uid, host_uid, mode, acl=None):
    """Walk pinned directories. Only eligible same-filesystem single-link regular files change."""
    root = Path(root)
    acl = acl or Acl()
    counts = {'visited': 0, 'updated': 0, 'skipped': 0}
    root_fd = os.open(root, OPEN | os.O_DIRECTORY)
    device = os.fstat(root_fd).st_dev
    def walk(fd, relative):
        info = os.fstat(fd); counts['visited'] += 1
        if counts['visited'] > MAX_ENTRIES: deny('READ_AUTHORITY_ENTRY_LIMIT')
        is_dir = stat.S_ISDIR(info.st_mode)
        if info.st_uid != owner_uid or info.st_dev != device or (not is_dir and (not stat.S_ISREG(info.st_mode) or info.st_nlink != 1)):
            counts['skipped'] += 1; return
        if mode == 'backup' and any(relative[:len(prefix)] == prefix for prefix in EXCLUDED):
            counts['skipped'] += 1; return
        if not is_dir and mode == 'journal' and not relative[-1].endswith('.journal'):
            counts['skipped'] += 1; return
        counts['updated'] += int(acl.grant(fd, host_uid, is_dir))
        if not is_dir: return
        for name in os.listdir(fd):
            if name in ('.', '..') or '/' in name: continue
            child = None
            try:
                child = os.open(name, OPEN, dir_fd=fd)
                walk(child, relative + (name,))
            except OSError as error:
                if error.errno not in (errno.ENOENT, errno.ELOOP, errno.ENXIO, errno.EACCES, errno.ENOTDIR): raise
                counts['skipped'] += 1
            finally:
                if child is not None: os.close(child)
    try:
        walk(root_fd, ())
    finally:
        os.close(root_fd)
    return counts

def prepare_lock():
    node = node_id()
    for directory in (Path('/run/plexonpanel'), Path('/run/plexonpanel/locks')):
        try: directory.mkdir(mode=0o755)
        except FileExistsError: pass
        trusted(directory, True)
        if stat.S_IMODE(directory.stat().st_mode) != 0o755: deny('NODE_LOCK_DIRECTORY_INVALID')
    import grp
    group = grp.getgrnam('plexonpanel-backup').gr_gid
    path = Path('/run/plexonpanel/locks') / (node + '.lock')
    provision_lock(path, group)

def provision_lock(path, group):
    """Internal path injection for disposable tests; the CLI never accepts a lock pathname."""
    try:
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC, 0o660)
    except FileExistsError:
        fd = os.open(path, os.O_WRONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
        created = False
    else:
        created = True
    try:
        if created:
            os.fchown(fd, 0, group); os.fchmod(fd, 0o660); os.fsync(fd)
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_uid != 0 or info.st_gid != group or stat.S_IMODE(info.st_mode) != 0o660:
            deny('NODE_LOCK_FILE_INVALID')
        after = path.lstat()
        if (after.st_dev, after.st_ino) != (info.st_dev, info.st_ino): deny('NODE_LOCK_FILE_CHANGED')
    finally:
        os.close(fd)
    # Never truncate, unlink, replace or chmod an existing shared lock inode.

def journal_root(key):
    machine = read_public('/etc/machine-id', 64)
    if not re.fullmatch('[0-9a-f]{32}', machine): deny('MACHINE_ID_INVALID')
    root = Path('/var/log/journal') / (machine + '.plexonpanel-' + key)
    trusted(root.parent, True)
    return root

def prepare_journal(key):
    _, host = scope(key)
    root = journal_root(key)
    try:
        root.mkdir(mode=0o750)
    except FileExistsError:
        trusted(root, True)
    else:
        import grp
        fd = os.open(root, OPEN | os.O_DIRECTORY)
        try:
            os.fchown(fd, 0, grp.getgrnam('systemd-journal').gr_gid)
            os.fchmod(fd, 0o2750); os.fsync(fd)
        finally:
            os.close(fd)
    fd = os.open(root, OPEN | os.O_DIRECTORY)
    try: Acl().grant(fd, host, True)
    finally: os.close(fd)

def main():
    if os.geteuid() != 0: deny('LOCAL_ROOT_REQUIRED')
    if sys.argv[1:] == ['prepare-lock']:
        prepare_lock(); print('{"ok":true,"operation":"prepare-lock"}'); return
    if len(sys.argv) == 3 and sys.argv[1] == 'prepare-journal':
        prepare_journal(sys.argv[2]); print('{"ok":true,"operation":"prepare-journal"}'); return
    if len(sys.argv) not in (3, 4) or sys.argv[1] not in ('backup', 'journal') or (len(sys.argv) == 4 and sys.argv[3] != '--once'):
        deny('READ_AUTHORITY_ARGUMENTS_INVALID')
    mode, key = sys.argv[1:3]; mc, host = scope(key)
    if mode == 'backup':
        root = Path('/srv/plexonpanel/servers') / key / 'server'; owner = mc
        trusted(root.parent, True)
    else:
        root = journal_root(key); owner = 0
    while True:
        # Namespace may not exist until the first Minecraft log; never create it.
        if root.exists():
            if root.is_symlink(): deny('READ_AUTHORITY_ROOT_SYMLINK')
            counts = scan(root, owner, host, mode)
        else:
            counts = {'visited': 0, 'updated': 0, 'skipped': 0}
        if len(sys.argv) == 4:
            print(json.dumps({'ok': True, 'instanceKey': key, 'mode': mode, **counts})); return
        time.sleep(2)

if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        code = str(error) if isinstance(error, AuthorityError) else type(error).__name__
        print('plexonpanel-read-authority: ' + code, file=sys.stderr)
        raise SystemExit(2)
