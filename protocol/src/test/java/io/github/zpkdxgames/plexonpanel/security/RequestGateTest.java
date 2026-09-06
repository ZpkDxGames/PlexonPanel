package io.github.zpkdxgames.plexonpanel.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RequestGateTest {
  @Test
  void duplicateActionCannotBeExecutedAgain() {
    var gate = new RequestGate();
    String device = UUID.randomUUID().toString(), id = UUID.randomUUID().toString();
    gate.accept(device, id);
    assertEquals(
        "DUPLICATE_REQUEST",
        assertThrows(SecurityException.class, () -> gate.accept(device, id)).getMessage());
  }

  @Test
  void commandFloodAndTransferFloodHaveSeparateBounds() {
    var gate = new RequestGate(Clock.fixed(Instant.now(), ZoneOffset.UTC));
    String d = UUID.randomUUID().toString();
    for (int i = 0; i < 20; i++) gate.accept(d, UUID.randomUUID().toString());
    assertEquals(
        "RATE_LIMITED",
        assertThrows(SecurityException.class, () -> gate.accept(d, UUID.randomUUID().toString()))
            .getMessage());
    for (int i = 0; i < 160; i++) gate.accept(d, UUID.randomUUID().toString(), true);
    assertThrows(SecurityException.class, () -> gate.accept(d, UUID.randomUUID().toString(), true));
  }

  @Test
  void invalidIdsCannotEnterTheCache() {
    assertThrows(IllegalArgumentException.class, () -> new RequestGate().accept("bad", "also-bad"));
  }
}
