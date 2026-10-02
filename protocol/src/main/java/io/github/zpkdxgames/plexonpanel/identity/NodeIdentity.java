package io.github.zpkdxgames.plexonpanel.identity;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** A public immutable node UUID, provisioned once by the administrator and shared by all instances. */
public final class NodeIdentity {
  public static final Path DEFAULT_PATH = Path.of("/etc/plexonpanel/node-id");
  private NodeIdentity() {}

  public static UUID readDefault() throws IOException {
    for (Path part = DEFAULT_PATH; part != null; part = part.getParent()) {
      var attrs = Files.readAttributes(part, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!"root".equals(attrs.owner().getName())
          || attrs.permissions().contains(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE)
          || attrs.permissions().contains(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE))
        throw new IOException("NODE_IDENTITY_OWNERSHIP_INVALID");
    }
    return read(DEFAULT_PATH);
  }

  public static UUID read(Path path) throws IOException {
    rejectSymlinks(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 64)
      throw new IOException("NODE_IDENTITY_UNAVAILABLE");
    try { return FleetIdentity.parseUuid(Files.readString(path).strip(), "nodeId"); }
    catch (IllegalArgumentException invalid) { throw new IOException("NODE_IDENTITY_INVALID"); }
  }

  /** Existing IDs are never regenerated; CREATE_NEW handles concurrent provisioning safely. */
  public static UUID initialize(Path path) throws IOException {
    rejectSymlinks(path);
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return read(path);
    Files.createDirectories(path.toAbsolutePath().getParent());
    UUID candidate = UUID.randomUUID();
    try (var channel = FileChannel.open(path, java.util.Set.of(StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(
            PosixFilePermissions.fromString("rw-r--r--")))) {
      var bytes = StandardCharsets.UTF_8.encode(candidate + "\n");
      while (bytes.hasRemaining()) channel.write(bytes);
      channel.force(true);
    } catch (FileAlreadyExistsException otherInitializer) { return read(path); }
    if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
      try (var parent = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) { parent.force(true); }
    }
    return candidate;
  }

  private static void rejectSymlinks(Path path) throws IOException {
    for (Path part = path.toAbsolutePath(); part != null; part = part.getParent())
      if (Files.isSymbolicLink(part)) throw new IOException("NODE_IDENTITY_SYMLINK_DENIED");
  }
}
