# Step 8 runtime finding: server-root enumeration ACL

During production runtime certification on PlexonCraft, `backup.preflight` continued to fail with `AccessDeniedException` after the ACL bridge had repaired all durable file ACLs.

The deployed bridge granted `plexonpanel-host` only `--x` on the server root while both `FullBackupPreflight` and `FullRestorePointManager` enumerate the server root with `Files.walkFileTree(...)`. Runtime proof showed root enumeration failed before a narrow ACL change and succeeded after granting `r-x` on `/opt/plexoncraft/server`; full durable read/traverse scans were then clean and the Host logged no new preflight error.

This change makes that contract permanent:

- server root receives `r-x` for `plexonpanel-host`, never write;
- configured include directories retain `r-x` and regular files retain `r--`;
- sensitive exclusions remain unchanged;
- regression coverage asserts the exact root ACL string;
- operator documentation requires root enumeration to succeed.

Runtime certification remains partial until the permanent bridge is deployed, the standalone relay is updated to the matched Dashboard candidate, recovery is resolved through the supported action, and a new full cold backup passes all remaining Step 8 gates.
