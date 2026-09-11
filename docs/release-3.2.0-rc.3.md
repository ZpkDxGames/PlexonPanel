# PlexonPanel 3.2.0-rc.3

PlexonPanel 3.2.0-rc.3 is the Phase 3 runtime-reliability candidate for the accepted Panel ↔ PlexonChats compatibility repair. It supersedes RC2 only for runtime certification of this repaired Paper-side integration; `v3.2.0-rc.2` remains immutable historical evidence at its original target.

## Exact engineering boundary

- Frozen Phase 2 base / historical RC2 target: `7dc266478d9af23963a982b219bda1fbfe75f0a9`.
- Accepted Panel ↔ Chats integration source: `c05d23bbea3786b192c329d49e516d50d143929c`.
- PlexonChats accepted audit head: `48a32861892a5d28445b780ee234d7fc0e732d0c` — unchanged/read-only.
- Dashboard/relay candidate: `bb2c1d76f38ce9ce49aa7f3ece278cc0df2d02f2` — unchanged.
- Dashboard CI: `34524470086` — unchanged.
- Wire protocol: `3` — unchanged.
- Java: `25`, class major `69`.
- PlexonCore: `2.0.4`, compile-only/non-shaded.

## Integration correction

RC3 carries the accepted Panel-only repair for the current PlexonChats API/event surface. Panel now uses `PlexonChatsAPI` and `PlexonChatEvent`, performs lifecycle-aware optional service discovery without polling, avoids stale peer references across disable/re-enable, and treats the active Chats GLOBAL bridge as authoritative so one logical public global chat produces at most one Panel `chat.message`. LOCAL, PM and cancelled public-chat paths remain excluded from the remote global stream.

No PlexonChats source, Dashboard source, Host product source, relay, protocol, pairing, device identity/grants or remote-action policy change belongs to RC3.

## Release certification

The exact final RC3 candidate must independently pass the complete x64 and ARM64 repository matrix after version preparation, including tests/check/Javadocs, Paper and Host JARs, Java 25/class major 69, protocol 3, PlexonCore dependency isolation, checksums, provenance and release-contract validation. The release workflow also proves the frozen Phase 2 base remains the merge base and the accepted integration commit is an ancestor of the final RC3 SHA.

The published release must be verified by downloading all six normal release assets, comparing them byte-for-byte with the certified local outputs, and rechecking `SHA256SUMS.txt`.

## Rollback and runtime boundary

- PlexonPanel stable rollback: `v3.1.1` / `e0984b625d692de6076afa7e20c4fe4b35f07e9a`.
- Historical Phase 2 RC2 remains `v3.2.0-rc.2` at `7dc266478d9af23963a982b219bda1fbfe75f0a9`.
- Preserve paired identity/device state unless intentional revocation is required.

RC3 publication certifies SOURCE / CI / RELEASE only. `runtimeCertification=NOT_EXECUTED` remains mandatory. Stable `v3.2.0` stays unpublished until PlexonCraft runtime certification verifies the repaired lifecycle, single-delivery chat semantics, relay reconnect/restart behavior, Core STARTING → HEALTHY and protocol 3 with zero release-blocking runtime defects.
