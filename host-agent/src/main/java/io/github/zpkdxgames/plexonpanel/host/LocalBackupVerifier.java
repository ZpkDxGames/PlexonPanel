package io.github.zpkdxgames.plexonpanel.host;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Local integrity gate applied before any full-backup remote upload. */
final class LocalBackupVerifier {
  record Result(String sha256, int entryCount, long expandedBytes) {}

  private static final int ENTRY_LIMIT = 500_000;

  private LocalBackupVerifier() {}

  static Result verify(
      Path archive, String expectedSha256, int expectedEntries, long maximumExpandedBytes)
      throws IOException {
    Objects.requireNonNull(archive, "archive");
    if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(archive))
      throw new IOException("LOCAL_BACKUP_ARCHIVE_INVALID");
    if (maximumExpandedBytes < 1) throw new IllegalArgumentException("maximumExpandedBytes");

    String actualHash = BackupManager.fileHash(archive);
    if (expectedSha256 == null || expectedSha256.isBlank() || !actualHash.equals(expectedSha256))
      throw new IOException("LOCAL_BACKUP_HASH_MISMATCH");

    try (ZipFile central = new ZipFile(archive.toFile())) {
      if (central.size() != expectedEntries)
        throw new IOException("LOCAL_BACKUP_ENTRY_COUNT_MISMATCH");
    } catch (ZipException malformed) {
      throw new IOException("LOCAL_BACKUP_STRUCTURE_INVALID", malformed);
    }

    Set<String> names = new HashSet<>();
    long expanded = 0L;
    int entries = 0;
    try (ZipInputStream zip =
        new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
      ZipEntry entry;
      byte[] buffer = new byte[64 * 1024];
      while ((entry = zip.getNextEntry()) != null) {
        String name = entry.getName();
        validateName(name);
        if (!names.add(name) || ++entries > ENTRY_LIMIT)
          throw new IOException("LOCAL_BACKUP_STRUCTURE_INVALID");
        if (!entry.isDirectory()) {
          int count;
          while ((count = zip.read(buffer)) >= 0) {
            try {
              expanded = Math.addExact(expanded, count);
            } catch (ArithmeticException overflow) {
              throw new IOException("LOCAL_BACKUP_SIZE_INVALID", overflow);
            }
            if (expanded > maximumExpandedBytes)
              throw new IOException("LOCAL_BACKUP_SIZE_INVALID");
          }
        }
        zip.closeEntry();
      }
    } catch (ZipException malformed) {
      throw new IOException("LOCAL_BACKUP_STRUCTURE_INVALID", malformed);
    }

    if (entries != expectedEntries) throw new IOException("LOCAL_BACKUP_ENTRY_COUNT_MISMATCH");
    return new Result(actualHash, entries, expanded);
  }

  private static void validateName(String name) throws IOException {
    if (name == null
        || name.isBlank()
        || name.length() > 4096
        || name.startsWith("/")
        || name.indexOf('\\') >= 0
        || name.contains("//")
        || name.chars().anyMatch(Character::isISOControl))
      throw new IOException("LOCAL_BACKUP_STRUCTURE_INVALID");
    Path relative = Path.of(name);
    if (relative.isAbsolute() || !relative.normalize().equals(relative) || relative.getNameCount() < 1)
      throw new IOException("LOCAL_BACKUP_STRUCTURE_INVALID");
    for (Path part : relative)
      if (part.toString().equals(".")
          || part.toString().equals("..")
          || part.toString().contains(":"))
        throw new IOException("LOCAL_BACKUP_STRUCTURE_INVALID");
  }
}
