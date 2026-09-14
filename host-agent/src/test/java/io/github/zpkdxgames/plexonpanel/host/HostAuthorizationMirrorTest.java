package io.github.zpkdxgames.plexonpanel.host;

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
