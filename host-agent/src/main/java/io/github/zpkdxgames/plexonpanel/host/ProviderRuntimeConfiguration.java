package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;

/** Seeds isolated writable provider credentials once; refreshed state is never overwritten on restart. */
final class ProviderRuntimeConfiguration {
  @FunctionalInterface interface Writer { void write(Path path, String text) throws IOException; }
  private ProviderRuntimeConfiguration() {}
  static HostConfig prepare(HostConfig configured) throws IOException {
    return prepare(configured, AtomicFiles::writeUtf8);
  }
  static HostConfig prepare(HostConfig configured, Writer writer) throws IOException {
    var backup = configured.backups();
    if (backup.rcloneRemote() == null || backup.rcloneRemote().isBlank()) return configured;
    Path source = Path.of(backup.rcloneConfig());
    Path directory = Path.of(configured.dataDirectory()).resolve("provider");
    String owner = Files.getOwner(Path.of(configured.dataDirectory()), LinkOption.NOFOLLOW_LINKS).getName();
    Path destination = directory.resolve("rclone.conf");
    Path marker = directory.resolve("source-config.sha256");
    for (Path path : List.of(source, directory, destination, marker)) InstanceLayout.rejectSymlinks(path);
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > 131_072)
      throw new IOException("PROVIDER_SOURCE_CONFIG_UNAVAILABLE");
    var sourceBefore = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    byte[] raw = Files.readAllBytes(source);
    try {
      var sourceAfter = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!Objects.equals(sourceBefore.fileKey(), sourceAfter.fileKey()) || sourceBefore.size() != sourceAfter.size()
          || !sourceBefore.lastModifiedTime().equals(sourceAfter.lastModifiedTime())) throw new IOException("PROVIDER_SOURCE_CHANGED_DURING_READ");
      String fingerprint;
      try { fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
      catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("PROVIDER_VALIDATION_UNAVAILABLE"); }
      if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
      var attributes = Files.readAttributes(directory, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isDirectory() || !attributes.owner().getName().equals(owner)
          || !attributes.permissions().equals(PosixFilePermissions.fromString("rwx------"))) throw new IOException("PROVIDER_RUNTIME_AUTHORITY_INVALID");
      boolean stateExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS), markerExists = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
      if (markerExists) {
        requirePrivate(marker, 65, owner);
        if (!Files.readString(marker).strip().equals(fingerprint)) throw new IOException("PROVIDER_SOURCE_CHANGE_REQUIRES_OFFLINE_RESEED");
        if (!stateExists) throw new IOException("PROVIDER_RUNTIME_STATE_INCOMPLETE");
        requirePrivate(destination, 262_144, owner);
      } else {
        if (stateExists) {
          requirePrivate(destination, 262_144, owner);
          byte[] current = Files.readAllBytes(destination);
          try { if (!MessageDigest.isEqual(raw, current)) throw new IOException("PROVIDER_RUNTIME_STATE_INCOMPLETE"); }
          finally { Arrays.fill(current, (byte) 0); }
        } else writer.write(destination, new String(raw, StandardCharsets.UTF_8));
        writer.write(marker, fingerprint + "\n");
        requirePrivate(destination, 262_144, owner); requirePrivate(marker, 65, owner);
      }
    } finally { Arrays.fill(raw, (byte) 0); }
    var runtimeBackup = new HostConfig.BackupConfig(backup.enabled(), backup.directory(), backup.include(), backup.retentionCount(),
        backup.intervalMinutes(), backup.maximumBytes(), backup.restoreEnabled(), backup.rcloneExecutable(), backup.rcloneRemote(),
        destination.toString(), backup.liveSnapshotExcludes());
    return new HostConfig(configured.serverId(), configured.serverName(), configured.relayUrl(), configured.relayPublicKey(),
        configured.serverRoot(), configured.dataDirectory(), configured.accessRegistry(), configured.serviceName(), configured.capabilities(),
        runtimeBackup, configured.console(), configured.commandChannel(), configured.fleet());
  }
  private static void requirePrivate(Path path, int limit, String owner) throws IOException {
    var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile() || attrs.size() > limit || !attrs.owner().getName().equals(owner)
        || !attrs.permissions().equals(PosixFilePermissions.fromString("rw-------"))) throw new IOException("PROVIDER_RUNTIME_AUTHORITY_INVALID");
  }
}
