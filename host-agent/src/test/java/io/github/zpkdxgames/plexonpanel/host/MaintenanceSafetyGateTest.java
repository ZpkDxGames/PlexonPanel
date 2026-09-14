package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class MaintenanceSafetyGateTest {
  @Test
  void failedFlushNeverInvokesDestructiveContinuation() {
    AtomicBoolean invoked = new AtomicBoolean();
    MinecraftCommandChannel channel = channel(MinecraftCommandChannel.Result.failed("RCON_TIMEOUT"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                MaintenanceSafetyGate.afterSuccessfulFlush(
                    channel,
                    () -> {
                      invoked.set(true);
                      return null;
                    }));

    assertEquals("RCON_TIMEOUT", failure.getMessage());
    assertFalse(invoked.get());
  }

  @Test
  void successfulFlushAllowsDestructiveContinuation() throws Exception {
    AtomicBoolean invoked = new AtomicBoolean();
    MinecraftCommandChannel channel = channel(MinecraftCommandChannel.Result.ok());

    String result =
        MaintenanceSafetyGate.afterSuccessfulFlush(
            channel,
            () -> {
              invoked.set(true);
              return "continued";
            });

    assertTrue(invoked.get());
    assertEquals("continued", result);
  }

  private static MinecraftCommandChannel channel(MinecraftCommandChannel.Result flush) {
    return new MinecraftCommandChannel() {
      @Override
      public boolean enabled() {
        return true;
      }

      @Override
      public Result maintenanceNotice(MaintenanceOperation operation, int remainingSeconds) {
        return Result.ok();
      }

      @Override
      public Result saveAllFlush() {
        return flush;
      }

      @Override
      public Result readinessProbe() {
        return Result.ok();
      }
    };
  }
}
