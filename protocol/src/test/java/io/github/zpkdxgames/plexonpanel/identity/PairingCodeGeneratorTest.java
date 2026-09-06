package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class PairingCodeGeneratorTest {
  private static final Instant NOW = Instant.parse("2026-08-15T18:00:00Z");

  @Test
  void generatesZeroPaddedCodeWithFiveMinuteLifetime() {
    PairingCodeGenerator generator =
        new PairingCodeGenerator(Clock.fixed(NOW, ZoneOffset.UTC), () -> 42);

    PairingCodeGenerator.GeneratedCode code = generator.generate();

    assertEquals("000042", code.value());
    assertEquals(NOW, code.createdAt());
    assertEquals(NOW.plus(PairingCodeGenerator.DEFAULT_TTL), code.expiresAt());
  }

  @Test
  void rejectsOutOfRangeRandomSources() {
    PairingCodeGenerator generator =
        new PairingCodeGenerator(Clock.fixed(NOW, ZoneOffset.UTC), () -> 1_000_000);

    assertThrows(IllegalStateException.class, generator::generate);
  }
}
