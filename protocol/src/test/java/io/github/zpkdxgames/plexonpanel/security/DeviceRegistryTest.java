package io.github.zpkdxgames.plexonpanel.security;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceRegistryTest {
  @TempDir Path temporary;
  final String server = UUID.randomUUID().toString();
  final Instant now = Instant.parse("2026-09-05T12:00:00Z");

  DeviceRegistry registry() throws Exception {
    return new DeviceRegistry(
        temporary.resolve("access.json"), server, Clock.fixed(now, ZoneOffset.UTC));
  }

  DeviceRegistry.Device grant(DeviceRegistry r, String role) throws Exception {
    String request = UUID.randomUUID().toString();
    r.begin(request, role, Scopes.ROLES.get(role), now.plusSeconds(60), 1);
    return r.consume(request, UUID.randomUUID().toString(), "Test browser");
  }

  @Test
  void localPolicyWinsEvenForOwner() throws Exception {
    var r = registry();
    var owner = grant(r, "Owner");
    long generation = r.snapshot().generation();
    assertEquals(
        "CAPABILITY_DISABLED",
        assertThrows(
                SecurityException.class,
                () ->
                    r.authorize(
                        owner.deviceId(), generation, "player.op", Map.of("player.op", false)))
            .getMessage());
    assertEquals(
        owner, r.authorize(owner.deviceId(), generation, "player.op", Map.of("player.op", true)));
  }

  @Test
  void newlyIssuedOwnerReceivesCanonicalAllScopes() throws Exception {
    var r = registry();
    var owner = grant(r, "Owner");
    assertEquals(Scopes.ALL, owner.scopes());
  }

  @Test
  void observerCannotBorrowModeratorScopes() throws Exception {
    var r = registry();
    var observer = grant(r, "Observer");
    long generation = r.snapshot().generation();
    assertEquals(
        "SCOPE_DENIED",
        assertThrows(
                SecurityException.class,
                () ->
                    r.authorize(
                        observer.deviceId(),
                        generation,
                        "player.kick",
                        Map.of("player.kick", true)))
            .getMessage());
    assertFalse(observer.scopes().contains("players.address"));
    assertFalse(observer.scopes().contains("players.history.view"));
    assertFalse(observer.scopes().contains("files.write"));
  }

  @Test
  void historyRoleAndCapabilityRulesDoNotUpgradeExistingGrants() throws Exception {
    var r = registry();
    for (String role : List.of("Moderator", "Administrator", "Owner"))
      assertTrue(Scopes.ROLES.get(role).contains("players.history.view"));
    assertFalse(Scopes.ROLES.get("Observer").contains("players.history.view"));
    assertEquals("players.history.view", Scopes.required("players.history.list"));
    assertEquals("players.view", Scopes.required("players.snapshot.request"));

    Set<String> oldModeratorScopes =
        Scopes.ROLES.get("Moderator").stream()
            .filter(scope -> !scope.equals("players.history.view"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    String request = UUID.randomUUID().toString();
    r.begin(request, "Moderator", oldModeratorScopes, now.plusSeconds(60), 1);
    var existing = r.consume(request, UUID.randomUUID().toString(), "Existing moderator");
    assertFalse(existing.scopes().contains("players.history.view"));
    assertEquals(
        "SCOPE_DENIED",
        assertThrows(
                SecurityException.class,
                () ->
                    r.authorize(
                        existing.deviceId(),
                        r.snapshot().generation(),
                        "players.history.view",
                        Map.of("players.history.view", true)))
            .getMessage());
  }

  @Test
  void pendingGrantIsOneUseAndCannotBeInventedByBrowser() throws Exception {
    var r = registry();
    String request = UUID.randomUUID().toString();
    r.begin(request, "Observer", Scopes.ROLES.get("Observer"), now.plusSeconds(30), 1);
    assertThrows(
        SecurityException.class,
        () -> r.consume(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "Attacker"));
    assertEquals("Observer", r.consume(request, UUID.randomUUID().toString(), "Device").role());
    assertThrows(
        SecurityException.class, () -> r.consume(request, UUID.randomUUID().toString(), "Replay"));
  }

  @Test
  void revocationIsDurableAndScopedToOneDevice() throws Exception {
    var r = registry();
    var first = grant(r, "Observer");
    var second = grant(r, "Observer");
    var fresh = registry();
    long generation = r.snapshot().generation();
    r.revoke(first.deviceId());
    assertThrows(
        SecurityException.class,
        () ->
            fresh.authorize(
                first.deviceId(), generation, "players.view", Map.of("players.view", true)));
    assertEquals(
        second,
        fresh.authorize(
            second.deviceId(), generation, "players.view", Map.of("players.view", true)));
    fresh.revokeAll();
    assertNotEquals(generation, r.snapshot().generation());
    assertTrue(r.snapshot().devices().isEmpty());
  }

  @Test
  void expiredAndWrongGenerationGrantsAreDenied() throws Exception {
    var r = registry();
    var d = grant(r, "Observer");
    long generation = r.snapshot().generation();
    assertThrows(
        SecurityException.class,
        () ->
            r.authorize(
                d.deviceId(), generation + 1, "players.view", Map.of("players.view", true)));
    var later =
        new DeviceRegistry(
            temporary.resolve("access.json"),
            server,
            Clock.fixed(now.plusSeconds(86400), ZoneOffset.UTC));
    assertEquals(
        "DEVICE_EXPIRED",
        assertThrows(
                SecurityException.class,
                () ->
                    later.authorize(
                        d.deviceId(), generation, "players.view", Map.of("players.view", true)))
            .getMessage());
  }

  @Test
  void unknownScopesAndUnboundedLifetimesCannotBeGranted() throws Exception {
    var r = registry();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            r.begin(
                UUID.randomUUID().toString(),
                "Observer",
                Set.of("shell.execute"),
                now.plusSeconds(60),
                1));
    assertThrows(
        IllegalArgumentException.class,
        () -> r.begin(UUID.randomUUID().toString(), "Owner", Scopes.ALL, now.plusSeconds(60), 31));
    assertThrows(
        IllegalArgumentException.class,
        () -> r.begin(UUID.randomUUID().toString(), "Owner", Scopes.ALL, now.plusSeconds(301), 1));
  }
}
