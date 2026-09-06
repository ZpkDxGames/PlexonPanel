# rc.2 migration and rollback

Protocol 3 rejects unscoped rc.2 browser credentials. They cannot be silently promoted to Owner. The Paper UUID and Ed25519 identity are preserved.

1. Schedule maintenance. Privately back up the old JAR, configuration, identity and pairing state through local administration.
2. Review existing remote-action, stream, privacy and command rules. Existing values are preserved; new defaults do not retroactively disable previously enabled settings. Compare/add the new access/files/host/backup sections deliberately.
3. Upgrade the relay with its existing signing secrets, namespace, migrations and identity pins intact, then the matching dashboard. Do not reset Durable Object storage to work around migration errors.
4. Stop Paper, install the v2 JAR and start on Java 25/Paper 26.2. The protocol marker invalidates old unscoped pairing state and creates `access/devices.json` while preserving identity.
5. Check the UUID/fingerprint, then re-pair every browser locally with a chosen role. Start with Observer and test denial/revocation. Old browser storage is discarded during v3 pairing/forget.
6. Install the independently pinned optional host separately and complete live acceptance before enabling mutations.

Rollback is coordinated across relay/dashboard/agents with privately saved configuration and recorded baseline commits. Keep Paper stopped, resolve restore journals first and reinstall known-good components together. Do not reuse v3 grants with rc.2 or assume old relay code enforces v3 revocation. Re-pair after rollback. Deleting identity files is never a routine migration step.
