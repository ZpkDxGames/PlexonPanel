#!/usr/bin/env python3
"""Local root operator setup. No service control, network calls, token output, or backup deletion."""
import argparse
import configparser
import copy
import grp
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import sys
import uuid
from urllib.parse import urlparse

KEYS = ('plexoncraft', 'tonimsmp')

class SetupError(Exception):
    pass

def require(condition, code):
    if not condition:
        raise SetupError(code)

def folder_id(value):
    if value.startswith('https://'):
        parsed = urlparse(value)
        require(parsed.netloc == 'drive.google.com' and not parsed.fragment
                and re.fullmatch(r'/drive/(?:u/\d+/)?folders/[A-Za-z0-9_-]{10,200}/?', parsed.path), 'DRIVE_FOLDER_URL_INVALID')
        value = parsed.path.rstrip('/').split('/')[-1]
    require(bool(re.fullmatch(r'[A-Za-z0-9_-]{10,200}', value)), 'DRIVE_FOLDER_ID_INVALID')
    return value

def safe_path(path):
    path = Path(path)
    require(path.is_absolute(), 'ABSOLUTE_PATH_REQUIRED')
    for part in (path, *path.parents):
        require(not part.is_symlink(), 'SYMLINK_DENIED')
    return path

def read_private(path, maximum=131072, policy=False):
    path = safe_path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size <= maximum, 'PRIVATE_FILE_INVALID')
        require(not info.st_mode & 0o007, 'PRIVATE_FILE_PUBLIC')
        if policy:
            require(info.st_uid == 0 and not info.st_mode & 0o022, 'POLICY_AUTHORITY_INVALID')
        else:
            require(not info.st_mode & 0o077, 'CREDENTIAL_FILE_NOT_PRIVATE')
        raw = stream.read(maximum + 1)
        require(len(raw) <= maximum, 'PRIVATE_FILE_TOO_LARGE')
        return raw

def read_public(path, maximum):
    path = safe_path(path)
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid == 0
                and not info.st_mode & 0o022 and info.st_size <= maximum, 'PUBLIC_POLICY_INVALID')
        raw = stream.read(maximum + 1)
        require(len(raw) <= maximum, 'PUBLIC_POLICY_INVALID')
        return raw

def validated_config(config, key):
    require(key in KEYS, 'INSTANCE_KEY_INVALID')
    require(config.get('schemaVersion') == 5 and config.get('fleet', {}).get('instanceKey') == key, 'INSTANCE_CONFIG_MISMATCH')
    server_id = config.get('serverId')
    parsed = uuid.UUID(server_id)
    require(str(parsed) == server_id and parsed.version in range(1, 6) and parsed.variant == uuid.RFC_4122, 'SERVER_ID_INVALID')
    expected = {
        'serverRoot': f'/srv/plexonpanel/servers/{key}/server',
        'dataDirectory': f'/var/lib/plexonpanel/instances/{key}',
        'accessRegistry': f'/var/lib/plexonpanel/instances/{key}/access/devices.json',
        'serviceName': f'minecraft@{key}.service',
    }
    require(all(config.get(field) == value for field, value in expected.items()), 'INSTANCE_LAYOUT_MISMATCH')
    backups = config.get('backups', {})
    require(backups.get('directory') == f'/var/backups/plexonpanel/instances/{key}'
            and backups.get('rcloneExecutable') == '/usr/bin/rclone', 'BACKUP_LAYOUT_MISMATCH')
    includes = backups.get('include')
    require(isinstance(includes, list) and 1 <= len(includes) <= 256
            and all(isinstance(item, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 _.-]{0,100}', item)
                    and '..' not in item for item in includes)
            and len(set(includes)) == len(includes), 'BACKUP_INCLUDE_INVALID')
    return server_id

def proposal(config, key, folder, credentials):
    server_id = validated_config(config, key)
    root = folder_id(folder)
    ini = configparser.ConfigParser(interpolation=None, strict=True)
    ini.read_string(credentials.decode('utf-8'))
    require(ini.sections() == ['gdrive'] and not ini.defaults(), 'ONE_GDRIVE_REMOTE_REQUIRED')
    remote = ini['gdrive']
    require(remote.get('type') == 'drive' and bool(remote.get('token', '').strip()), 'DRIVE_OAUTH_REQUIRED')
    # Only ordinary per-instance OAuth Drive remotes are supported by this setup workflow.
    require(not any(remote.get(field, '').strip() for field in ('service_account_file', 'service_account_credentials', 'team_drive', 'resource_key')), 'DRIVE_SPECIAL_CONFIGURATION_REQUIRES_MANUAL_SETUP')
    remote['root_folder_id'] = root
    import io
    out = io.StringIO(); ini.write(out)
    updated = copy.deepcopy(config)
    updated['backups'].update(enabled=True, rcloneRemote=f'gdrive:plexonpanel/{server_id}',
                              rcloneConfig=f'/etc/plexonpanel/instances/{key}/rclone.conf')
    return updated, out.getvalue().encode('utf-8')

