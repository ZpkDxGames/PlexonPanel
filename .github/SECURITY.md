# Security policy

Remote mutations are disabled by default. Valid current scoped grants and local capabilities are checked by relay and agent; Owner cannot override local policy. Op/deop and restore require the built-in Owner role. There is no generic shell, arbitrary unit/executable, RCON or database execution. Durable audit intent precedes privileged work.

Report vulnerabilities privately through GitHub security advisories if enabled, otherwise an established private channel to the repository owner. Never post real credentials, keys, codes, player data, raw logs or file bodies.

Tests cover signatures/replay, capabilities/scopes, revocation, bounded inputs/transfers, stale writes, symlink/traversal protection and ZIP/recovery behavior. Live OS/cloud/plugin-stack security still requires [acceptance](../docs/VALIDATION.md). For an incident disable mutations locally, revoke affected devices, preserve local evidence and coordinate any signing-key rotation. Deleting identity files is not a normal repair.
