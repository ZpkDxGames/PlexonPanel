from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    file = Path(path)
    text = file.read_text()
    if old not in text:
        raise SystemExit(f"anchor not found in {path}: {old[:100]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"anchor is not unique in {path}: {text.count(old)} matches")
    file.write_text(text.replace(old, new, 1))


def replace_count(path: str, old: str, new: str, count: int) -> None:
    file = Path(path)
    text = file.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f"unexpected anchor count in {path}: expected {count}, got {actual}")
    file.write_text(text.replace(old, new))


# DeviceRegistry: keep Paper's shared registry behavior, add a private non-mutating Host mirror mode,
# and provide guarded authoritative snapshot replacement.
registry = "protocol/src/main/java/io/github/zpkdxgames/plexonpanel/security/DeviceRegistry.java"
replace_once(
    registry,
    '  private static final String SHARED_FILE_MODE = "rw-rw----";\n',
    '  private static final String SHARED_FILE_MODE = "rw-rw----";\n'
    '  private static final String PRIVATE_FILE_MODE = "rw-------";\n',
)
replace_once(
    registry,
    '  private final Clock clock;\n  private final Gson gson = new Gson();\n',
    '  private final Clock clock;\n'
    '  private final String fileMode;\n'
    '  private final boolean trackLastSeen;\n'
    '  private final long initialGeneration;\n'
    '  private final Gson gson = new Gson();\n',
)
replace_once(
    registry,
    '''  public DeviceRegistry(Path path, String serverId) throws IOException {
    this(path, serverId, Clock.systemUTC());
  }

  public DeviceRegistry(Path path, String serverId, Clock clock) throws IOException {
    this.path = path.toAbsolutePath().normalize();
    this.serverId = UUID.fromString(serverId).toString();
    this.clock = clock;
    Files.createDirectories(this.path.getParent());
    if (Files.isSymbolicLink(path)) throw new IOException("Access registry must not be a symlink");
    locked(state -> state);
  }
''',
    '''  public DeviceRegistry(Path path, String serverId) throws IOException {
    this(path, serverId, Clock.systemUTC());
  }

  public DeviceRegistry(Path path, String serverId, Clock clock) throws IOException {
    this(path, serverId, clock, SHARED_FILE_MODE, true, Math.max(1, clock.millis()));
  }

  /** Host-owned mirror: private files, no local last-seen revision churn, deterministic bootstrap marker. */
  public static DeviceRegistry hostMirror(Path path, String serverId) throws IOException {
    return new DeviceRegistry(path, serverId, Clock.systemUTC(), PRIVATE_FILE_MODE, false, 1L);
  }

  private DeviceRegistry(
      Path path,
      String serverId,
      Clock clock,
      String fileMode,
      boolean trackLastSeen,
      long initialGeneration)
      throws IOException {
    this.path = path.toAbsolutePath().normalize();
    this.serverId = UUID.fromString(serverId).toString();
    this.clock = clock;
    this.fileMode = fileMode;
    this.trackLastSeen = trackLastSeen;
    this.initialGeneration = initialGeneration;
    Files.createDirectories(this.path.getParent());
    if (Files.isSymbolicLink(path)) throw new IOException("Access registry must not be a symlink");
    locked(state -> state);
  }
''',
)
replace_once(registry, '    seen(deviceId);\n    return device;\n', '    if (trackLastSeen) seen(deviceId);\n    return device;\n')
replace_once(
    registry,
    '''  public synchronized State snapshot() throws IOException {
    return locked(s -> s);
  }
''',
    '''  /**
   * Replace this registry from an already authenticated authoritative snapshot. The normal Host
   * path refuses generation/revision rollback. Bootstrap is allowed only before a Host mirror has
   * ever received a Paper-authoritative state.
   */
  public synchronized State replaceAuthoritative(State incoming, boolean bootstrap) throws IOException {
    validateIncoming(incoming);
    return locked(
        current -> {
          if (!bootstrap) {
            if (incoming.generation < current.generation)
              throw new SecurityException("ACCESS_SNAPSHOT_STALE_GENERATION");
            if (incoming.generation == current.generation && incoming.revision < current.revision)
              throw new SecurityException("ACCESS_SNAPSHOT_STALE_REVISION");
            if (incoming.generation == current.generation
                && incoming.revision == current.revision
                && !incoming.equals(current))
              throw new SecurityException("ACCESS_SNAPSHOT_REVISION_CONFLICT");
          }
          return incoming.equals(current) ? current : incoming;
        });
  }

  public synchronized State snapshot() throws IOException {
    return locked(s -> s);
  }
''',
)
replace_once(
    registry,
    '      } else previous = new State(3, serverId, Math.max(1, clock.millis()), 1, List.of());\n',
    '      } else previous = new State(3, serverId, initialGeneration, 1, List.of());\n',
)
replace_once(
    registry,
    '''        AtomicFiles.writeUtf8(path, gson.toJson(next));
        enforceSharedFileMode(path);
        enforceSharedFileMode(lock);
''',
    '''        AtomicFiles.writeUtf8(path, gson.toJson(next));
        enforceFileMode(path, fileMode);
        enforceFileMode(lock, fileMode);
''',
)
replace_once(
    registry,
    '''  /**
   * Paper and Host intentionally access the same registry as different Linux users. A shared file
   * may therefore already have the required 0660 mode while the current process is not its owner.
   * Linux permits group read/write in that case but rejects chmod with EPERM. Accept that ownership
   * hand-off only when the existing POSIX mode is already exactly the required private shared mode.
   */
  private static void enforceSharedFileMode(Path target) throws IOException {
    try {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(SHARED_FILE_MODE));
    } catch (UnsupportedOperationException ignoredPermissions) {
      /* Non-POSIX development platform. */
    } catch (IOException permissionError) {
      try {
        String actual =
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
        if (!SHARED_FILE_MODE.equals(actual)) throw permissionError;
      } catch (UnsupportedOperationException ignoredPermissions) {
        throw permissionError;
      } catch (IOException verificationError) {
        permissionError.addSuppressed(verificationError);
        throw permissionError;
      }
    }
  }
''',
    '''  private void validateIncoming(State state) throws IOException {
    if (state == null
        || state.protocolVersion != 3
        || !serverId.equals(state.serverId)
        || state.devices == null
        || state.devices.size() > 64
        || state.generation < 1
        || state.revision < 1)
      throw new IOException("Invalid authoritative access snapshot");
    try {
      for (Device device : state.devices) {
        UUID.fromString(device.deviceId);
        Scopes.validate(device.scopes);
        if (device.name == null
            || !device.name.matches("[\\\\p{L}\\\\p{N} ._()-]{1,64}")
            || device.role == null
            || !device.role.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")
            || device.expiresAt <= device.issuedAt
            || device.expiresAt - device.issuedAt > 2592000)
          throw new IOException("Invalid authoritative device grant");
      }
    } catch (IllegalArgumentException invalid) {
      throw new IOException("Invalid authoritative device grant", invalid);
    }
  }

  /**
   * Paper's registry remains group-shared at 0660. Host-owned mirrors use 0600. If a shared file
   * already has the exact required mode but this process is not its owner, accept that safe mode
   * rather than failing only because chmod returned EPERM.
   */
  private static void enforceFileMode(Path target, String requiredMode) throws IOException {
    try {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(requiredMode));
    } catch (UnsupportedOperationException ignoredPermissions) {
      /* Non-POSIX development platform. */
    } catch (IOException permissionError) {
      try {
        String actual =
            PosixFilePermissions.toString(
                Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS));
        if (!requiredMode.equals(actual)) throw permissionError;
      } catch (UnsupportedOperationException ignoredPermissions) {
        throw permissionError;
      } catch (IOException verificationError) {
        permissionError.addSuppressed(verificationError);
        throw permissionError;
      }
    }
  }
''',
)

