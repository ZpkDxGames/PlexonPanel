package io.github.zpkdxgames.plexonpanel.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {
  private final CommandPolicy policy =
      new CommandPolicy(
          List.of("^(tps|paper version)$", "^spark health$"),
          List.of("^stop(\\s.*)?$", ".*(password|token).*"));

  @Test
  void normalizesAndAllowsExplicitCommand() {
    CommandPolicy.Decision decision = policy.evaluate(" /TPS ");

    assertTrue(decision.allowed());
    assertEquals("TPS", decision.command());
  }

  @Test
  void denyRulesTakePrecedenceAndNewlinesAreRejected() {
    assertFalse(policy.evaluate("stop now").allowed());
    assertEquals("COMMAND_DENIED", policy.evaluate("stop now").code());
    assertEquals("INVALID_COMMAND", policy.evaluate("tps\nstop").code());
  }

  @Test
  void unmatchedCommandsDefaultToDeny() {
    assertEquals("COMMAND_NOT_ALLOWED", policy.evaluate("list").code());
  }
}
