package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostConfigTest {
  @TempDir Path root;

  JsonObject config() throws Exception {
    var b = new JsonObject();
    b.addProperty("enabled", false);
    b.addProperty("directory", root.resolve("backups").toString());
    b.add("include", new Gson().toJsonTree(List.of("world")));
    b.addProperty("retentionCount", 3);
    b.addProperty("intervalMinutes", 0);
    b.addProperty("maximumBytes", 1048576);
    var c = new JsonObject();
    c.addProperty("serverId", UUID.randomUUID().toString());
    c.addProperty("serverName", "Test");
    c.addProperty("relayUrl", "wss://relay.example/v1/agent");
    c.addProperty(
        "relayPublicKey",
        Base64.getEncoder()
            .encodeToString(
                KeyPairGenerator.getInstance("Ed25519")
                    .generateKeyPair()
                    .getPublic()
                    .getEncoded()));
    c.addProperty("serverRoot", root.toString());
    c.addProperty("dataDirectory", root.resolve("data").toString());
    c.addProperty("accessRegistry", root.resolve("access.json").toString());
    c.addProperty("serviceName", "plexoncraft.service");
    c.add("capabilities", new JsonObject());
    c.add("backups", b);
    return c;
  }

  HostConfig load(JsonObject c) throws Exception {
    Path p = root.resolve("host.json");
    Files.writeString(p, c.toString());
    return HostConfig.load(p);
  }

  @Test
  void defaultsCannotInvokeShellOrUnconfiguredServices() throws Exception {
    assertFalse(load(config()).effectiveCapabilities().get("server.start"));
    for (String service :
        List.of("*", "../ssh.service", "plexoncraft.service;id", "--all", "ssh.service extra")) {
      var c = config();
      c.addProperty("serviceName", service);
      assertThrows(IllegalArgumentException.class, () -> load(c));
    }
    var c = config();
    c.getAsJsonObject("capabilities").addProperty("shell.execute", true);
    assertThrows(IllegalArgumentException.class, () -> load(c));
  }

  @Test
  void rcloneOnlyUsesTheLocallyFixedExecutable() throws Exception {
    var c = config();
    var b = c.getAsJsonObject("backups");
    b.addProperty("rcloneRemote", "offsite:plexon");
    b.addProperty("rcloneExecutable", "/bin/sh");
    b.addProperty("rcloneConfig", root.resolve("rclone.conf").toString());
    assertThrows(IllegalArgumentException.class, () -> load(c));
  }

  @Test
  void daemonRefusesRootBeforeOpeningConfiguredFiles() {
    if (ProcessHandle.current().info().user().orElse("").equals("root"))
      assertThrows(
          SecurityException.class, () -> HostMain.main(new String[] {"/missing-config.json"}));
  }
}
