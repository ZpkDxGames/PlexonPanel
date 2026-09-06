# Development

Use Java 25 and Gradle wrapper 9.7.0. `protocol` contains pure Java identity/security/files/audit/metrics, `agent` integrates Paper, `host-agent` provides the non-root OS adapter, and `integrations/plexonchats-api` is an optional compile-only event API.

Run `./gradlew --no-daemon test javadoc :agent:jar :host-agent:jar`, then `python3 scripts/package-release.py`. Paper API is pinned to 26.2.build.112-stable. Gson 2.14.0 and its license are bundled in both JARs; no native dependency is required.

In the dashboard repository use Node 24, `npm ci`, `npm run check`, and `npm run relay:smoke`. Smoke uses actual local workerd with temporary keys and no account credentials. The historical rc.2 mock gateway intentionally refuses protocol 3 operation.

Keep Java and both TypeScript scope/action copies aligned. Preserve the identical public-only wire fixture. Do not remove server-side authorization to resolve a UI denial. Bukkit capture stays on-thread; I/O/compression/process/network work uses bounded workers. No shell or arbitrary units/paths/executables.

Never commit runtime identities, grants, generated tokens, backups or real service credentials. Record non-secret live evidence in VALIDATION.md and release-gates.json before release.
