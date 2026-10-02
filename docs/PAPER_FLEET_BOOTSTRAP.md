# Paper schema-5 bootstrap

For a schema-5 configuration or a 5.x plugin, startup validates the canonical working directory, exact `mc-<instanceKey>` account, public node identity, root-controlled instance registry and registered Minecraft port before saving configuration or generating identity keys. The plugin data directory must be exactly `server/plugins/PlexonPanel` within that instance. Symlinked paths are rejected.

Fresh keys use the registry's server UUID. Existing metadata with a different UUID is rejected before configuration migration; keys, grants and permissions are preserved. A copied installation therefore requires the intentional offline clone/rekey procedure. Display names do not choose accounts, paths, units or authorization.

After public preflight, the corrected legacy migration writes its separate schema-4 baseline and retains `config.yml.pre-v4-backup`. Fleet migration then atomically publishes schema 5 and retains `config.yml.pre-v5-backup`, using the registered node/key and preserving the current display name, operator fields and gateway policy. The legacy step remains fixed at schema 4 even when bundled defaults become schema 5. Restart is idempotent; binding changes require offline handling.

Runtime remains 4.0.0 until coordinated version activation. Legacy schema-4 startup retains its current path behavior during this source groundwork. Do not replace the retained 4.0 installation or the deployed VPS relay 3.5 in isolation.

Executed tests cover public preflight rejection, pinned fresh identity, copied-identity preservation, and attached schema-5 defaults followed by both migrations. Paper compilation and migration tests execute in CI on x64/ARM64. Actual Paper startup, two-server migration, rollback and VPS security checks remain NOT_EXECUTED.
