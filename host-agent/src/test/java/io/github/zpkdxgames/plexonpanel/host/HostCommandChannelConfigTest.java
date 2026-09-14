package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostCommandChannelConfigTest {
  @TempDir Path temporary;

  @Test
  void omittedCommandChannelDefaultsToDisabledLoopbackRcon() throws Exception {
    HostConfig loaded = load(base());
    assertFalse(loaded.commandChannel().enabled());
    assertEquals("127.0.0.1", loaded.commandChannel().host());
    assertEquals(25575, loaded.commandChannel().port());
  }

  @Test
  void enabledChannelRequiresLoopbackAndAbsoluteSecretFile() throws Exception {
    JsonObject valid = base();
    valid.add("commandChannel", channel("127.0.0.1", temporary.resolve("rcon.secret").toString()));
    assertTrue(load(valid).commandChannel().enabled());

    JsonObject external = base();
    external.add("commandChannel", channel("0.0.0.0", temporary.resolve("rcon.secret").toString()));
    assertThrows(IllegalArgumentException.class, () -> load(external));

    JsonObject relative = base();
    relative.add("commandChannel", channel("127.0.0.1", "rcon.secret"));
    assertThrows(IllegalArgumentException.class, () -> load(relative));
  }

  @Test
  void inlineRconPasswordIsRejectedRatherThanSilentlyIgnored() throws Exception {
    JsonObject config = base();
    JsonObject channel = channel("127.0.0.1", temporary.resolve("rcon.secret").toString());
    channel.addProperty("password", "must-not-live-in-host-config");
    config.add("commandChannel", channel);

    assertThrows(IllegalArgumentException.class, () -> load(config));
  }

  @Test
  void commandTimeoutAndReadinessBoundsAreValidated() throws Exception {
    JsonObject tooFast = base();
    JsonObject fastChannel = channel("127.0.0.1", temporary.resolve("rcon.secret").toString());
    fastChannel.addProperty("commandTimeoutMillis", 50);
    tooFast.add("commandChannel", fastChannel);
    assertThrows(IllegalArgumentException.class, () -> load(tooFast));

    JsonObject tooLong = base();
    JsonObject longChannel = channel("127.0.0.1", temporary.resolve("rcon.secret").toString());
    longChannel.addProperty("readinessTimeoutSeconds", 3600);
    tooLong.add("commandChannel", longChannel);
    assertThrows(IllegalArgumentException.class, () -> load(tooLong));
  }

  private JsonObject base() throws Exception {
    JsonObject backups = new JsonObject();
    backups.addProperty("enabled", false);
    backups.addProperty("directory", temporary.resolve("backups").toString());
    backups.add("include", new Gson().toJsonTree(List.of("world")));
    backups.addProperty("retentionCount", 3);
    backups.addProperty("intervalMinutes", 0);
    backups.addProperty("maximumBytes", 1_048_576L);
    backups.addProperty("restoreEnabled", false);
    backups.addProperty("rcloneExecutable", "/usr/bin/rclone");
    backups.addProperty("rcloneRemote", "");
    backups.addProperty("rcloneConfig", temporary.resolve("rclone.conf").toString());

    JsonObject config = new JsonObject();
    config.addProperty("serverId", UUID.randomUUID().toString());
    config.addProperty("serverName", "Test");
    config.addProperty("relayUrl", "wss://relay.example/v1/agent");
    config.addProperty(
        "relayPublicKey",
        Base64.getEncoder()
            .encodeToString(
                KeyPairGenerator.getInstance("Ed25519")
                    .generateKeyPair()
                    .getPublic()
                    .getEncoded()));
    config.addProperty("serverRoot", temporary.toString());
    config.addProperty("dataDirectory", temporary.resolve("data").toString());
    config.addProperty("accessRegistry", temporary.resolve("access.json").toString());
    config.addProperty("serviceName", "plexoncraft.service");
    config.add("capabilities", new JsonObject());
    config.add("backups", backups);
    return config;
  }

  private static JsonObject channel(String host, String secretFile) {
    JsonObject channel = new JsonObject();
    channel.addProperty("enabled", true);
    channel.addProperty("host", host);
    channel.addProperty("port", 25575);
    channel.addProperty("secretFile", secretFile);
    channel.addProperty("commandTimeoutMillis", 5000);
    channel.addProperty("readinessTimeoutSeconds", 180);
    return channel;
  }

  private HostConfig load(JsonObject config) throws Exception {
    Path path = temporary.resolve(UUID.randomUUID() + ".json");
    Files.writeString(path, config.toString());
    return HostConfig.load(path);
  }
}
