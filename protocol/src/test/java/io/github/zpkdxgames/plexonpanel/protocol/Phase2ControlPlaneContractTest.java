package io.github.zpkdxgames.plexonpanel.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zpkdxgames.plexonpanel.identity.PairingCodeGenerator;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

final class Phase2ControlPlaneContractTest {
  @Test
  void phaseTwoPreservesProtocolThreeAndShortLivedPairing() {
    assertEquals(3, ProtocolCodec.VERSION);
    assertEquals(Duration.ofMinutes(5), PairingCodeGenerator.DEFAULT_TTL);
  }

  @Test
  void reconnectPolicyRemainsBoundedForLongOutages() {
    Random random = new Random(3200L);
    for (int attempt = 1; attempt <= 100; attempt++) {
      long delay = ReconnectBackoff.delayMillis(attempt, 1_000L, 60_000L, random);
      assertTrue(delay >= 1L);
      assertTrue(delay <= 60_000L);
    }
  }
}