# Host-owned durable mirror wrapper.
host_mirror = Path("host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostAuthorizationMirror.java")
host_mirror.write_text(r'''package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable Host-local copy of Paper's authoritative device authorization state. */
public final class HostAuthorizationMirror {
  private static final long MAX_REGISTRY_BYTES = 262144L;
  private final Path directory;
  private final Path registryPath;
  private final String serverId;
  private final DeviceRegistry registry;
  private final Gson gson = new Gson();
  private volatile boolean initialized;
  private volatile String source;
  private volatile Instant lastSyncAt;
  private volatile String lastRejection = "";

  public HostAuthorizationMirror(Path directory, String serverId) throws IOException {
    this.directory = directory.toAbsolutePath().normalize();
    this.registryPath = this.directory.resolve("devices.json");
    this.serverId = java.util.UUID.fromString(serverId).toString();
    Files.createDirectories(this.directory);
    enforceDirectoryMode(this.directory);
    this.registry = DeviceRegistry.hostMirror(this.registryPath, this.serverId);
    DeviceRegistry.State state = registry.snapshot();
    this.initialized = !isBootstrapSentinel(state);
    this.source = initialized ? "HOST_MIRROR" : "WAITING_FOR_PAPER";
    if (initialized && Files.exists(registryPath))
      this.lastSyncAt = Files.getLastModifiedTime(registryPath, LinkOption.NOFOLLOW_LINKS).toInstant();
  }

  public DeviceRegistry registry() {
    return registry;
  }

  /**
   * One-time migration path from the former Paper-owned shared registry. Once a valid Host mirror
   * exists this method deliberately returns before touching the legacy plugin path.
   */
  public synchronized boolean bootstrapLegacy(Path legacyRegistry) throws IOException {
    if (initialized || legacyRegistry == null) return false;
    Path legacy = legacyRegistry.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(legacy) || !Files.isRegularFile(legacy, LinkOption.NOFOLLOW_LINKS))
      return false;
    if (Files.size(legacy) > MAX_REGISTRY_BYTES) throw new IOException("Legacy access registry exceeds limit");
    DeviceRegistry.State state;
    try {
      state = gson.fromJson(Files.readString(legacy), DeviceRegistry.State.class);
    } catch (RuntimeException invalid) {
      lastRejection = "LEGACY_REGISTRY_MALFORMED";
      throw new IOException("Invalid legacy access registry", invalid);
    }
    applyState(state, true, "LEGACY_BOOTSTRAP");
    return true;
  }

  public synchronized void apply(JsonObject body) throws IOException {
    DeviceRegistry.State incoming;
    try {
      incoming = gson.fromJson(body, DeviceRegistry.State.class);
    } catch (RuntimeException invalid) {
      lastRejection = "ACCESS_SNAPSHOT_MALFORMED";
      throw new IOException("Invalid access authority snapshot", invalid);
    }
    applyState(incoming, !initialized, "PAPER_SYNC");
  }

  private void applyState(DeviceRegistry.State state, boolean bootstrap, String source) throws IOException {
    try {
      registry.replaceAuthoritative(state, bootstrap);
      initialized = true;
      this.source = source;
      lastSyncAt = Instant.now();
      lastRejection = "";
    } catch (SecurityException stale) {
      lastRejection = stale.getMessage() == null ? "ACCESS_SNAPSHOT_REJECTED" : stale.getMessage();
      throw stale;
    } catch (IOException invalid) {
      lastRejection = "ACCESS_SNAPSHOT_INVALID";
      throw invalid;
    }
  }

  public synchronized Map<String, Object> status() {
    LinkedHashMap<String, Object> status = new LinkedHashMap<>();
    status.put("state", initialized ? "READY" : "WAITING_FOR_PAPER");
    status.put("source", source);
    status.put("path", registryPath.toString());
    try {
      DeviceRegistry.State snapshot = registry.snapshot();
      status.put("generation", snapshot.generation());
      status.put("revision", snapshot.revision());
      status.put("deviceCount", snapshot.devices().size());
    } catch (IOException error) {
      status.put("state", "ERROR");
      status.put("error", "MIRROR_READ_FAILED");
    }
    if (lastSyncAt != null) status.put("lastSyncAt", lastSyncAt.toString());
    if (!lastRejection.isBlank()) status.put("lastRejection", lastRejection);
    return Map.copyOf(status);
  }

  private static boolean isBootstrapSentinel(DeviceRegistry.State state) {
    return state.generation() == 1L && state.revision() == 1L && state.devices().isEmpty();
  }

  private static void enforceDirectoryMode(Path directory) throws IOException {
    try {
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX development platform.
    }
  }
}
''')

