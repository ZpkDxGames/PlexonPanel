package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** Local offline preservation; next ordinary Host startup seeds the new private configuration. */
final class ProviderReseed {
  private ProviderReseed() {}
  static Path preserve(Path state, String hostUser) throws IOException {
    Path provider = state.resolve("provider"), lockPath = state.resolve("identity/fleet.lock");
    for (Path path : List.of(state, provider, lockPath)) InstanceLayout.rejectSymlinks(path);
    var attrs = Files.readAttributes(provider, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isDirectory() || !attrs.owner().getName().equals(hostUser)
        || !attrs.permissions().equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("PROVIDER_RUNTIME_AUTHORITY_INVALID");
    var lockAttrs = Files.readAttributes(lockPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!lockAttrs.isRegularFile() || !lockAttrs.owner().getName().equals(hostUser)
        || !lockAttrs.permissions().equals(PosixFilePermissions.fromString("rw-------"))) throw new IOException("PROVIDER_RESEED_LOCK_UNAVAILABLE");
    try (var channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
      FileLock lock;
      try { lock = channel.tryLock(); } catch (OverlappingFileLockException active) { throw new IOException("PROVIDER_RESEED_BUSY"); }
      if (lock == null) throw new IOException("PROVIDER_RESEED_BUSY");
      try (lock) {
        if (!Objects.equals(lockAttrs.fileKey(), Files.readAttributes(lockPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey()))
          throw new IOException("PROVIDER_RESEED_LOCK_CHANGED");
        Path archive = state.resolve("provider.pre-reseed-" + UUID.randomUUID());
        Files.move(provider, archive, StandardCopyOption.ATOMIC_MOVE);
        try (var parent = FileChannel.open(state, StandardOpenOption.READ)) { parent.force(true); }
        return archive;
      }
    }
  }
}
