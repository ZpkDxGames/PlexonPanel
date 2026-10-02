package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityStoreTest {
  @TempDir Path temporaryDirectory;

  @Test
  void identityIsPersistedAndRotationChangesTheKey() throws Exception {
    Clock clock = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
    IdentityStore store = new IdentityStore(temporaryDirectory, clock);

    DeviceIdentity initial = store.loadOrCreate();
    DeviceIdentity loaded = new IdentityStore(temporaryDirectory, clock).loadOrCreate();
    DeviceIdentity rotated = store.rotate();

    assertEquals(initial.serverId(), loaded.serverId());
    assertEquals(initial.publicKeyBase64(), loaded.publicKeyBase64());
    assertNotEquals(initial.serverId(), rotated.serverId());
    assertNotEquals(initial.publicKeyBase64(), rotated.publicKeyBase64());
    assertTrue(Files.isRegularFile(temporaryDirectory.resolve("identity/device.key")));
  }

  @Test
  void mixedKeyStateAfterInterruptedPublicationIsRejectedWithoutRewritingIt() throws Exception {
    var first = new IdentityStore(temporaryDirectory).loadOrCreate();
    Path other = temporaryDirectory.resolve("other");
    new IdentityStore(other).loadOrCreate();
    Path key = temporaryDirectory.resolve("identity/device.key");
    Files.writeString(key, Files.readString(other.resolve("identity/device.key")));
    String mixed = Files.readString(key);
    assertThrows(java.io.IOException.class, () -> new IdentityStore(temporaryDirectory).loadOrCreate());
    assertEquals(mixed, Files.readString(key));
    assertTrue(Files.readString(temporaryDirectory.resolve("identity/device.json")).contains(first.serverId().toString()));
  }

  @Test
  void rejectsSymlinkedPrivateStateAndDoesNotEchoMalformedKeyContents() throws Exception {
    new IdentityStore(temporaryDirectory).loadOrCreate();
    Path key = temporaryDirectory.resolve("identity/device.key");
    Files.writeString(key, "invalid-private-key-input");
    var failure = assertThrows(java.io.IOException.class, () -> new IdentityStore(temporaryDirectory).loadOrCreate());
    assertFalse(failure.toString().contains("invalid-private-key-input"));
    assertEquals(null, failure.getCause());
    Files.delete(key);
    Files.createSymbolicLink(key, temporaryDirectory.resolve("outside"));
    assertThrows(java.io.IOException.class, () -> new IdentityStore(temporaryDirectory).loadOrCreate());
    assertTrue(Files.isSymbolicLink(key));
  }

  @Test
  void pairingStateSurvivesRestartAndCanBeCleared() throws Exception {
    PairingState first = new PairingState(temporaryDirectory);
    assertFalse(first.isPaired());

    first.markPaired();
    assertTrue(new PairingState(temporaryDirectory).isPaired());

    first.clear();
    assertFalse(new PairingState(temporaryDirectory).isPaired());
  }
  @Test
  void registeredServerIdCreatesFreshIdentityButNeverRewritesAnExistingIdentity() throws Exception {
    var registered = java.util.UUID.randomUUID();
    var store = new IdentityStore(temporaryDirectory);
    var first = store.loadOrCreate(registered);
    assertEquals(registered, first.serverId());
    byte[] key = Files.readAllBytes(temporaryDirectory.resolve("identity/device.key"));
    byte[] metadata = Files.readAllBytes(temporaryDirectory.resolve("identity/device.json"));
    assertEquals(first.fingerprint(), store.loadOrCreate(registered).fingerprint());
    assertThrows(java.io.IOException.class, () -> store.loadOrCreate(java.util.UUID.randomUUID()));
    assertArrayEquals(key, Files.readAllBytes(temporaryDirectory.resolve("identity/device.key")));
    assertArrayEquals(metadata, Files.readAllBytes(temporaryDirectory.resolve("identity/device.json")));
  }
}