# Host startup: use Host-owned mirror, migrate only once, request Paper refresh, receive snapshots,
# and expose non-secret diagnostic state.
host_main = "host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostMain.java"
replace_once(
    host_main,
    '''    if (!Files.isRegularFile(Path.of(config.accessRegistry()), LinkOption.NOFOLLOW_LINKS))
      throw new IllegalStateException("Paper must create its local access registry first");
    DeviceRegistry devices =
        new DeviceRegistry(Path.of(config.accessRegistry()), config.serverId());
''',
    '''    Path legacyAccessRegistry = Path.of(config.accessRegistry()).toAbsolutePath().normalize();
    HostAuthorizationMirror authorization =
        new HostAuthorizationMirror(data.resolve("access"), config.serverId());
    authorization.bootstrapLegacy(legacyAccessRegistry);
    DeviceRegistry devices = authorization.registry();
''',
)
replace_once(
    host_main,
    '                List.of(data, Path.of(config.accessRegistry()).getParent())),\n',
    '                List.of(data, legacyAccessRegistry.getParent())),\n',
)
replace_once(
    host_main,
    '''            case "paper.connection" -> {
              boolean online = bool(m.body(), "connected");
              paper.set(online);
              if (online) paperRevision.incrementAndGet();
            }
            case "backup.coordination.result" -> leases.accept(m);
''',
    '''            case "paper.connection" -> {
              boolean online = bool(m.body(), "connected");
              paper.set(online);
              if (online) paperRevision.incrementAndGet();
            }
            case "access.authority.sync" -> {
              try {
                authorization.apply(m.body());
              } catch (Exception rejected) {
                System.err.println(
                    "PlexonPanel Host access mirror rejected snapshot: "
                        + rejected.getClass().getSimpleName()
                        + ": "
                        + rejected.getMessage());
              }
            }
            case "backup.coordination.result" -> leases.accept(m);
''',
)
replace_once(
    host_main,
    '''        () -> {
          telemetryScheduler.execute(fastSnapshot);
          scheduler.execute(serviceSnapshot);
        });
''',
    '''        () -> {
          connection.send("access.authority.request", Map.of(), MessagePriority.CRITICAL);
          telemetryScheduler.execute(fastSnapshot);
          scheduler.execute(serviceSnapshot);
        });
''',
)
replace_count(
    host_main,
    '            status.put("paperConnected", paper.get());\n',
    '            status.put("paperConnected", paper.get());\n            status.put("authorizationMirror", authorization.status());\n',
    1,
)
# The action switch has deeper indentation than the periodic snapshot block.
replace_count(
    host_main,
    '                  status.put("paperConnected", paper.get());\n',
    '                  status.put("paperConnected", paper.get());\n                  status.put("authorizationMirror", authorization.status());\n',
    1,
)

