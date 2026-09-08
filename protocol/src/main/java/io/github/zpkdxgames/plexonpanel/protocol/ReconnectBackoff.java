package io.github.zpkdxgames.plexonpanel.protocol;

import java.util.random.RandomGenerator;

/** Shared bounded exponential reconnect policy for Protocol 3 clients. */
public final class ReconnectBackoff {
  private ReconnectBackoff() {}

  public static long delayMillis(
      int attempt,
      long initialMillis,
      long maximumMillis,
      RandomGenerator random) {
    if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
    if (initialMillis < 1 || maximumMillis < initialMillis)
      throw new IllegalArgumentException("invalid reconnect delay bounds");
    if (random == null) throw new IllegalArgumentException("random is required");

    int shift = Math.min(attempt - 1, 20);
    long exponential;
    try {
      exponential = Math.multiplyExact(initialMillis, 1L << shift);
    } catch (ArithmeticException overflow) {
      exponential = maximumMillis;
    }
    long base = Math.min(maximumMillis, exponential);

    // Spread clients over a 40% window so Paper, Host and browsers do not reconnect in lockstep.
    double multiplier = 0.8d + random.nextDouble() * 0.4d;
    long jittered = Math.max(1L, Math.round(base * multiplier));
    // Jitter may exceed the configured ceiling by at most 20%; clamp to preserve the hard maximum.
    return Math.min(maximumMillis, jittered);
  }
}
