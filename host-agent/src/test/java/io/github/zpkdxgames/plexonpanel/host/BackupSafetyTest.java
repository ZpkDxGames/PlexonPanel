package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupSafetyTest {
  @TempDir Path temporary;

  private static String relayPublicKey() throws Exception {
    return Base64.getEncoder()
        .encodeToString(KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded());
  }

  BackupManager manager(Path root, long max) throws Exception {
    return new BackupManager(
        new HostConfig(
            UUID.randomUUID().toString(),
            "Test Server",
            "ws://127.0.0.1/v1/agent",
            relayPublicKey(),
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
                "",
                HostConfig.defaultLiveSnapshotExcludes()),
            HostConfig.ConsoleConfig.defaults(),
            HostConfig.MaintenanceCommandConfig.defaults()),
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
  void snapshotPolicyExcludesVolatileRuntimeTreesWithoutExcludingDurablePluginConfig() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("policy-server"));
    HostConfig config =
        new HostConfig(
            UUID.randomUUID().toString(),
            "Test Server",
            "ws://127.0.0.1/v1/agent",
            relayPublicKey(),
            root.toString(),
            temporary.resolve("policy-data").toString(),
            temporary.resolve("policy-access.json").toString(),
            "test.service",
            Map.of(),
            new HostConfig.BackupConfig(
                true,
                temporary.resolve("policy-backups").toString(),
                List.of("world", "plugins", "config"),
                3,
                0,
                1024 * 1024,
                true,
                "",
                "",
                "",
                HostConfig.defaultLiveSnapshotExcludes()),
            HostConfig.ConsoleConfig.defaults(),
            HostConfig.MaintenanceCommandConfig.defaults());
    assertTrue(LiveSnapshotPolicy.volatileExcluded(config, "plugins/spark/tmp/profile-1.tmp"));
    assertFalse(LiveSnapshotPolicy.volatileExcluded(config, "plugins/PlexonChats/config.yml"));
    assertTrue(LiveSnapshotPolicy.allowed(config, "plugins/PlexonChats/config.yml"));
    assertFalse(LiveSnapshotPolicy.allowed(config, "plugins/PlexonChats/database.db"));
  }
}