# Host never mutates the Paper-authoritative grant set. Normal access-management actions already
# route to Paper, and forced HOST targeting must fail closed.
host_config = "host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostConfig.java"
replace_once(
    host_config,
    '''    result.putAll(capabilities);
    for (String s :
''',
    '''    result.putAll(capabilities);
    for (String s : Scopes.ALL)
      if (s.startsWith("devices.")) result.put(s, false);
    for (String s :
''',
)

# Paper can answer a Host refresh request without creating a new pairing or transport session.
agent_runtime = "agent/src/main/java/io/github/zpkdxgames/plexonpanel/AgentRuntime.java"
replace_once(
    agent_runtime,
    '''            case "maintenance.coordination" -> maintenanceCoordinator.accept(message);
            case "console.authority" ->
''',
    '''            case "maintenance.coordination" -> maintenanceCoordinator.accept(message);
            case "access.authority.request" -> {
              try {
                actions.syncAccess();
              } catch (java.io.IOException error) {
                throw new IllegalStateException("Host access authority refresh failed", error);
              }
            }
            case "console.authority" ->
''',
)

# Regression tests for migration, Paper-offline Host restart, stale rollback, malformed state,
# revocation propagation and no Host-side revision churn.
test_file = Path("host-agent/src/test/java/io/github/zpkdxgames/plexonpanel/host/HostAuthorizationMirrorTest.java")
test_file.parent.mkdir(parents=True, exist_ok=True)
test_file.write_text(r'''package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import io.github.zpkdxgames.plexonpanel.security.Scopes;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostAuthorizationMirrorTest {
  @TempDir Path root;
  private final Gson gson = new Gson();
  private final String serverId = UUID.randomUUID().toString();

  @Test
  void migratesLegacyRegistryThenRestartsWithoutPaperPath() throws Exception {
    Path legacy = root.resolve("paper/access/devices.json");
    DeviceRegistry paper = new DeviceRegistry(legacy, serverId);
    DeviceRegistry.Device owner = grant(paper, "Owner");
    DeviceRegistry.State authoritative = paper.snapshot();

    HostAuthorizationMirror first = new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    assertTrue(first.bootstrapLegacy(legacy));
    assertEquals(
        owner,
        first.registry().authorize(
            owner.deviceId(), authoritative.generation(), "backup.view", Map.of("backup.view", true)));

    Files.deleteIfExists(legacy);
    Files.deleteIfExists(legacy.resolveSibling("devices.json.lock"));
    HostAuthorizationMirror restarted =
        new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    assertFalse(restarted.bootstrapLegacy(legacy));
    assertEquals("READY", restarted.status().get("state"));
    assertEquals(
        owner,
        restarted.registry().authorize(
            owner.deviceId(), authoritative.generation(), "backup.view", Map.of("backup.view", true)));
  }

  @Test
  void firstPaperSnapshotBootstrapsMirrorAndHostAuthorizationDoesNotChangeRevision() throws Exception {
    HostAuthorizationMirror mirror = new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    assertEquals("WAITING_FOR_PAPER", mirror.status().get("state"));
    DeviceRegistry.Device owner = device("Owner", UUID.randomUUID().toString());
    DeviceRegistry.State state = new DeviceRegistry.State(3, serverId, 44, 7, List.of(owner));
    mirror.apply(gson.toJsonTree(state).getAsJsonObject());
    long revision = mirror.registry().snapshot().revision();
    mirror.registry().authorize(owner.deviceId(), 44, "backup.view", Map.of("backup.view", true));
    assertEquals(revision, mirror.registry().snapshot().revision());
  }

  @Test
  void staleSnapshotsAndRevisionConflictsFailClosed() throws Exception {
    HostAuthorizationMirror mirror = new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    DeviceRegistry.Device owner = device("Owner", UUID.randomUUID().toString());
    DeviceRegistry.State current = new DeviceRegistry.State(3, serverId, 50, 10, List.of(owner));
    mirror.apply(gson.toJsonTree(current).getAsJsonObject());

    var staleGeneration = new DeviceRegistry.State(3, serverId, 49, 99, List.of(owner));
    assertEquals(
        "ACCESS_SNAPSHOT_STALE_GENERATION",
        assertThrows(SecurityException.class, () -> mirror.apply(gson.toJsonTree(staleGeneration).getAsJsonObject()))
            .getMessage());

    var staleRevision = new DeviceRegistry.State(3, serverId, 50, 9, List.of(owner));
    assertEquals(
        "ACCESS_SNAPSHOT_STALE_REVISION",
        assertThrows(SecurityException.class, () -> mirror.apply(gson.toJsonTree(staleRevision).getAsJsonObject()))
            .getMessage());

    var conflict = new DeviceRegistry.State(3, serverId, 50, 10, List.of());
    assertEquals(
        "ACCESS_SNAPSHOT_REVISION_CONFLICT",
        assertThrows(SecurityException.class, () -> mirror.apply(gson.toJsonTree(conflict).getAsJsonObject()))
            .getMessage());
  }

  @Test
  void revocationAndGenerationRotationReplaceOldGrant() throws Exception {
    HostAuthorizationMirror mirror = new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    DeviceRegistry.Device owner = device("Owner", UUID.randomUUID().toString());
    mirror.apply(
        gson.toJsonTree(new DeviceRegistry.State(3, serverId, 70, 3, List.of(owner))).getAsJsonObject());
    mirror.apply(
        gson.toJsonTree(new DeviceRegistry.State(3, serverId, 71, 4, List.of())).getAsJsonObject());
    assertThrows(
        SecurityException.class,
        () -> mirror.registry().authorize(owner.deviceId(), 70, "backup.view", Map.of("backup.view", true)));
    assertEquals(71, mirror.registry().snapshot().generation());
    assertTrue(mirror.registry().snapshot().devices().isEmpty());
  }

  @Test
  void malformedOrWrongServerSnapshotIsRejected() throws Exception {
    HostAuthorizationMirror mirror = new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    var wrongServer =
        new DeviceRegistry.State(3, UUID.randomUUID().toString(), 8, 2, List.of(device("Owner", UUID.randomUUID().toString())));
    assertThrows(
        java.io.IOException.class,
        () -> mirror.apply(gson.toJsonTree(wrongServer).getAsJsonObject()));

    var malformed = new com.google.gson.JsonObject();
    malformed.addProperty("protocolVersion", 3);
    malformed.addProperty("serverId", serverId);
    assertThrows(java.io.IOException.class, () -> mirror.apply(malformed));
  }

  @Test
  void hostMirrorUsesPrivatePosixModeWhenSupported() throws Exception {
    HostAuthorizationMirror mirror = new HostAuthorizationMirror(root.resolve("host/access"), serverId);
    try {
      assertEquals(
          "rwx------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("host/access"))));
      assertEquals(
          "rw-------",
          PosixFilePermissions.toString(
              Files.getPosixFilePermissions(root.resolve("host/access/devices.json"))));
    } catch (UnsupportedOperationException ignored) {
      // Non-POSIX development host.
    }
    assertNotNull(mirror.registry());
  }

  private DeviceRegistry.Device grant(DeviceRegistry registry, String role) throws Exception {
    String requestId = UUID.randomUUID().toString();
    registry.begin(requestId, role, Scopes.ROLES.get(role), Instant.now().plusSeconds(60), 1);
    return registry.consume(requestId, UUID.randomUUID().toString(), "Migration browser");
  }

  private DeviceRegistry.Device device(String role, String id) {
    long now = Instant.now().getEpochSecond();
    return new DeviceRegistry.Device(
        id, "Host mirror browser", role, Scopes.ROLES.get(role), now, now + 3600, now);
  }
}
''')

