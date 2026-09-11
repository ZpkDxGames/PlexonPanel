# PlexonPanel 3.2.0

PlexonPanel 3.2.0 is the stable GitHub repository closure of the accepted 3.2.0-rc.3 Java source line.

## Stable boundary

- Paper plugin and Host companion are released as a matched `3.2.0` pair.
- Java 25 / class major 69.
- Paper API 26.2.
- Wire protocol remains **3** under `/v1`.
- PlexonCore remains compile-only/non-shaded; CI provisions the pinned 2.0.4 API artifact while runtime compatibility remains `>=1.0 <3.0`.
- The Dashboard/Relay provenance candidate recorded by the Java package remains `bb2c1d76f38ce9ce49aa7f3ece278cc0df2d02f2` / CI `34524470086`; this stable Java closure does not rewrite that separate product source.

## Accepted runtime-source repair

The stable line includes the accepted Panel-only PlexonChats integration correction from RC3:

- lifecycle-aware optional PlexonChats service discovery without polling;
- current `PlexonChatsAPI` / `PlexonChatEvent` contract;
- no stale peer service reference across plugin disable/re-enable;
- the active Chats GLOBAL bridge is authoritative for remote global-chat publication;
- one logical public GLOBAL chat produces at most one Panel `chat.message`;
- LOCAL, PM and cancelled public-chat paths are excluded.

No protocol, identity, pairing, immutable device-grant, remote-action authority, Host product behavior, Dashboard source or relay source redesign is part of this stable promotion.

## Repository closure hardening

- Promoted coordinated root and Paper metadata from `3.2.0-rc.3` to stable `3.2.0`.
- Generalized the x64/ARM64 Build workflow so it validates the repository version dynamically rather than hard-coding one RC.
- Replaced legacy version-specific RC/stable publishers with one exact-`main` stable release workflow.
- Stable publication requires `release/stable` to point to the exact current `main` commit and refuses prerelease versions or pre-existing tags.
- Release packaging rebuilds/tests both agents, generates the test summary, manifest and SHA-256 file, and verifies protocol 3, Java 25, required Paper classes, Core isolation and both matched JARs before publication.
- Updated current README/Core/API documentation to the stable 3.2.0/Core 2.x boundary while retaining historical acceptance/migration evidence unchanged.

## Assets

- `PlexonPanel-3.2.0.jar`
- `plexonpanel-host-3.2.0.jar`
- `PlexonPanel-3.2.0-examples.zip`
- `release-manifest.json`
- `SHA256SUMS.txt`
- `test-summary.txt`

## Deployment note

The stable GitHub release closes source, CI, merge, release and downloadable-artifact state. `runtimeCertification=NOT_EXECUTED` remains explicit in the release manifest: live PlexonCraft rollout/smoke testing is a separate operational follow-up and is not fabricated by repository closure.