def load_config(key):
    require(key in KEYS, 'INSTANCE_KEY_INVALID')
    path = safe_path(f'/etc/plexonpanel/instances/{key}/host-config.json')
    for directory in path.parents:
        authority = directory.stat()
        require(authority.st_uid == 0 and not authority.st_mode & 0o022,
                'POLICY_DIRECTORY_AUTHORITY_INVALID')
    raw = read_private(path, 65536, policy=True)
    config = json.loads(raw)
    validated_config(config, key)
    node = read_public('/etc/plexonpanel/node-id', 64).decode().strip()
    registry = json.loads(read_public('/etc/plexonpanel/instances.json', 131072))
    require(registry.get('schemaVersion') == 5 and registry.get('nodeId') == node
            and config.get('fleet', {}).get('nodeId') == node, 'NODE_IDENTITY_MISMATCH')
    matches = [entry for entry in registry.get('instances', []) if entry.get('instanceKey') == key]
    require(len(matches) == 1 and matches[0].get('serverId') == config['serverId'], 'REGISTRY_IDENTITY_MISMATCH')
    return path, raw, config

def host_stopped(key):
    result = subprocess.run(['/usr/bin/systemctl', 'show', f'plexonpanel-host@{key}.service',
                             '--property=ActiveState', '--property=MainPID'], capture_output=True, text=True, timeout=10)
    values = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    return result.returncode == 0 and values.get('ActiveState') in ('inactive', 'failed') and values.get('MainPID') == '0'

def atomic_write(path, raw, gid, mode):
    path = safe_path(path)
    temporary = path.with_name(f'.{path.name}.setup-{uuid.uuid4()}')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    try:
        with os.fdopen(fd, 'wb') as stream:
            os.fchown(stream.fileno(), 0, gid); os.fchmod(stream.fileno(), mode)
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
        parent_fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try: os.fsync(parent_fd)
        finally: os.close(parent_fd)
    finally:
        temporary.unlink(missing_ok=True)

