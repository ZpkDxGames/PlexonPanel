#!/usr/bin/env python3
"""Disposable fixtures only; no VPS services, identities, or credentials are used."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location('drive_setup', 'scripts/instance-drive-backup.py')
setup = importlib.util.module_from_spec(spec); spec.loader.exec_module(setup)
KEY = 'tonimsmp'
SERVER = '11111111-1111-4111-8111-111111111111'
CONFIG = {
    'schemaVersion': 5, 'serverId': SERVER, 'relayPublicKey': 'public-fixture',
    'fleet': {'instanceKey': KEY, 'nodeId': '22222222-2222-4222-8222-222222222222'},
    'serverRoot': f'/srv/plexonpanel/servers/{KEY}/server',
    'dataDirectory': f'/var/lib/plexonpanel/instances/{KEY}',
    'accessRegistry': f'/var/lib/plexonpanel/instances/{KEY}/access/devices.json',
    'serviceName': f'minecraft@{KEY}.service',
    'capabilities': {'maintenance.run': True},
    'backups': {'directory': f'/var/backups/plexonpanel/instances/{KEY}', 'rcloneExecutable': '/usr/bin/rclone',
                'rcloneRemote': '', 'rcloneConfig': f'/etc/plexonpanel/instances/{KEY}/rclone.conf',
                'include': ['world', 'plugins'], 'retentionCount': 7, 'maximumBytes': 20000000, 'restoreEnabled': False},
}
# Deliberately fake token: never a production credential.
CREDENTIALS = b'[gdrive]\ntype = drive\ntoken = {"fixture":"not-a-credential"}\n'
FOLDER = 'fixture_folder_identifier'

class DriveSetupTest(unittest.TestCase):
    def test_folder_id_from_id_or_url(self):
        for value in (FOLDER, f'https://drive.google.com/drive/folders/{FOLDER}?usp=sharing', f'https://drive.google.com/drive/u/0/folders/{FOLDER}'):
            self.assertEqual(setup.folder_id(value), FOLDER)
        for value in ('../escape', 'short', f'https://evil.test/drive/folders/{FOLDER}', f'https://drive.google.com.evil.test/drive/folders/{FOLDER}'):
            with self.assertRaises(setup.SetupError): setup.folder_id(value)

    def test_proposal_preserves_identity_policies_and_retention(self):
        before = copy.deepcopy(CONFIG)
        updated, seed = setup.proposal(CONFIG, KEY, FOLDER, CREDENTIALS)
        self.assertEqual(CONFIG, before)
        expected = copy.deepcopy(CONFIG)
        expected['backups'].update(enabled=True, rcloneRemote=f'gdrive:plexonpanel/{SERVER}', rcloneConfig=f'/etc/plexonpanel/instances/{KEY}/rclone.conf')
        self.assertEqual(updated, expected)
        self.assertIn(f'root_folder_id = {FOLDER}'.encode(), seed)
        self.assertIn(b'not-a-credential', seed)  # Private seed content only, never status/plan JSON.

    def test_foreign_instance_and_layout_denied(self):
        for field, value in [('serverRoot', '/srv/plexonpanel/servers/plexoncraft/server'), ('dataDirectory', '/tmp/state'), ('serviceName', 'minecraft@plexoncraft.service')]:
            config = copy.deepcopy(CONFIG); config[field] = value
            with self.assertRaises(setup.SetupError): setup.proposal(config, KEY, FOLDER, CREDENTIALS)
        with self.assertRaises(setup.SetupError): setup.proposal(CONFIG, 'plexoncraft', FOLDER, CREDENTIALS)

    def test_invalid_include_and_multi_remote_credentials_denied(self):
        for includes in ([], ['../world'], ['world', 'world'], ['world/private']):
            config = copy.deepcopy(CONFIG); config['backups']['include'] = includes
            with self.assertRaises(setup.SetupError): setup.proposal(config, KEY, FOLDER, CREDENTIALS)
        for credentials in (b'[gdrive]\ntype=s3\ntoken=x\n', b'[gdrive]\ntype=drive\n', CREDENTIALS + b'\n[other]\ntype=drive\n', CREDENTIALS + b'service_account_file=/etc/private\n'):
            with self.assertRaises(setup.SetupError): setup.proposal(CONFIG, KEY, FOLDER, credentials)

    def test_private_read_rejects_public_symlink_and_hardlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); source = root / 'seed'; source.write_bytes(CREDENTIALS); source.chmod(0o600)
            self.assertEqual(setup.read_private(source), CREDENTIALS)
            source.chmod(0o644)
            with self.assertRaises(setup.SetupError): setup.read_private(source)
            source.chmod(0o600); link = root / 'link'; link.symlink_to(source)
            with self.assertRaises(setup.SetupError): setup.read_private(link)
            os.link(source, root / 'hardlink')
            with self.assertRaises(setup.SetupError): setup.read_private(source)

    def modern_fixture(self, root):
        config = copy.deepcopy(CONFIG)
        config['backups']['include'] = ['world', 'world_nether', 'world_the_end', 'plugins', 'permissions.yml', 'required-other-file']
        for name in ('overworld', 'the_nether', 'the_end'):
            (root / 'world' / 'dimensions' / 'minecraft' / name).mkdir(parents=True)
        actual_safe_path = setup.safe_path
        def route(value):
            path = Path(value)
            prefix = Path(CONFIG['serverRoot'])
            return actual_safe_path(root / path.relative_to(prefix)) if path.is_relative_to(prefix) else actual_safe_path(path)
        return config, route

    def test_modern_layout_removes_only_absent_legacy_defaults_and_preserves_required_sources(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); config, route = self.modern_fixture(root)
            before = copy.deepcopy(config)
            with patch.object(setup, 'safe_path', side_effect=route):
                updated, _ = setup.proposal(config, KEY, FOLDER, CREDENTIALS, modern_world_layout=True)
            self.assertEqual(updated['backups']['include'], ['world', 'plugins', 'required-other-file'])
            self.assertEqual(config, before)
            self.assertFalse((root / 'world_nether').exists())
            self.assertEqual(len(list((root / 'world' / 'dimensions' / 'minecraft').iterdir())), 3)

    def test_modern_layout_preserves_existing_legacy_data_and_permission_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); config, route = self.modern_fixture(root)
            (root / 'world_nether').mkdir(); (root / 'world_the_end').mkdir()
            (root / 'permissions.yml').write_text('fixture: true\n')
            with patch.object(setup, 'safe_path', side_effect=route):
                self.assertEqual(setup.modern_world_includes(config, KEY), config['backups']['include'])

    def test_modern_layout_requires_world_all_dimensions_and_no_symlinks(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); config, route = self.modern_fixture(root)
            with patch.object(setup, 'safe_path', side_effect=route):
                config['backups']['include'].remove('world')
                with self.assertRaisesRegex(setup.SetupError, 'WORLD_INCLUDE_REQUIRED'):
                    setup.modern_world_includes(config, KEY)
                config['backups']['include'].insert(0, 'world')
                end = root / 'world' / 'dimensions' / 'minecraft' / 'the_end'; end.rmdir()
                with self.assertRaisesRegex(setup.SetupError, 'MODERN_DIMENSION_LAYOUT_NOT_CONFIRMED'):
                    setup.modern_world_includes(config, KEY)
                end.symlink_to(root / 'world' / 'dimensions' / 'minecraft' / 'overworld')
                with self.assertRaisesRegex(setup.SetupError, 'SYMLINK_DENIED'):
                    setup.modern_world_includes(config, KEY)

    def test_apply_revalidates_include_correction_before_any_writes(self):
        updated, seed = setup.proposal(CONFIG, KEY, FOLDER, CREDENTIALS)
        updated['backups']['include'] = ['world']
        with patch.object(setup, 'host_stopped', return_value=True), patch.object(setup, 'modern_world_includes', return_value=['world', 'plugins']), patch.object(setup, 'atomic_write') as writer:
            with self.assertRaisesRegex(setup.SetupError, 'INCLUDE_CORRECTION_CHANGED_REVIEW_AGAIN'):
                setup.apply(KEY, Path('/unused'), json.dumps(CONFIG).encode(), updated, seed)
            writer.assert_not_called()

    def test_apply_refuses_running_host_before_writes(self):
        updated, seed = setup.proposal(CONFIG, KEY, FOLDER, CREDENTIALS)
        with patch.object(setup, 'host_stopped', return_value=False), patch.object(setup, 'atomic_write') as writer:
            with self.assertRaisesRegex(setup.SetupError, 'STOP_ONLY_THE_SELECTED_HOST_FIRST'):
                setup.apply(KEY, Path('/unused'), json.dumps(CONFIG).encode(), updated, seed)
            writer.assert_not_called()

    @unittest.skipUnless(os.geteuid() == 0, 'Disposable root-owned policy fixture requires root')
    def test_atomic_apply_preserves_rollback_and_never_touches_provider_or_archives(self):
        self.apply_fixture(False)

    @unittest.skipUnless(os.geteuid() == 0, 'Disposable root-owned policy fixture requires root')
    def test_failed_config_replace_restores_both_policy_and_seed(self):
        self.apply_fixture(True)

    def apply_fixture(self, fail_after_config):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp); policy = root / 'policy'; policy.mkdir(mode=0o700)
            path = policy / 'host-config.json'; original = (json.dumps(CONFIG) + '\n').encode(); path.write_bytes(original); path.chmod(0o640)
            immutable = policy / 'rclone.conf'; immutable.write_bytes(b''); immutable.chmod(0o640)
            archives = root / 'archives'; archives.mkdir(); archive = archives / 'prior.zip'; archive.write_bytes(b'untouched')
            provider = root / 'provider'
            safe_path = setup.safe_path
            def route(value):
                value = str(value)
                return provider if value == f'/var/lib/plexonpanel/instances/{KEY}/provider' else safe_path(value)
            def account(name): return SimpleNamespace(pw_uid=1001 if name.startswith('pph-') else 1002)
            updated, seed = setup.proposal(CONFIG, KEY, FOLDER, CREDENTIALS)
            with patch.object(setup, 'host_stopped', return_value=True), patch.object(setup, 'safe_path', side_effect=route), patch.object(setup.pwd, 'getpwnam', side_effect=account), patch.object(setup.grp, 'getgrnam', return_value=SimpleNamespace(gr_gid=0)):
                # /usr/bin/rclone may not exist locally; only this fixed executable readiness check is stubbed.
                with patch.object(Path, 'is_file', autospec=True, side_effect=lambda p: True if str(p) == '/usr/bin/rclone' else p.exists()), patch.object(setup.os, 'access', return_value=True):
                    real_write = setup.atomic_write
                    failed_once = False
                    def write(target, raw, gid, mode):
                        nonlocal failed_once
                        real_write(target, raw, gid, mode)
                        if fail_after_config and target == path and not failed_once:
                            failed_once = True
                            raise OSError('synthetic directory fsync failure after replace')
                    with patch.object(setup, 'atomic_write', side_effect=write):
                        if fail_after_config:
                            with self.assertRaises(OSError): setup.apply(KEY, path, original, updated, seed)
                            self.assertEqual(path.read_bytes(), original)
                            self.assertEqual(immutable.read_bytes(), b'')
                            self.assertEqual(archive.read_bytes(), b'untouched')
                            self.assertFalse(provider.exists())
                            return
                        result = setup.apply(KEY, path, original, updated, seed)
            self.assertEqual(json.loads(path.read_bytes()), updated)
            self.assertEqual(immutable.read_bytes(), seed)
            rollback = Path(result['rollbackDirectory'])
            self.assertEqual((rollback / 'host-config.json').read_bytes(), original)
            self.assertEqual((rollback / 'rclone.conf').read_bytes(), b'')
            self.assertEqual(archive.read_bytes(), b'untouched'); self.assertFalse(provider.exists())
            self.assertFalse(result['minecraftStateChanged']); self.assertFalse(result['remoteUploadExecuted'])
            self.assertEqual(path.stat().st_mode & 0o777, 0o640)
            self.assertEqual(rollback.stat().st_mode & 0o777, 0o700)

if __name__ == '__main__': unittest.main()
