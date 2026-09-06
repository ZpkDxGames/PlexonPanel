# PlexonPanel 2.0 implementation

Baselines: Paper `2e3bbf34c486cbc595b1d6b503281f4fc0c72f19`; dashboard `d1583af58017ee2aad2538a0ae5cec74f97e71b3`.

1. Preserve the outbound Ed25519 transport, persistent UUID/key, bounded streams, local command policy, PlexonChats integration and database-less relay.
2. Extract a shared Java protocol/security/files/audit module. Introduce explicit protocol 3, scoped device grants issued locally, replay/rate limits, capability negotiation and local revocation.
3. Expand Paper telemetry and typed controls, local pairing commands and inventory GUI. Keep all sensitive capabilities disabled unless configured locally.
4. Add a restricted path policy and atomic conflict-checked text operations. Deny secrets, identities, symlinks and active binaries. Keep file contents out of relay storage and audits.
5. Add the optional non-root systemd companion, fixed service operations, local/rclone backups, retention and stopped-server restore with emergency snapshot/rollback.
6. Integrate the responsive Next.js control room, scoped actions, actual completion results, file conflict/confirmation flows, audit and access views.
7. Verify subsystem tests, builds and cross-language protocol fixtures; update migration/deployment/privacy documentation and CI.
8. Push reviewable 2.0 branches. Merge, tag, release and deploy only after the specified end-to-end and ARM64 acceptance gates pass.

## Verification

Implementation and local checks are complete. See VALIDATION.md for exact results and pending live gates.

## Release gate

The attached specification requires a disposable Paper 26.2 integration run, production-like ARM64 verification, rclone validation, live Vercel/Cloudflare validation and a 30-minute stability run. Keep evidence and unverified requirements explicit. Do not publish production artifacts as validated before those gates pass.
