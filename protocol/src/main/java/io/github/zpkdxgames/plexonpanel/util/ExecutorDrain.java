package io.github.zpkdxgames.plexonpanel.util;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded termination observation; a timeout never means work has completed. */
public final class ExecutorDrain {
  private ExecutorDrain() {}
  public static Duration remaining(long deadline) { return Duration.ofNanos(Math.max(0, deadline - System.nanoTime())); }
  public static boolean await(ExecutorService executor, Duration timeout) {
    if (timeout.isNegative() || timeout.compareTo(Duration.ofMinutes(10)) > 0)
      throw new IllegalArgumentException("Invalid drain timeout");
    try { return executor.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS); }
    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
  }
}
