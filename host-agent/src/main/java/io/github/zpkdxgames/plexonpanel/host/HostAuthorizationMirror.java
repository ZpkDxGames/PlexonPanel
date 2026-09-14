package io.github.zpkdxgames.plexonpanel.host;

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
