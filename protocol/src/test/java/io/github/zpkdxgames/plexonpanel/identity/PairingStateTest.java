package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PairingStateTest {
  private static final Instant NOW = Instant.parse("2026-08-15T18:00:00Z");

  @Test
  void codeIsHiddenUntilGatewayRegistration() {
    PairingState state = new PairingState(Clock.fixed(NOW, ZoneOffset.UTC));
    state.beginCode("request-1", "481927", NOW.plusSeconds(300));

    assertTrue(state.hasPendingCode());
    assertTrue(state.activeCode().isEmpty());

    state.markCodeRegistered("request-1", "challenge-1", NOW.plusSeconds(240));

    PairingState.PairingCode active = state.activeCode().orElseThrow();
    assertEquals("481927", active.value());
    assertEquals("challenge-1", active.challengeId());
    assertEquals(NOW.plusSeconds(240), active.expiresAt());
    assertFalse(state.hasPendingCode());
  }

  @Test
  void mismatchedGatewayRegistrationIsRejected() {
    PairingState state = new PairingState(Clock.fixed(NOW, ZoneOffset.UTC));
    state.beginCode("request-1", "481927", NOW.plusSeconds(300));

    assertThrows(
        IllegalStateException.class,
        () -> state.markCodeRegistered("request-2", "challenge-1", NOW.plusSeconds(240)));
  }

  @Test
  void expiredCodesNeverBecomeActive() {
    PairingState state = new PairingState(Clock.fixed(NOW, ZoneOffset.UTC));

    assertThrows(IllegalArgumentException.class, () -> state.beginCode("request-1", "481927", NOW));
  }
}
