# Bedrock integration source audit

The two draft heads inspected before edits were backend `3f1c9460b9fe3ec1fd8ea3aba3f2caa88f56baac` and Dashboard `34ed02f4d14d832db7847056720e499741f13e68`, both on `development/bedrock-host-integration-v1`. The backend draft provided a security design. The Dashboard draft provided edition-specific fleet card selection and a Docker resource freshness parser. [Backend draft](https://github.com/ZpkDxGames/PlexonPanel/pull/104) · [Dashboard draft](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/pull/95)

| Source | Authority and compatibility seam |
| --- | --- |
| Backend `protocol/.../protocol/ProtocolCodec.java`, `protocol/.../identity/FleetContract.java`, `FleetIdentity.java` and `NodeInstanceRegistry.java` | Protocol 3 signs envelope content. Fleet association binds server UUID, node UUID and immutable instance key. Paper and Host schema 5 are separate from the Bedrock admission contract. |
| Backend `agent/.../PlexonPanelPlugin.java`, `host-agent/.../HostConnection.java` and `HostAuthorizationMirror.java` | Paper establishes the legacy room and approves device grants. Host verifies the relay and mirrors Paper's authorized generation and revision. A Linux Host does not acquire admission or pairing authority from its Java code alone. |
| Backend `host-agent/.../SystemdService.java`, `ServiceResourceSampler.java`, `HostConsoleStreamService.java` and `HostConsoleHistory.java` | Exact-instance systemd service control and cgroup/journal sampling have Paper deployment assumptions. Bedrock reads come from a separate locally mediated Docker source. |
| Backend `host-agent/.../MaintenanceManager.java`, `FullRestorePointManager.java` and `MinecraftCommandChannel.java` | Existing full backups depend on Paper save/flush, readiness and service proofs. Use of the Bedrock LevelDB world requires independently verified stop, archive and recovery behavior. |
| Dashboard `src/adapter/session.ts`, `fleet-feed.ts`, `control-state.ts` and `scopes.ts` | A bound grant and authenticated server session precede `dashboard.ready` and all events. Legacy Paper/Host source checks and action IDs remain. Host-only state has an explicit edition/runtime/admission marker and its own event set. |
| Dashboard `src/features/capabilities.ts`, `src/ui/bindings/index.ts`, `src/ui/routes/Fleet.tsx`, `Console.tsx` and `docs/V60_BINDINGS.md` | Existing review/authorization hooks are bound to a device and selected Paper/Host session. The read-only Bedrock route has independent status and console inputs. Paper's TPS, JVM heap, chat, Bukkit plugins and player UUID views remain Paper sources. |

## Relay provenance

The current Dashboard head and `main` contain the browser relay adapter, and the backend `mock-gateway/` explicitly retires its earlier protocol simulator. The exact standalone 5.0.0 source is in Dashboard history at `da691360b39435d02a8671f45f2a2c4c49e3adbe`, the commit pinned by the backend 5.0.0 release gate. The Command Center commit `cffbd5bfe5ee296c0c83ff4cd1dd08daa15af4ca` removed the `relay/` tree from the Dashboard head. [Standalone source](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/tree/da691360b39435d02a8671f45f2a2c4c49e3adbe/relay) · [5.0.0 gate](release-gates-5.0.0.json) · [Removal](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/commit/cffbd5bfe5ee296c0c83ff4cd1dd08daa15af4ca)

The Dashboard draft restores that Node standalone implementation separately from the Next.js runtime, with its original legacy SQLite coordination and signed Paper/Host routes. The authenticated Bedrock room is serviced by the standalone variant. The Worker release path has its own historical source and deployment gate. The identities and configuration of the running VPS remain outside the source audit. [Standalone source and build instructions](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/tree/development/bedrock-host-integration-v1/relay)

## Version policy and baseline checks

The backend root version, plugin descriptor, 6.0.0 release gate, artifact names and published tag agree on 6.0.0. `ReleaseVersionConsistencyTest` still expected 5.0.0 and compared against its historical gate. The 6.0.0 tree predates the Bedrock draft. The repair checks the gate of the version declared in Gradle, including current protocol, fleet digest and schema versions, then checks historical gate versions independently. The historical evidence files and release version retain their existing content. [6.0.0 gate](release-gates-6.0.0.json) · [Published 6.0.0 release](https://github.com/ZpkDxGames/PlexonPanel/releases/tag/v6.0.0)

| Initial CI status | Evidence |
| --- | --- |
| Backend PR x64 failed in `:protocol:test`, 90 protocol tests run with one failure in `ReleaseVersionConsistencyTest`. | [x64 job](https://github.com/ZpkDxGames/PlexonPanel/actions/runs/44961249409/job/137020321281) |
| Backend PR ARM64 failed in the same test, 90 run with one failure. | [ARM64 job](https://github.com/ZpkDxGames/PlexonPanel/actions/runs/44961249409/job/137020321561) |
| Dashboard verify passed on the draft PR and branch push. | [PR run](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/actions/runs/44961252751) · [Branch run](https://github.com/ZpkDxGames/PlexonPanel-Dashboard/actions/runs/44961219059) |

Local reproduction used the verified Gradle 9.7.0 distribution, Temurin 25 and the pinned PlexonCore 2.0.4 artifact. Under the runner's standard `0022` umask, 335 backend tests reproduced the single release-version failure. No running VPS component or production configuration was accessed for these checks.
