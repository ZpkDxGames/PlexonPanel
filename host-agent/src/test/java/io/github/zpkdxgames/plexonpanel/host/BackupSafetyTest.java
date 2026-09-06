package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSafetyTest {
  @TempDir Path temporary;

  BackupManager manager(Path root, long max) throws Exception {
    return new BackupManager(
        new HostConfig(
            UUID.randomUUID().toString(),
            "Test",
            "wss://relay.example/v1/agent",
            "unused",
            root.toString(),
            temporary.resolve("data").toString(),
            temporary.resolve("access.json").toString(),
            "test.service",
            Map.of(),
            new HostConfig.BackupConfig(
                true,
                temporary.resolve("backups").toString(),
                List.of("world", "plugins", "config"),
                3,
                0,
                max,
                true,
                "",
                "",
                "")),
        null,
        null,
        () -> false,
        p -> {},
        new ReentrantLock());
  }

  Path zip(String name, String content) throws Exception {
    Path archive = temporary.resolve(UUID.randomUUID() + ".zip");
    try (var out = new ZipOutputStream(Files.newOutputStream(archive))) {
      out.putNextEntry(new ZipEntry(name));
      out.write(content.getBytes());
      out.closeEntry();
    }
    return archive;
  }

  @Test
  void zipSlipSymlinksSecretsAndOversizedExpansionFailClosed() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    var b = manager(root, 32);
    for (String path :
        List.of(
            "../escape.txt",
            "world/../../escape.txt",
            "/world/data",
            "world/%2e%2e/test",
            "plugins/PlexonPanel/access.json",
            "plugins/MyPlugin/key.pem",
            "world//data")) {
      Path stage = Files.createTempDirectory(root, "stage");
      assertThrows(IOException.class, () -> b.extract(zip(path, "bad"), stage), path);
    }
    assertThrows(
        IOException.class,
        () ->
            b.extract(
                zip("world/level.dat", "x".repeat(100)), Files.createTempDirectory(root, "stage")));
    assertFalse(Files.exists(temporary.resolve("escape.txt")));
  }

  @Test
  void pluginFoldersAndJarFilesAreIndividualRestoreTargets() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    var b = manager(root, 1000);
    assertEquals(
        List.of("plugins/MyPlugin.jar"),
        b.extract(
            zip("plugins/MyPlugin.jar", "jar"), Files.createDirectory(root.resolve("stage1"))));
    assertEquals(
        List.of("plugins/MyPlugin"),
        b.extract(
            zip("plugins/MyPlugin/config.yml", "enabled: true"),
            Files.createDirectory(root.resolve("stage2"))));
  }

  @Test
  void rollbackIsIdempotentAcrossASecondCrash() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server")),
        stage = Files.createDirectory(root.resolve("stage")),
        saved = Files.createDirectory(root.resolve("saved"));
    var b = manager(root, 1000);
    Files.writeString(root.resolve("config"), "new");
    Files.writeString(saved.resolve("config"), "original");
    Files.createDirectory(root.resolve("world"));
    Files.writeString(root.resolve("world/level.dat"), "new-world");
    List<String> targets = List.of("config", "world");
    Set<String> existed = Set.of("config");
    b.rollback(stage, saved, targets, existed);
    assertEquals("original", Files.readString(root.resolve("config")));
    assertFalse(Files.exists(root.resolve("world")));
    b.rollback(stage, saved, targets, existed);
    assertEquals("original", Files.readString(root.resolve("config")));
  }
}
