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
    b.addProperty("restoreEnabled", false);
    b.addProperty("rcloneExecutable", "/usr/bin/rclone");
    b.addProperty("rcloneRemote", "");
    b.addProperty("rcloneConfig", root.resolve("rclone.conf").toString());
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

  Set<String> hostSupported() {
    return Set.of(
        "telemetry.view",
        "server.status",
        "settings.view",
        "audit.view.self",
        "audit.view",
        "devices.view",
        "devices.revoke",
        "server.start",
        "server.stop",
        "server.restart",
        "files.list",
        "files.read",
        "files.download",
        "files.write",
        "files.create",
        "files.rename",
        "files.delete",
        "files.upload",
        "backup.view",
        "backup.create",
        "backup.download",
        "backup.delete",
        "backup.restore");
  }

  JsonObject fullControlConfig() throws Exception {
    JsonObject c = config();
    JsonObject caps = c.getAsJsonObject("capabilities");
    for (String scope : hostSupported()) caps.addProperty(scope, true);
    JsonObject backups = c.getAsJsonObject("backups");
    backups.addProperty("enabled", true);
    backups.addProperty("restoreEnabled", true);
    return c;
  }

  @Test
  void fullControlEnablesEveryHostSupportedScope() throws Exception {
    HostConfig loaded = load(fullControlConfig());
    Map<String, Boolean> capabilities = loaded.effectiveCapabilities();
    for (String scope : hostSupported())
      assertTrue(capabilities.get(scope), () -> "Expected Host capability: " + scope);
  }

  @Test
  void hostStillRejectsPaperOnlyScopes() throws Exception {
    for (String scope :
        List.of(
            "overview.view",
            "players.view",
            "player.op",
            "chat.send",
            "console.execute.allowed",
            "plugins.reload")) {
      JsonObject c = config();
      c.getAsJsonObject("capabilities").addProperty(scope, true);
      assertThrows(IllegalArgumentException.class, () -> load(c), scope);
    }
  }

  @Test
  void loopbackWsIsAllowedForStandaloneRelay() throws Exception {
    for (String relayUrl :
        List.of(
            "ws://127.0.0.1:8787/v1/agent",
            "ws://localhost:8787/v1/agent",
            "ws://[::1]:8787/v1/agent")) {
      JsonObject c = config();
      c.addProperty("relayUrl", relayUrl);
      assertEquals(relayUrl, load(c).relayUrl(), relayUrl);
    }
  }

  @Test
  void plaintextWebSocketRemainsLoopbackOnly() throws Exception {
    for (String relayUrl :
        List.of(
            "ws://relay.example/v1/agent",
            "ws://127.0.0.1.example/v1/agent",
            "ws://0.0.0.0:8787/v1/agent",
            "http://127.0.0.1:8787/v1/agent",
            "ws://127.0.0.1:8787/v1/dashboard",
            "ws://127.0.0.1:8787/v1/agent?token=bad")) {
      JsonObject c = config();
      c.addProperty("relayUrl", relayUrl);
      assertThrows(IllegalArgumentException.class, () -> load(c), relayUrl);
    }
  }

  @Test
  void backupCapabilitiesRemainEffectivelyGated() throws Exception {
    JsonObject disabled = fullControlConfig();
    disabled.getAsJsonObject("backups").addProperty("enabled", false);
    Map<String, Boolean> off = load(disabled).effectiveCapabilities();
    for (String scope :
        List.of(
            "backup.view", "backup.create", "backup.download", "backup.delete", "backup.restore"))
      assertFalse(off.get(scope), scope);

    JsonObject noRestore = fullControlConfig();
    noRestore.getAsJsonObject("backups").addProperty("restoreEnabled", false);
    Map<String, Boolean> partial = load(noRestore).effectiveCapabilities();
    assertTrue(partial.get("backup.create"));
    assertTrue(partial.get("backup.view"));
    assertFalse(partial.get("backup.restore"));
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
    assertThrows(IllegalArgumentException.class, () -> load(c));
  }

  @Test
  void daemonRefusesRootBeforeOpeningConfiguredFiles() {
    if (ProcessHandle.current().info().user().orElse("").equals("root"))
      assertThrows(
          SecurityException.class, () -> HostMain.main(new String[] {"/missing-config.json"}));
  }
}
