package io.github.zpkdxgames.plexonpanel.protocol;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayGuardTest {
    private final Instant now = Instant.parse("2026-08-14T12:00:00Z");
    private final ReplayGuard guard = new ReplayGuard(Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(30), 10);

    @Test
    void acceptsFreshMessageExactlyOnce() {
        UUID id = UUID.randomUUID();

        assertTrue(guard.accept(id, now));
        assertFalse(guard.accept(id, now));
    }

    @Test
    void rejectsMessagesOutsideClockWindow() {
        assertFalse(guard.accept(UUID.randomUUID(), now.minusSeconds(31)));
        assertFalse(guard.accept(UUID.randomUUID(), now.plusSeconds(31)));
        assertTrue(guard.accept(UUID.randomUUID(), now.plusSeconds(30)));
    }
}