# Installation/permissions documentation now reflects the Host-owned mirror and one-time migration.
host_docs = "docs/HOST_AGENT.md"
replace_once(
    host_docs,
    '''2. Create `plexonpanel-host` as a system user with its own private group and no login shell. Create `plexonpanel-access`; add Paper and host users to it and restart services after group changes.
3. Install the JAR under root-owned `/opt/plexonpanel-host/`. Install root-owned `/etc/plexonpanel-host/host-config.json` mode 0640, readable by the private host group. The host user must not modify its JAR/config/unit/polkit rule.
4. Create `/var/lib/plexonpanel-host` and `/var/backups/plexonpanel`, host-owned mode 0700. Backups must be outside the live root. Configure only needed top-level include names.
5. Start Paper once to generate `plugins/PlexonPanel/access/devices.json`. Make **only the access directory** group-owned by `plexonpanel-access`, mode 2770 (setgid), and registry/lock files 0660. Parents need traversal rights. Never share private Paper keys or recursively loosen server permissions.
6. Grant host OS read/write access only to paths needed by enabled features. The unit's writable mount allowlist does not grant ownership. Test denied paths. Agents do not need each other's private keys.
''',
    '''2. Create `plexonpanel-host` as a system user with its own private group and no login shell. Paper and Host no longer need a permanently shared access-registry group merely for authorization.
3. Install the JAR under root-owned `/opt/plexonpanel-host/`. Install root-owned `/etc/plexonpanel-host/host-config.json` mode 0640, readable by the private host group. The host user must not modify its JAR/config/unit/polkit rule.
4. Create `/var/lib/plexonpanel-host` and `/var/backups/plexonpanel`, host-owned mode 0700. The Host keeps its durable authorization mirror at `/var/lib/plexonpanel-host/access/devices.json`; the directory is 0700 and mirror/lock files are 0600. Backups must be outside the live root.
5. Existing installations may leave `accessRegistry` pointed at Paper's `plugins/PlexonPanel/access/devices.json` for one-time migration. If no Host mirror exists yet, Host imports that validated registry once. After the mirror exists, Host starts and authenticates paired devices without touching the Paper/plugin path. New grants and revocations arrive as validated Paper-authoritative relay snapshots. Do not copy private keys or loosen the whole server tree.
6. Grant host OS read/write access only to paths needed by enabled features. The unit's writable mount allowlist does not grant ownership. Test denied paths. Agents do not need each other's private keys.
''',
)
replace_once(
    host_docs,
    '''The 3.0.1 full-control example enables telemetry, server status/start/stop/restart, all implemented file operations, the complete backup family, audit, devices and settings. Effective backup capability still requires `backups.enabled`; restore additionally requires `restoreEnabled`. HostConfig continues rejecting Paper-only scope families. File operations remain confined under `serverRoot`, and lifecycle actions remain bound to the validated exact systemd service name.
''',
    '''The full-control example enables telemetry, server status/start/stop/restart, all implemented file operations, the complete backup family, audit and settings. Device pairing/revocation remains Paper-authoritative even if legacy config still lists `devices.*`; Host effective capabilities force those mutations off so an explicit HOST-targeted access change fails closed. Existing paired credentials continue to authorize Host actions from the private mirror while Paper is offline. Effective backup capability still requires `backups.enabled`; restore additionally requires `restoreEnabled`. HostConfig continues rejecting Paper-only scope families. File operations remain confined under `serverRoot`, and lifecycle actions remain bound to the validated exact systemd service name.
''',
)

