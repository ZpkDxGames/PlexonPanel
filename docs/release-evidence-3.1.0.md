# PlexonPanel 3.1.0 release evidence

Date: 2026-09-08

## Release-owner live acceptance

The exact `v3.1.0-rc.1` Paper and Host artifacts were deployed to the live PlexonCraft server environment for acceptance testing. The release owner reported that the PlexonPanel update was working perfectly and explicitly authorized promotion to stable `v3.1.0`.

This operator sign-off is the live acceptance record for the 3.1.0 release matrix. It does not replace automated evidence where a gate is machine-verifiable.

## Automated evidence

- PlexonPanel hardened PR/build run `34188277382` passed on Ubuntu 24.04 x64 and ARM64 with Java 25, including tests, checks, Javadocs, Paper/Host JAR builds, release packaging and distribution-contract checks.
- Dashboard/Relay baseline run `34185274648` passed its install, checks, relay smoke test and production build while remaining on the reviewed 3.0.2 / protocol 3 runtime used by the 3.1.0 RC.
- `v3.1.0-rc.1` was published from the reviewed implementation and contained the five required release artifacts.

## Promoted artifact identity

Stable promotion must reuse the exact tested RC artifacts. The two executable JAR checksums are:

```text
520edb48f26172b3154d1fc14516969cc560965063d45385f9f1d5cf2fe9dc56  PlexonPanel-3.1.0.jar
821fd1af0e3d3cf0f4f3f0d13ee316e276f4b5a0e147211d65c026540b1d4a4d  plexonpanel-host-3.1.0.jar
```

The stable publisher verifies these hashes before creating the final release.
