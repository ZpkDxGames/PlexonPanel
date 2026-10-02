package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeBackupCoordinatorTest {
  @TempDir Path directory;

  @Test void excludesOtherHostsAndReleasesAfterProcessDeath() throws Exception {
    Path file = Files.createFile(directory.resolve("shared.lock"));
    Process child = child(file);
    try {
      ready(child);
      var coordinator = new NodeBackupCoordinator(file);
      AtomicInteger queued = new AtomicInteger();
      IOException busy = assertThrows(IOException.class,
          () -> coordinator.acquire(Duration.ofMillis(150), queued::incrementAndGet));
      assertEquals("NODE_BACKUP_BUSY", busy.getMessage());
      assertEquals(1, queued.get());
      assertFalse(coordinator.active());
      assertFalse(coordinator.queued());
      // A different node's inode is independent while this child still owns the shared node.
      var other = new NodeBackupCoordinator(Files.createFile(directory.resolve("other.lock")));
      try (var lease = other.acquire(Duration.ZERO, null)) { assertTrue(other.active()); }
      child.destroyForcibly();
      assertTrue(child.waitFor(5, TimeUnit.SECONDS));
      try (var lease = coordinator.acquire(Duration.ofSeconds(2), null)) { assertTrue(coordinator.active()); }
      assertFalse(coordinator.active());
      assertTrue(Files.isRegularFile(file)); // Never unlink a kernel lock's inode.
    } finally { child.destroyForcibly(); }
  }

  @Test void waitsWithoutBlockingIndependentHeartbeatAndCanBeInterrupted() throws Exception {
    Path file = Files.createFile(directory.resolve("shared.lock"));
    Process child = child(file);
    var worker = Executors.newSingleThreadExecutor();
    var telemetry = Executors.newSingleThreadScheduledExecutor();
    try {
      ready(child);
      var coordinator = new NodeBackupCoordinator(file);
      var queued = new CountDownLatch(1);
      AtomicInteger beats = new AtomicInteger();
      telemetry.scheduleAtFixedRate(beats::incrementAndGet, 0, 5, TimeUnit.MILLISECONDS);
      Future<?> waiting = worker.submit(() -> {
        try (var lease = coordinator.acquire(Duration.ofSeconds(30), queued::countDown)) {
          fail("Must not acquire a held node lock");
        } catch (InterruptedException expected) {
          Thread.currentThread().interrupt();
        } catch (IOException error) { throw new UncheckedIOException(error); }
      });
      assertTrue(queued.await(3, TimeUnit.SECONDS));
      assertTrue(coordinator.queued());
      int before = beats.get();
      assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));
      assertTrue(beats.get() > before);
      waiting.cancel(true);
      worker.shutdown();
      assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
      assertFalse(coordinator.queued());
    } finally { worker.shutdownNow(); telemetry.shutdownNow(); child.destroyForcibly(); }
  }

  @Test void nestedLeaseKeepsExclusionUntilOutermostReleaseAndCloseIsIdempotent() throws Exception {
    Path file = Files.createFile(directory.resolve("node.lock"));
    var first = new NodeBackupCoordinator(file);
    var second = new NodeBackupCoordinator(file);
    try (var outer = first.acquire(Duration.ZERO, null)) {
      var inner = first.acquire(Duration.ZERO, null);
      inner.close(); inner.close();
      assertTrue(first.active());
      assertThrows(IOException.class, () -> second.acquire(Duration.ZERO, null));
    }
    try (var lease = second.acquire(Duration.ZERO, null)) { assertTrue(second.active()); }
  }

  @Test void rejectsMissingFilesAndSymlinkedFileOrParent() throws Exception {
    assertThrows(IOException.class, () -> new NodeBackupCoordinator(directory.resolve("absent")));
    Path file = Files.createFile(directory.resolve("real.lock"));
    Path link = Files.createSymbolicLink(directory.resolve("link.lock"), file);
    assertThrows(IOException.class, () -> new NodeBackupCoordinator(link));
    Path parent = Files.createSymbolicLink(directory.resolve("parent"), directory);
    assertThrows(IOException.class, () -> new NodeBackupCoordinator(parent.resolve("real.lock")));
  }

  private static Process child(Path file) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    String cp = System.getProperty("java.class.path");
    // Console launcher classpaths can be separate from java.class.path.
    String classes = Path.of(NodeBackupCoordinatorTest.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString();
    String main = Path.of(NodeBackupCoordinator.class.getProtectionDomain().getCodeSource().getLocation().getPath()).toString();
    return new ProcessBuilder(java, "-cp", cp + File.pathSeparator + classes + File.pathSeparator + main,
        LockHolder.class.getName(), file.toString()).redirectErrorStream(true).start();
  }

  private static void ready(Process child) throws Exception {
    try (var reader = new BufferedReader(new InputStreamReader(child.getInputStream()))) {
      var read = Executors.newSingleThreadExecutor();
      try { assertEquals("LOCKED", read.submit(reader::readLine).get(5, TimeUnit.SECONDS)); }
      finally { read.shutdownNow(); }
    }
  }

  public static final class LockHolder {
    public static void main(String[] args) throws Exception {
      var coordinator = new NodeBackupCoordinator(Path.of(args[0]));
      try (var lease = coordinator.acquire(Duration.ZERO, null)) {
        System.out.println("LOCKED"); System.out.flush();
        Thread.sleep(Duration.ofMinutes(2));
      }
    }
  }
}
