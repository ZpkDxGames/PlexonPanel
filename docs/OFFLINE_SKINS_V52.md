# v5.2 applied skin metadata

Player inventory snapshots and `players.presence` events now carry nullable `skinTexture`. Server snapshots carry nullable `onlineMode`; older Java constructor signatures remain available. Protocol version stays 3, and both artifact names retain version 5.0.0.

`PaperSnapshotCollector.extractSkinTexture` reads the currently applied Paper profile, including textures applied by SkinsRestorer. It decodes a bounded Base64 JSON property and accepts only an HTTP(S) `textures.minecraft.net/texture/` URL with a 56–64 character hexadecimal hash. No arbitrary URL is returned to the dashboard.

Join/leave handlers capture the hash on the Bukkit event thread. The presence worker receives only immutable text, never a Bukkit Player. The durable presence journal format is unchanged. A texture applied after join appears on the next player inventory capture.

The optional reflective SkinsRestorer storage lookup is deliberately omitted: this release uses the profile actually applied to the player and performs no skin storage or network work on the main thread. No hard SkinsRestorer dependency is added.

The dashboard prioritizes the texture hash, uses names for offline version 3 UUIDs, and advances through provider and same-origin proxy fallbacks before showing initials. Proxy inputs, response size, PNG signature, redirects and request duration are bounded.

Regression coverage: `SkinTexturesTest` exercises trusted-host parsing, malformed/oversize properties, compatibility constructors and metadata JSON. The existing Java 25 workflow builds the Paper/Host pair on Linux x64 and arm64.
