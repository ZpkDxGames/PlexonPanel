package io.github.zpkdxgames.plexonpanel.util;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class NamedThreadFactory implements ThreadFactory {
  private final String prefix;
  private final AtomicInteger sequence = new AtomicInteger();

  public NamedThreadFactory(String prefix) {
    this.prefix = Objects.requireNonNull(prefix, "prefix");
  }

  @Override
  public Thread newThread(Runnable runnable) {
    Thread thread =
        Thread.ofPlatform()
            .name(prefix + "-" + sequence.incrementAndGet())
            .daemon(true)
            .unstarted(runnable);
    thread.setUncaughtExceptionHandler((ignored, error) -> error.printStackTrace(System.err));
    return thread;
  }
}
