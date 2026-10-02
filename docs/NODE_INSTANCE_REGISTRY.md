# Node instance registration

Schema 5 uses `/etc/plexonpanel/instances.json`, a public root-owned allocation registry. It contains only schema version, node UUID and per-instance server UUID, immutable key, Minecraft port and optional RCON port. It contains no gateway settings, keys, passwords, pairing grants, environment values or private configuration. The shared `/etc/plexonpanel/node-id` must agree with the registry.

## Allocation rules

Every key and server UUID is unique on the node. Ports are unique across all Minecraft and RCON listeners registered on that node, including a Minecraft port conflicting with another instance's RCON port. Ports must be 1–65535. Re-registering identical values is idempotent; rebinding an existing key, changing its UUID/ports or substituting the node identity is denied. A deliberate port change requires a separately reviewed offline allocation update; this CLI does not silently replace an existing allocation.

Registration uses a stable root-owned `instances.json.lock` inode and an OS file lock. Competing administrator processes cannot both allocate the same port. The registry is fsynced and replaced atomically with mode 0644. The lock is never deleted during ordinary operation. Root ownership and absence of writable/symlink ancestors are checked before runtime reads and default-path registration. Host/Minecraft accounts can read public allocations but cannot change them.

## CLI

After a canonical schema-5 Host configuration has been planned, validated and explicitly published:

```sh
sudo /usr/bin/java -jar /opt/plexonpanel/releases/5.0.0/plexonpanel-host-5.0.0.jar --register-fleet plexoncraft 25565
sudo -u pph-plexoncraft /usr/bin/java -jar /opt/plexonpanel/releases/5.0.0/plexonpanel-host-5.0.0.jar --validate-fleet plexoncraft
```

These are future deployment commands, not evidence that a 5.0 artifact has been released or executed on the VPS. Registration requires the local Linux administrator and derives the server/node/key and RCON port from the canonical private Host configuration. It prints only the resulting public allocation. Validation does not modify files and verifies the registered Host binding and RCON port. Neither command dumps private configuration or forwards browser data.

Confirm actual `server.properties`, RCON settings, listener availability and private-file policy in the finalized deployment validator before starting services. The registry detects allocation conflicts; it does not claim to reserve kernel ports or prove another unrelated process has not bound a port.

## Startup and identity

Host schema 5 requires the registered immutable fleet binding and exact RCON port before creating private identity state. Paper schema 5 requires its canonical account/path, node, registry entry and configured Minecraft port before creating its private identity. A fresh Paper key pair uses the registered server UUID. An existing Paper identity with another UUID is rejected without rewriting its metadata/key. Existing matching Paper UUIDs and keys remain intact, and ordinary renames do not modify allocation or history authority.

For the operator's approved fresh installation, choose a new server UUID in the new private Host configuration, register it, then initialize Paper at that registered UUID through schema-5 startup. Keep the former installation and private identities unchanged for rollback. A cloned directory with a copied identity must go through explicit offline rekey/archive handling; copying another server's private identity into an allocation is not supported.

## Evidence limits

Local tests execute registry publication, idempotence, immutable binding denial, cross-role port conflicts, malformed schemas, unsafe lock/symlink handling and two competing JVM registrations. Paper compilation/startup source checks run in CI. Actual VPS registry ownership, listeners, two-server startup, private configuration boundaries and migration/rollback certification remain NOT_EXECUTED.