def check(key):
    _, _, config = load_config(key)
    backups = config['backups']; remote = backups.get('rcloneRemote', '')
    seed = safe_path(f'/etc/plexonpanel/instances/{key}/rclone.conf')
    runtime = safe_path(f'/var/lib/plexonpanel/instances/{key}/provider')
    source = Path(config['serverRoot'])
    includes = backups.get('include', [])
    missing = sum(not (source / item).exists() for item in includes
                  if isinstance(item, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9 _.-]{0,100}', item))
    host = pwd.getpwnam(f'pph-{key}'); executable = safe_path('/usr/bin/rclone')
    seed_info = seed.stat() if seed.exists() else None
    runtime_info = runtime.stat() if runtime.exists() else None
    return {'instanceKey': key, 'serverId': config['serverId'], 'providerConfiguredOnDisk': bool(remote),
            'remoteNamespaceValid': bool(remote) and remote.rstrip('/').endswith('/' + config['serverId']),
            'immutableSeedPresent': seed.is_file(), 'privateRuntimeStatePresent': runtime.is_dir(),
            'immutableSeedPermissionsValid': seed_info is not None and seed_info.st_uid == 0
                and seed_info.st_gid == host.pw_gid and stat.S_IMODE(seed_info.st_mode) == 0o640,
            'privateRuntimePermissionsValid': runtime_info is not None and runtime_info.st_uid == host.pw_uid
                and stat.S_IMODE(runtime_info.st_mode) == 0o700,
            'hostStopped': host_stopped(key), 'missingTopLevelIncludes': missing,
            'rcloneInstalled': executable.is_file() and os.access(executable, os.X_OK),
            'backupRepositoryPresent': Path(backups['directory']).is_dir(), 'hostAccountPresent': host.pw_uid != 0}

def apply(key, path, old, updated, seed):
    require(host_stopped(key), 'STOP_ONLY_THE_SELECTED_HOST_FIRST')
    provider = safe_path(f'/var/lib/plexonpanel/instances/{key}/provider')
    require(not provider.exists(), 'EXISTING_PROVIDER_STATE_REQUIRES_OFFLINE_RESEED')
    require(not json.loads(old)['backups'].get('rcloneRemote', '').strip(), 'EXISTING_PROVIDER_REQUIRES_MANUAL_REVIEW')
    executable = safe_path('/usr/bin/rclone')
    require(executable.is_file() and os.access(executable, os.X_OK), 'INSTALL_RCLONE_FIRST')
    host = pwd.getpwnam(f'pph-{key}'); mc = pwd.getpwnam(f'mc-{key}')
    gid = grp.getgrnam(f'pph-{key}').gr_gid
    require(host.pw_uid != 0 and mc.pw_uid != 0 and host.pw_uid != mc.pw_uid, 'INSTANCE_ACCOUNTS_INVALID')
    parent = path.parent.stat()
    require(parent.st_uid == 0 and not parent.st_mode & 0o022, 'POLICY_DIRECTORY_AUTHORITY_INVALID')
    immutable = safe_path(path.parent / 'rclone.conf')
    prior = read_private(immutable, policy=True) if immutable.exists() else None
    # Private rollback copies do not overwrite or remove existing state or archives.
    rollback = path.parent / f'provider-setup-rollback-{uuid.uuid4()}'
    rollback.mkdir(mode=0o700)
    atomic_write(rollback / 'host-config.json', old, 0, 0o600)
    if prior is not None: atomic_write(rollback / 'rclone.conf', prior, 0, 0o600)
    require(read_private(path, 65536, policy=True) == old, 'HOST_CONFIG_CHANGED_REVIEW_AGAIN')
    atomic_write(immutable, seed, gid, 0o640)
    try: atomic_write(path, (json.dumps(updated, indent=2) + '\n').encode(), gid, 0o640)
    except Exception:
        if prior is not None: atomic_write(immutable, prior, gid, 0o640)
        else: immutable.unlink(missing_ok=True)
        raise
    return {'result': 'CONFIGURED_HOST_RESTART_REQUIRED', 'instanceKey': key,
            'remote': updated['backups']['rcloneRemote'], 'rollbackDirectory': str(rollback),
            'minecraftStateChanged': False, 'hostStarted': False, 'remoteUploadExecuted': False}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('check', 'plan', 'apply'))
    parser.add_argument('instance', nargs='?', choices=KEYS)
    parser.add_argument('--folder', help='Existing Drive folder URL or ID (never a credential).')
    parser.add_argument('--credential-file', help='Private mode-0600 rclone OAuth file containing only [gdrive].')
    args = parser.parse_args()
    require(os.geteuid() == 0, 'LOCAL_ROOT_REQUIRED')
    if args.operation == 'check':
        for key in ((args.instance,) if args.instance else KEYS):
            try: print(json.dumps(check(key)))
            except SetupError as failure: print(json.dumps({'instanceKey': key, 'result': str(failure)}))
            except (OSError, ValueError, TypeError, KeyError): print(json.dumps({'instanceKey': key, 'result': 'LOCAL_CONFIGURATION_CHECK_FAILED'}))
        return
    require(args.instance and args.folder and args.credential_file, 'INSTANCE_FOLDER_AND_CREDENTIAL_FILE_REQUIRED')
    path, old, config = load_config(args.instance)
    credentials = read_private(args.credential_file)
    updated, seed = proposal(config, args.instance, args.folder, credentials)
    other = KEYS[1] if args.instance == KEYS[0] else KEYS[0]
    other_seed = safe_path(f'/etc/plexonpanel/instances/{other}/rclone.conf')
    if other_seed.exists():
        ini = configparser.ConfigParser(interpolation=None); ini.read_string(read_private(other_seed, policy=True).decode())
        require(all(section.get('root_folder_id', '') != folder_id(args.folder) for section in (ini[name] for name in ini.sections())), 'DRIVE_FOLDER_ALREADY_ASSIGNED_TO_OTHER_INSTANCE')
    if args.operation == 'plan':
        print(json.dumps({'instanceKey': args.instance, 'remote': updated['backups']['rcloneRemote'],
                          'immutableSeed': updated['backups']['rcloneConfig'], 'minecraftStateChanged': False,
                          'hostRestartRequired': True, 'existingArchivesPreserved': True}))
    else: print(json.dumps(apply(args.instance, path, old, updated, seed)))

if __name__ == '__main__':
    try: main()
    except SetupError as failure:
        print(str(failure), file=sys.stderr); sys.exit(1)
    except (OSError, ValueError, TypeError, KeyError, configparser.Error, subprocess.SubprocessError):
        print('LOCAL_PROVIDER_SETUP_FAILED', file=sys.stderr); sys.exit(1)
