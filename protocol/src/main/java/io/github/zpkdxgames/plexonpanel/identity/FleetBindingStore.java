package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.Gson;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.Objects;

/** Durable clone detection plus an exclusive process lease for an instance's identity state. */
public final class FleetBindingStore {
  private static final Gson GSON = new Gson();
  private final Path directory;
  private final Path serverRoot;

  public FleetBindingStore(Path dataDirectory, Path serverRoot) throws IOException {
    rejectSymlinks(dataDirectory.resolve("identity"));
    Files.createDirectories(dataDirectory.resolve("identity"));
    rejectSymlinks(serverRoot);
    this.directory = dataDirectory.resolve("identity").toRealPath();
    this.serverRoot = serverRoot.toRealPath();
  }

  /** Ordinary restart/reload preserves identity. A copied binding fails before any rewrite. */
  public Lease claim(FleetIdentity identity, String publicKeyFingerprint) throws IOException {
    Objects.requireNonNull(identity);
    if (publicKeyFingerprint == null || publicKeyFingerprint.isBlank() || publicKeyFingerprint.length() > 128)
      throw new IOException("FLEET_DEVICE_FINGERPRINT_INVALID");
    Path leaseFile = directory.resolve("fleet.lock");
    rejectSymlinks(leaseFile);
    FileChannel channel = FileChannel.open(leaseFile, StandardOpenOption.CREATE,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    FileLock lock = null;
    try {
      try { lock = channel.tryLock(); } catch (OverlappingFileLockException busy) { /* fail closed */ }
      if (lock == null) throw new IOException("FLEET_IDENTITY_ALREADY_ACTIVE");
      var expected = new Binding(1, identity.serverId().toString(), identity.nodeId().toString(),
          identity.instanceKey(), serverRoot.toString(), directory.toString(), publicKeyFingerprint);
      Path file = directory.resolve("fleet-binding.json");
      rejectSymlinks(file);
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192)
          throw new IOException("FLEET_BINDING_INVALID");
        Binding actual;
        try { actual = GSON.fromJson(Files.readString(file), Binding.class); }
        catch (RuntimeException invalid) { throw new IOException("FLEET_BINDING_INVALID"); }
        if (!expected.equals(actual)) throw new IOException("FLEET_CLONE_OR_BINDING_CHANGE_REQUIRES_OFFLINE_REKEY");
      } else {
        AtomicFiles.writeUtf8(file, GSON.toJson(expected) + "\n");
      }
      return new Lease(channel, lock);
    } catch (IOException | RuntimeException failure) {
      try { if (lock != null) lock.release(); } finally { channel.close(); }
      throw failure;
    }
  }

  private static void rejectSymlinks(Path path) throws IOException {
    for (Path part = path.toAbsolutePath(); part != null; part = part.getParent())
      if (Files.isSymbolicLink(part)) throw new IOException("FLEET_IDENTITY_SYMLINK_DENIED");
  }

  private record Binding(int schemaVersion, String serverId, String nodeId, String instanceKey,
      String serverRoot, String identityDirectory, String fingerprint) {}

  public static final class Lease implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    private Lease(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }
    @Override public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      try { lock.release(); } finally { channel.close(); }
    }
  }
}
