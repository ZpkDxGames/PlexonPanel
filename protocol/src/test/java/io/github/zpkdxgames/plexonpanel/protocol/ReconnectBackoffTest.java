package io.github.zpkdxgames.plexonpanel.protocol;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

class ReconnectBackoffTest {
  @Test
  void growsExponentiallyWithinHardMaximum() {
    Random random = new Random(7L);
    long previous = 0L;
    for (int attempt = 1; attempt <= 12; attempt++) {
      long delay = ReconnectBackoff.delayMillis(attempt, 2_000L, 180_000L, random);
      assertTrue(delay >= 1L);
      assertTrue(delay <= 180_000L);
      if (attempt >= 8) assertTrue(delay >= 100_000L);
      previous = delay;
    }
    assertTrue(previous > 0L);
  }

  @Test
  void unavailableRelayProducesBoundedAttemptsOverTenMinutes() {
    Random random = new Random(19L);
    long elapsed = 0L;
    int attempt = 0;
    while (elapsed < 600_000L) {
      attempt++;
      elapsed += ReconnectBackoff.delayMillis(attempt, 2_000L, 180_000L, random);
    }
    assertTrue(attempt <= 12, "ten-minute outage must not produce a request storm");
  }

  @Test
  void invalidBoundsFailClosed() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ReconnectBackoff.delayMillis(0, 2_000L, 60_000L, new Random()));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReconnectBackoff.delayMillis(1, 60_000L, 2_000L, new Random()));
  }
}
