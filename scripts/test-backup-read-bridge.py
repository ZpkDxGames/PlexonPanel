#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import stat
from types import SimpleNamespace

bridge_path = Path("host-agent/examples/backup-read-bridge.py")
spec = importlib.util.spec_from_file_location("plexonpanel_backup_read_bridge", bridge_path)
bridge = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(bridge)

class FakeStat:
    def __init__(self, inode: int):
        self.st_mode = stat.S_IFREG | 0o600
        self.st_dev = 1
        self.st_ino = inode

original_lstat = bridge.lstat_safe
original_run = bridge.subprocess.run
original_sleep = bridge.time.sleep
try:
    fixed = FakeStat(10)
    bridge.lstat_safe = lambda _path: fixed
    bridge.subprocess.run = lambda *args, **kwargs: SimpleNamespace(returncode=1)
    bridge.time.sleep = lambda _seconds: None
    assert bridge.run_setfacl(Path("/srv/transient.db-wal"), "u:plexonpanel-host:r--") is False

    states = iter([FakeStat(20), FakeStat(21), FakeStat(21)])
    results = iter([SimpleNamespace(returncode=1), SimpleNamespace(returncode=0)])
    bridge.lstat_safe = lambda _path: next(states)
    bridge.subprocess.run = lambda *args, **kwargs: next(results)
    assert bridge.run_setfacl(Path("/srv/replaced.dat"), "u:plexonpanel-host:r--") is True

    root = Path("/opt/plexoncraft/server")
    assert bridge.excluded_path(root, root / "plugins/PlexonPanel/identity/device.key")
    assert bridge.excluded_path(root, root / "plugins/PlexonPanel/audit/audit.jsonl")
    assert bridge.excluded_path(root, root / "plugins/spark/tmp/cache.bin")
    assert not bridge.excluded_path(root, root / "plugins/GhostBlocks/ghostblocks.yml")
finally:
    bridge.lstat_safe = original_lstat
    bridge.subprocess.run = original_run
    bridge.time.sleep = original_sleep
