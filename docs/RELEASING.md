# Releasing

## Verification

1. Update `version` in the root `build.gradle.kts` and the changelog.
2. Run `./gradlew clean test javadoc :agent:jar releaseBundle` with Java 25.
3. Install the JAR on a disposable Paper 26.2 server and verify enable, status, reload, pairing, reconnect, and disabled-by-default remote actions.
4. Inspect the JAR for `plugin.yml`, configuration defaults, bundled Gson classes, and the absence of the optional PlexonChats API contract.
5. Record SHA-256 checksums for the JAR and release ZIP.

## GitHub

Create a signed `vX.Y.Z` tag and GitHub release. Attach the JAR, release ZIP, and checksum file. Copy only the matching changelog section into the release notes.

## Marketplaces

SpigotMC and Modrinth pages should state the exact Paper/Java versions, hosted-service requirement, data categories, default-disabled remote capabilities, support URL, source/license links, and checksum. Do not advertise Folia, Spigot, or older Paper support until each has its own test matrix.
