package io.github.zpkdxgames.plexonpanel.identity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityStoreTest {
    @TempDir
    Path temporaryDirectory;

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
    void pairingStateSurvivesRestartAndCanBeCleared() throws Exception {
        PairingState first = new PairingState(temporaryDirectory);
        assertFalse(first.isPaired());

        first.markPaired();
        assertTrue(new PairingState(temporaryDirectory).isPaired());

        first.clear();
        assertFalse(new PairingState(temporaryDirectory).isPaired());
    }
}
