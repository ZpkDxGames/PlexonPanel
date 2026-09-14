package io.github.zpkdxgames.plexonpanel.host;

import java.io.IOException;

/** Enforces the final-save safety boundary before destructive maintenance continuation. */
final class MaintenanceSafetyGate {
  @FunctionalInterface
  interface CheckedSupplier<T> {
    T get() throws Exception;
  }

  private MaintenanceSafetyGate() {}

  static <T> T afterSuccessfulFlush(
      MinecraftCommandChannel commandChannel, CheckedSupplier<T> destructiveContinuation)
      throws Exception {
    MinecraftCommandChannel.Result result = commandChannel.saveAllFlush();
    if (!result.success()) throw new IOException(result.code());
    return destructiveContinuation.get();
  }
}
