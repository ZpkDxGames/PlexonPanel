package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.FleetIdentity;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Kernel-backed, node-wide exclusion for archive creation and upload, independent of telemetry. */
public final class NodeBackupCoordinator {
  private final Path lockFile;
  private final ThreadLocal<Held> held = new ThreadLocal<>();
  private final AtomicInteger waiters = new AtomicInteger();
  private final AtomicInteger owners = new AtomicInteger();

  /** The administrator provisions this existing inode; Hosts must never create or delete it. */
  public static NodeBackupCoordinator forNode(UUID nodeId) throws IOException {
    FleetIdentity.parseUuid(nodeId.toString(), "nodeId");
    Path file = Path.of("/run/plexonpanel/locks", nodeId + ".lock");
    validatePath(file);
    for (Path parentPath = file.getParent(); parentPath != null; parentPath = parentPath.getParent()) {
      var parent = Files.readAttributes(parentPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!"root".equals(parent.owner().getName())
          || parent.permissions().contains(PosixFilePermission.GROUP_WRITE)
          || parent.permissions().contains(PosixFilePermission.OTHERS_WRITE))
        throw new IOException("NODE_LOCK_DIRECTORY_UNTRUSTED");
    }
    if (!"root".equals(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).getName()))
      throw new IOException("NODE_LOCK_FILE_UNTRUSTED");
    return new NodeBackupCoordinator(file);
  }

  // Package-private path injection keeps process tests independent of production provisioning.
  NodeBackupCoordinator(Path lockFile) throws IOException {
    this.lockFile = lockFile.toAbsolutePath().normalize();
    validatePath(this.lockFile);
  }

  /** Reentrant only on the owning thread, allowing the same lease to cover stop through recovery. */
  public Lease acquire(Duration timeout, Runnable onQueued) throws IOException, InterruptedException {
    if (timeout == null || timeout.isNegative() || timeout.compareTo(Duration.ofHours(24)) > 0)
      throw new IllegalArgumentException("Invalid node lock timeout");
    if (Thread.interrupted()) throw new InterruptedException();
    Held existing = held.get();
    if (existing != null) {
      existing.depth++;
      return new Lease(existing);
    }
    validatePath(lockFile);
    Object inode = Files.readAttributes(lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    boolean transferred = false;
    waiters.incrementAndGet();
    try {
      Object after = Files.readAttributes(lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
      if (inode == null || !inode.equals(after)) throw new IOException("NODE_LOCK_FILE_CHANGED");
      long started = System.nanoTime(), budget = timeout.toNanos();
      boolean announced = false;
      while (true) {
        if (Thread.interrupted()) throw new InterruptedException();
        FileLock lock = null;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException busyInThisJvm) {
          // Another instance in this JVM obeys the same exclusion as a separate Host process.
        }
        if (lock != null) {
          Held next = new Held(channel, lock);
          held.set(next);
          owners.incrementAndGet();
          transferred = true;
          return new Lease(next);
        }
        if (!announced) {
          announced = true;
          if (onQueued != null) onQueued.run();
        }
        long remaining = budget - (System.nanoTime() - started);
        if (remaining <= 0) throw new IOException("NODE_BACKUP_BUSY");
        Thread.sleep(Math.max(1, Math.min(100, Duration.ofNanos(remaining).toMillis())));
      }
    } finally {
      waiters.decrementAndGet();
      if (!transferred) channel.close();
    }
  }

  boolean heldByCurrentThread() { return held.get() != null; }

  public boolean active() { return owners.get() > 0; }
  public boolean queued() { return waiters.get() > 0; }

  private static void validatePath(Path file) throws IOException {
    for (Path part = file; part != null; part = part.getParent()) {
      if (Files.isSymbolicLink(part)) throw new IOException("NODE_LOCK_SYMLINK_DENIED");
    }
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
      throw new IOException("NODE_LOCK_FILE_UNAVAILABLE");
  }

  private static final class Held {
    final FileChannel channel;
    final FileLock lock;
    final Thread owner = Thread.currentThread();
    int depth = 1;
    Held(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
  }

  public final class Lease implements AutoCloseable {
    private final Held value;
    private final AtomicBoolean closed = new AtomicBoolean();
    private Lease(Held value) { this.value = value; }
    @Override public void close() throws IOException {
      if (Thread.currentThread() != value.owner) throw new IllegalStateException("NODE_LOCK_OWNER_REQUIRED");
      if (!closed.compareAndSet(false, true)) return;
      if (--value.depth != 0) return;
      held.remove();
      owners.decrementAndGet();
      try { value.lock.release(); } finally { value.channel.close(); }
    }
  }
}
