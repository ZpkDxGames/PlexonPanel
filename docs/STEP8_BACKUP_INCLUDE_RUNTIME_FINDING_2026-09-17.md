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

The bounded-walker candidate then correctly exposed two deployment defects: the active allowlist named nonexistent Nether and End roots, and Google Drive token refresh could not atomically rewrite an rclone config stored beside root-owned Host policy. Follow-up hardening gives each preflight gate a sanitized typed failure and moves the documented OAuth config location to an isolated Host-owned directory under `/var/lib/plexonpanel-host`. Neither remediation broadens Host authority over the live server tree or root-owned policy.

After those deployment defects were corrected, production preflight identified one remaining traversal edge: Java can report an access failure while opening an excluded directory before `preVisitDirectory` has an opportunity to prune it. The live read contract deliberately withholds Host access from `plugins/PlexonPanel/audit`, `plugins/PlexonPanel/identity` and `plugins/spark/tmp`. The shared walker now treats a failed visit as prunable only when the failed relative path already matches the validated exclusion policy. Access failures anywhere else still fail closed as `BACKUP_SOURCE_UNREADABLE`; no ACL is granted to identity keys, pairing state, audit data or profiler temporary data.
