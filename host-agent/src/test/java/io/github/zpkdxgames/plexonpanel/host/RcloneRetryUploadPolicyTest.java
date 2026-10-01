package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RcloneRetryUploadPolicyTest {
  private static final String ROOT = "gdrive:PlexonCraft";
  @TempDir Path temporary;

  @Test
  void verifiedLocalFilesCanBeUploadedSuccessfullyAfterEarlierProviderFailure() throws Exception {
    Path archive = temporary.resolve("restore-point.zip");
    Path metadata = temporary.resolve("restore-point.json");
    Files.write(archive, new byte[2048]);
    Files.write(metadata, new byte[256]);

    RetryRunner runner = new RetryRunner();
    var provider = new RcloneBackupProvider(config(), runner);
    runner.providerAvailable = false;

    IOException firstFailure =
        assertThrows(
            IOException.class,
            () ->
                provider.uploadAndPromote(
                    archive,
                    metadata,
                    UUID.randomUUID().toString(),
                    "PlexonCraft-Latest.zip",
                    30));
    assertEquals("RCLONE_COMMAND_FAILED", firstFailure.getMessage());
    assertTrue(Files.isRegularFile(archive));
    assertTrue(Files.isRegularFile(metadata));

    runner.providerAvailable = true;
    var retried =
        provider.uploadAndPromote(
            archive,
            metadata,
            UUID.randomUUID().toString(),
            "PlexonCraft-Latest.zip",
            30);

    assertTrue(retried.uploaded());
    assertTrue(retried.verified());
    assertEquals(2048L, runner.remote.get(ROOT + "/PlexonCraft-Latest.zip"));
    assertEquals(256L, runner.remote.get(ROOT + "/PlexonCraft-Latest.json"));
  }

  private static HostConfig.BackupConfig config() {
    return new HostConfig.BackupConfig(
        true,
        "/tmp/plexonpanel-backups",
        List.of("world"),
        1,
        0,
        1024L * 1024L,
        false,
        "/usr/bin/rclone",
        ROOT,
        "/tmp/rclone.conf");
  }

  private static final class RetryRunner implements RcloneBackupProvider.CommandRunner {
    final Map<String, Long> remote = new HashMap<>();
    boolean providerAvailable = true;
    Path archiveSource;

    @Override
    public RcloneBackupProvider.ProcessResult run(List<String> arguments, int timeoutSeconds)
        throws Exception {
      String operation = arguments.get(1);
      return switch (operation) {
        case "copyto" -> copy(arguments);
        case "lsl" -> size(arguments.get(2));
        case "lsf" -> exists(arguments.get(2));
        case "deletefile" -> delete(arguments.get(2));
        case "hashsum" -> hash(arguments.get(3));
        case "lsjson" ->
            providerAvailable
                ? new RcloneBackupProvider.ProcessResult(0, "[]")
                : new RcloneBackupProvider.ProcessResult(1, "provider unavailable");
        default -> new RcloneBackupProvider.ProcessResult(1, "unsupported operation");
      };
    }

    private RcloneBackupProvider.ProcessResult copy(List<String> arguments) throws IOException {
      if (!providerAvailable)
        return new RcloneBackupProvider.ProcessResult(1, "provider unavailable");
      String source = arguments.get(2);
      String destination = arguments.get(3);
      Long bytes = remote.get(source);
      if (bytes == null && !source.startsWith(ROOT + "/")) {
        Path local = Path.of(source);
        if (Files.isRegularFile(local)) {
          bytes = Files.size(local);
          if (source.endsWith(".zip")) archiveSource = local;
        }
      }
      if (bytes == null) return new RcloneBackupProvider.ProcessResult(4, "not found");
      remote.put(destination, bytes);
      return new RcloneBackupProvider.ProcessResult(0, "");
    }

    private RcloneBackupProvider.ProcessResult size(String path) {
      Long bytes = remote.get(path);
      return bytes == null
          ? new RcloneBackupProvider.ProcessResult(4, "not found")
          : new RcloneBackupProvider.ProcessResult(0, bytes + " object");
    }

    private RcloneBackupProvider.ProcessResult hash(String path) throws IOException {
      if (!providerAvailable || archiveSource == null || !remote.containsKey(path))
        return new RcloneBackupProvider.ProcessResult(4, "not found");
      return new RcloneBackupProvider.ProcessResult(
          0, BackupManager.fileHash(archiveSource) + "  " + Path.of(path).getFileName());
    }

    private RcloneBackupProvider.ProcessResult exists(String path) {
      return remote.containsKey(path)
          ? new RcloneBackupProvider.ProcessResult(0, "object")
          : new RcloneBackupProvider.ProcessResult(4, "not found");
    }

    private RcloneBackupProvider.ProcessResult delete(String path) {
      remote.remove(path);
      return new RcloneBackupProvider.ProcessResult(0, "");
    }
  }
}
