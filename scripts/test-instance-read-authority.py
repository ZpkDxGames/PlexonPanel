#!/usr/bin/env python3
"""Real libacl/FD tests; --require-isolation also executes multi-UID access denial in CI."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

spec = importlib.util.spec_from_file_location('authority', 'host-agent/examples/fleet/instance-read-authority.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
acl = module.Acl()

def text(path):
    fd = os.open(path, module.OPEN)
    try: return acl.text(fd)
    finally: os.close(fd)

def replace_acl(path, value):
    fd = os.open(path, module.OPEN)
    obj = module.ctypes.c_void_p(acl.lib.acl_from_text(value.encode()))
    try: assert obj and acl.lib.acl_set_fd(fd, obj) == 0
    finally: acl.lib.acl_free(obj); os.close(fd)

with tempfile.TemporaryDirectory() as temporary:
    base = Path(temporary); root = base / 'server'; root.mkdir(mode=0o700)
    uid = os.getuid()
    file = root / 'world.dat'; file.write_bytes(b'fixture'); file.chmod(0o600)
    private = root / 'plugins/PlexonPanel/identity'; private.mkdir(parents=True)
    key = private / 'device.key'; key.write_bytes(b'non-production fixture'); key.chmod(0o600)
    outside = base / 'outside'; outside.write_bytes(b'outside fixture'); outside.chmod(0o600)
    outside_before = text(outside)
    (root / 'escape').symlink_to(outside)
    os.link(outside, root / 'hardlink')
    (root / 'directory-escape').symlink_to(base, target_is_directory=True)
    counts = module.scan(root, uid, uid, 'backup', acl)
    assert counts['updated'] >= 2
    assert f'user:{uid}:r--' in text(file)
    assert text(outside) == outside_before
    assert f'user:{uid}:r--' not in text(key)
    file.chmod(0o600); assert 'mask::---' in text(file)
    module.scan(root, uid, uid, 'backup', acl)
    assert 'mask::r--' in text(file)
    replacement = root / 'new'; replacement.write_bytes(b'replaced'); replacement.chmod(0o600); replacement.replace(file)
    module.scan(root, uid, uid, 'backup', acl); assert f'user:{uid}:r--' in text(file)
    pinned = os.open(file, module.OPEN)
    file.unlink(); file.symlink_to(outside)
    try: acl.grant(pinned, uid, False)
    finally: os.close(pinned)
    assert text(outside) == outside_before
    # A foreign owner is skipped without publication, even in a writable source tree.
    before = text(root)
    assert module.scan(root, uid + 1, uid, 'backup', acl)['updated'] == 0
    assert text(root) == before
    lock = base / 'node.lock'; module.provision_lock(lock, os.getgid())
    before_inode = lock.stat().st_ino; lock.write_bytes(b'preserved existing inode')
    module.provision_lock(lock, os.getgid())
    assert lock.stat().st_ino == before_inode and lock.read_bytes() == b'preserved existing inode'
    lock.chmod(0o600)
    try: module.provision_lock(lock, os.getgid())
    except module.AuthorityError: pass
    else: raise AssertionError('Invalid existing lock must fail closed')
    assert lock.stat().st_ino == before_inode and lock.stat().st_mode & 0o777 == 0o600
    print('PASS: real FD ACL repair, chmod/replacement repair, pinned-inode substitution, exclusions, hard/symlink and foreign-owner denial')
    print('PASS: lock creation, stable existing inode/content, invalid existing mode preserved and denied')

if '--require-isolation' in sys.argv:
    assert os.geteuid() == 0, 'CI isolation requires local root in disposable runner'
    mc, host, other = 61001, 61002, 61003
    with tempfile.TemporaryDirectory() as temporary:
        base = Path(temporary); base.chmod(0o755)
        root = base / 'server'; root.mkdir(mode=0o700); os.chown(root, mc, mc)
        file = root / 'private.db'; file.write_bytes(b'disposable fixture'); file.chmod(0o600); os.chown(file, mc, mc)
        sensitive = base / 'root-private'; sensitive.write_bytes(b'disposable root fixture'); sensitive.chmod(0o600)
        os.link(sensitive, root / 'root-hardlink')
        prior = text(sensitive)
        # An unrelated Host's masked read must remain ineffective when the paired Host is granted read.
        replace_acl(file, f'user::rw-\nuser:{other}:r--\ngroup::---\nmask::---\nother::---\n')
        module.scan(root, mc, host, 'backup', acl)
        assert f'user:{other}:---' in text(file)
        assert text(sensitive) == prior
        probe = 'import os,sys;os.setgroups([]);os.setgid(int(sys.argv[1]));os.setuid(int(sys.argv[1]));p=sys.argv[2];mode=sys.argv[3];\ntry:\n f=open(p,mode);f.close();sys.exit(0)\nexcept PermissionError:sys.exit(13)'
        def permission(user, path, mode):
            return subprocess.run([sys.executable, '-c', probe, str(user), str(path), mode], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode
        assert permission(host, file, 'rb') == 0
        assert permission(host, file, 'ab') == 13
        assert permission(other, file, 'rb') == 13
        assert permission(host, sensitive, 'rb') == 13
        assert permission(host, root / 'root-hardlink', 'rb') == 13
        journals = base / 'namespace'; journals.mkdir(mode=0o700)
        log = journals / 'system.journal'; log.write_bytes(b'disposable journal'); log.chmod(0o600)
        skipped = journals / 'credentials'; skipped.write_bytes(b'disposable fixture'); skipped.chmod(0o600)
        module.scan(journals, 0, host, 'journal', acl)
        assert permission(host, log, 'rb') == 0 and permission(host, log, 'ab') == 13
        assert permission(other, log, 'rb') == 13 and permission(host, skipped, 'rb') == 13
        print('PASS: actual paired Host read, Host write denial, unrelated Host denial, masked-principal preservation, root-hardlink denial and scoped journal read/denial')
else:
    print('NOT_EXECUTED: multi-UID authorization (requires disposable privileged CI runner)')
