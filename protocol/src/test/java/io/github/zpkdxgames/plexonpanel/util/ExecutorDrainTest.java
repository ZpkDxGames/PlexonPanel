package io.github.zpkdxgames.plexonpanel.util;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class ExecutorDrainTest {
  @Test void stalledWorkerTimesOutAndOnlyReportsDrainedAfterItActuallyExits() throws Exception {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    worker.execute(() -> {
      entered.countDown();
      boolean interrupted = false;
      while (true) try { release.await(); break; } catch (InterruptedException retry) { interrupted = true; }
      if (interrupted) Thread.currentThread().interrupt();
    });
    try {
      assertTrue(entered.await(2, TimeUnit.SECONDS)); worker.shutdownNow();
      long start = System.nanoTime();
      assertFalse(ExecutorDrain.await(worker, Duration.ofMillis(20)));
      assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
      release.countDown();
      assertTrue(ExecutorDrain.await(worker, Duration.ofSeconds(2)));
    } finally { release.countDown(); worker.shutdownNow(); worker.awaitTermination(2, TimeUnit.SECONDS); }
  }
  @Test void interruptIsPreservedAndCannotBeReportedAsSuccessfulDrain() {
    ExecutorService worker = Executors.newSingleThreadExecutor();
    try {
      Thread.currentThread().interrupt();
      assertFalse(ExecutorDrain.await(worker, Duration.ofSeconds(1)));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally { Thread.interrupted(); worker.shutdownNow(); }
  }
}
