# PlexonPanel 3.2.0-rc.1 — historical candidate

> **Superseded.** This file is retained as historical evidence for the immutable `v3.2.0-rc.1` prerelease. Do not use RC1 as the matching Phase 2 deployment candidate. The active source/release boundary is `docs/release-3.2.0-rc.2.md`.

RC1 established the compatible Protocol 3 control-plane candidate from stable 3.1.1, including Paper/Host reconnect hardening, diagnostics and the standalone-relay deployment path. Its published Java tag/release remains immutable at `ccac7e4b3ae15f876d5653fbeda462cea1dbcdf1`.

The original RC1 coupling referenced dashboard commit `ece21970f5dbea71bb79951b6551e7b798d9fa8b`. Subsequent coupled dashboard certification discovered HIGH/CRITICAL dependency advisories. Those findings were remediated on the dashboard RC2 line, so RC1 is not the final coupled Phase 2 boundary.

Protocol remains **3**. Stable `3.2.0` remains unpublished. PlexonCraft runtime certification remains **NOT EXECUTED**.

## Historical rollback

- PlexonPanel stable rollback: `v3.1.1`, commit `e0984b625d692de6076afa7e20c4fe4b35f07e9a`.
- Dashboard source rollback: `main`, commit `03777c7dc108b54dda625c7f56f5e723ca35124f`, plus the deployed Worker relay rollback path.

For current candidate deployment, provenance and certification instructions, use `docs/release-3.2.0-rc.2.md`.