Path("docs/HOST_AUTHORIZATION_MIRROR.md").write_text('''# Host authorization mirror\n\nPlexonPanel keeps pairing and access mutation authoritative in Paper while allowing the Linux Host Companion to remain usable when Minecraft is stopped.\n\n- Paper publishes Protocol 3 `access.sync` after authentication and every pairing/revocation mutation.\n- The relay validates that state using the existing generation/revision/device contract, persists its coordination copy, and forwards a relay-signed `access.authority.sync` snapshot to Host when Host is online.\n- Host may send `access.authority.request`; the relay accepts it only from authenticated Host and asks authenticated Paper to republish `access.sync`.\n- Host stores the accepted state at `<dataDirectory>/access/devices.json` with private POSIX permissions. Host authorization does not update `lastSeen`, so the mirror revision remains exactly the Paper-authoritative revision and stale rollback comparisons remain meaningful.\n- Generation rollback, revision rollback, equal-revision content conflicts, malformed snapshots, wrong server IDs and invalid grants fail closed. Diagnostics expose only state/generation/revision/device count/source/rejection code, never keys or bearer credentials.\n- `accessRegistry` is retained as a migration-only bootstrap path. It is read only when the Host mirror is still the deterministic empty bootstrap state. Once a valid mirror exists, Paper may be stopped and the plugin path may be unavailable without preventing Host startup.\n- Access-management actions continue to route to Paper. Host forces effective `devices.*` capabilities off to prevent its mirror from becoming a second grant authority.\n\n## Migration check\n\n1. Start the matched Paper and Host versions once so the Host mirror becomes `READY`.\n2. Confirm `/var/lib/plexonpanel-host/access` is host-owned mode 0700 and its registry/lock are 0600.\n3. Stop Minecraft/Paper, restart `plexonpanel-host.service`, and reconnect an already paired browser. Host-backed backup, provider, console and systemd actions must remain authorized.\n4. Start Paper, revoke a device, wait for the access snapshot, stop Paper again, and verify the revoked credential cannot use Host actions.\n5. Do not delete or replace the Host mirror to work around a rejection. Investigate the exposed mirror diagnostic and restore a newer valid Paper-authoritative state.\n''')

print("Step 6 backend patch applied")
