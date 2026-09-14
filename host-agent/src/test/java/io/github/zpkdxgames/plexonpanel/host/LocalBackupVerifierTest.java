package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalBackupVerifierTest {
  @TempDir Path temporary;

  @Test
  void verifiedZipRequiresMatchingHashAndEntryCount() throws Exception {
    Path archive = zip("world/level.dat", "level-data");
    String hash = BackupManager.fileHash(archive);

    LocalBackupVerifier.Result result = LocalBackupVerifier.verify(archive, hash, 1, 1024);

    assertEquals(hash, result.sha256());
    assertEquals(1, result.entryCount());
    assertEquals("level-data".getBytes().length, result.expandedBytes());
  }

  @Test
  void hashMismatchFailsBeforeRemoteEligibility() throws Exception {
    Path archive = zip("world/level.dat", "level-data");
    IOException failure =
        assertThrows(
            IOException.class,
            () -> LocalBackupVerifier.verify(archive, "0".repeat(64), 1, 1024));
    assertEquals("LOCAL_BACKUP_HASH_MISMATCH", failure.getMessage());
  }

  @Test
  void entryCountMismatchFailsClosed() throws Exception {
    Path archive = zip("world/level.dat", "level-data");
    String hash = BackupManager.fileHash(archive);
    IOException failure =
        assertThrows(IOException.class, () -> LocalBackupVerifier.verify(archive, hash, 2, 1024));
    assertEquals("LOCAL_BACKUP_ENTRY_COUNT_MISMATCH", failure.getMessage());
  }

  @Test
  void truncatedZipFailsStructuralVerification() throws Exception {
    Path archive = zip("world/level.dat", "level-data".repeat(50));
    byte[] content = Files.readAllBytes(archive);
    Files.write(archive, java.util.Arrays.copyOf(content, Math.max(1, content.length / 2)));
    String hash = BackupManager.fileHash(archive);

    assertThrows(IOException.class, () -> LocalBackupVerifier.verify(archive, hash, 1, 100_000));
  }

  private Path zip(String name, String content) throws Exception {
    Path archive = temporary.resolve("backup.zip");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry(name));
      output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return archive;
  }
}
