# Step 8 runtime finding: full-backup include boundary

Real PlexonCraft 3.5.0 acceptance reached Host `backup.preflight` with all three services active, the final backup storage writable, the current backup-read bridge installed, server-root enumeration working and the RCON password matching. Preflight still failed with `AccessDeniedException`.

The production evidence exposed a source-policy mismatch:

- the root-owned backup-read bridge correctly grants the non-root Host access only to the configured top-level `backups.include` entries;
- full-backup preflight and cold archive creation incorrectly walked the entire `serverRoot` and could therefore touch an unrelated path outside that allowlist.

The remediation applies one shared source walker to preflight sizing, the stopped-server verification scan and ZIP creation. It:

- traverses only the exact configured top-level includes;
- retains relative server-root paths inside the archive;
- keeps mandatory noise/private exclusions and symlink rejection;
- fails closed for an empty or duplicate active include list and for a missing configured source;
- never requires broadening the Host ACL to unrelated server files.

The prior candidate must not be certified or tagged. Build a fresh matched Paper/Host pair, deploy the replacement Host JAR without changing identity/configuration, resolve the preserved maintenance recovery gate only after systemd plus RCON readiness pass, and rerun preflight and the complete cold-backup/Google Drive/restart acceptance flow.
