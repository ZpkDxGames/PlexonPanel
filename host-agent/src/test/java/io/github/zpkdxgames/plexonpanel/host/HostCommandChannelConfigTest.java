package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostCommandChannelConfigTest {
  @TempDir Path root;

  @Test
  void missingCommandChannelDefaultsDisabledAndLoopback() throws Exception {
    HostConfig config = load(base());
    assertFalse(config.commandChannel().enabled());
    assertEquals("127.0.0.1", config.commandChannel().host());
    assertEquals(25575, config.commandChannel().port());
  }

  @Test
  void enabledChannelRequiresAbsoluteSecretAndLoopbackTarget() throws Exception {
    JsonObject relative = base();
    relative.add("commandChannel", channel(true, "127.0.0.1", "relative.secret"));
    assertThrows(IllegalArgumentException.class, () -> load(relative));

    JsonObject remote = base();
    remote.add("commandChannel", channel(true, "0.0.0.0", root.resolve("rcon.secret").toString()));
    assertThrows(IllegalArgumentException.class, () -> load(remote));

    JsonObject valid = base();
    valid.add("commandChannel", channel(true, "localhost", root.resolve("rcon.secret").toString()));
    HostConfig loaded = load(valid);
    assertTrue(loaded.commandChannel().enabled());
    assertEquals("localhost", loaded.commandChannel().host());
  }

  private JsonObject base() throws Exception {
    JsonObject backups = new JsonObject();
    backups.addProperty("enabled", false);
    backups.addProperty("directory", root.resolve("backups").toString());
    backups.add("include", new Gson().toJsonTree(List.of("world")));
    backups.addProperty("retentionCount", 3);
    backups.addProperty("intervalMinutes", 0);
    backups.addProperty("maximumBytes", 1048576);
    backups.addProperty("restoreEnabled", false);
    backups.addProperty("rcloneExecutable", "/usr/bin/rclone");
    backups.addProperty("rcloneRemote", "");
    backups.addProperty("rcloneConfig", root.resolve("rclone.conf").toString());

    JsonObject rootConfig = new JsonObject();
    rootConfig.addProperty("serverId", UUID.randomUUID().toString());
    rootConfig.addProperty("serverName", "Test");
    rootConfig.addProperty("relayUrl", "wss://relay.example/v1/agent");
    rootConfig.addProperty(
        "relayPublicKey",
        Base64.getEncoder()
            .encodeToString(
                KeyPairGenerator.getInstance("Ed25519")
                    .generateKeyPair()
                    .getPublic()
                    .getEncoded()));
    rootConfig.addProperty("serverRoot", root.toString());
    rootConfig.addProperty("dataDirectory", root.resolve("data").toString());
    rootConfig.addProperty("accessRegistry", root.resolve("access.json").toString());
    rootConfig.addProperty("serviceName", "plexoncraft.service");
    rootConfig.add("capabilities", new JsonObject());
    rootConfig.add("backups", backups);
    return rootConfig;
  }

  private JsonObject channel(boolean enabled, String host, String secretFile) {
    JsonObject channel = new JsonObject();
    channel.addProperty("enabled", enabled);
    channel.addProperty("host", host);
    channel.addProperty("port", 25575);
    channel.addProperty("secretFile", secretFile);
    channel.addProperty("commandTimeoutMillis", 2000);
    channel.addProperty("readinessTimeoutMillis", 1000);
    return channel;
  }

  private HostConfig load(JsonObject config) throws Exception {
    Path file = root.resolve(UUID.randomUUID() + ".json");
    Files.writeString(file, config.toString());
    return HostConfig.load(file);
  }
}
