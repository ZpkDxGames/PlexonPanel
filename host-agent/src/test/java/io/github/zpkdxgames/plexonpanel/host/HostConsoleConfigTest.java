package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostConsoleConfigTest {
  @TempDir Path root;

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
    config.addProperty("serverRoot", root.toString());
    config.addProperty("dataDirectory", root.resolve("data").toString());
    config.addProperty("accessRegistry", root.resolve("access.json").toString());
    config.addProperty("serviceName", "plexoncraft.service");
    config.add("capabilities", new JsonObject());
    config.add("backups", backups);
    return config;
  }

  private JsonObject console(boolean enabled) {
    JsonObject console = new JsonObject();
    console.addProperty("enabled", enabled);
    console.addProperty("source", "JOURNALD");
    console.addProperty("journalExecutable", "/usr/bin/journalctl");
    console.addProperty("initialReplayLines", 500);
    console.addProperty("recentLines", 2500);
    console.addProperty("queueCapacity", 4096);
    console.addProperty("batchSize", 100);
    console.addProperty("batchIntervalMillis", 200);
    console.addProperty("maximumLineBytes", 8192);
    console.addProperty("cursorPersistenceMillis", 1000);
    console.add("redactPatterns", new Gson().toJsonTree(List.of("session-[A-Za-z0-9]+")));
    return console;
  }

  private HostConfig load(JsonObject config) throws Exception {
    Path path = root.resolve("host.json");
    Files.writeString(path, config.toString());
    return HostConfig.load(path);
  }

  @Test
  void omittedConsoleMigratesToDisabledSafeDefaults() throws Exception {
    HostConfig loaded = load(base());
    assertFalse(loaded.console().enabled());
    assertEquals("JOURNALD", loaded.console().source());
    assertEquals("/usr/bin/journalctl", loaded.console().journalExecutable());
    assertEquals(500, loaded.console().initialReplayLines());
    assertEquals(2500, loaded.console().recentLines());
    assertFalse(loaded.effectiveCapabilities().get("console.view.errors"));
    assertFalse(loaded.effectiveCapabilities().get("console.view.full"));
    assertFalse(loaded.effectiveCapabilities().get("console.execute.allowed"));
  }

  @Test
  void enabledConsoleGatesViewScopesButNeverCommandExecution() throws Exception {
    JsonObject config = base();
    config.add("console", console(true));
    JsonObject capabilities = config.getAsJsonObject("capabilities");
    capabilities.addProperty("console.view.errors", true);
    capabilities.addProperty("console.view.full", true);

    Map<String, Boolean> effective = load(config).effectiveCapabilities();
    assertTrue(effective.get("console.view.errors"));
    assertTrue(effective.get("console.view.full"));
    assertFalse(effective.get("console.execute.allowed"));
  }

  @Test
  void disabledConsoleSuppressesConfiguredViewScopes() throws Exception {
    JsonObject config = base();
    config.add("console", console(false));
    JsonObject capabilities = config.getAsJsonObject("capabilities");
    capabilities.addProperty("console.view.errors", true);
    capabilities.addProperty("console.view.full", true);

    Map<String, Boolean> effective = load(config).effectiveCapabilities();
    assertFalse(effective.get("console.view.errors"));
    assertFalse(effective.get("console.view.full"));
  }

  @Test
  void consoleSourceAndExecutableAreFailClosed() throws Exception {
    JsonObject wrongSource = base();
    JsonObject source = console(true);
    source.addProperty("source", "FILE");
    wrongSource.add("console", source);
    assertThrows(IllegalArgumentException.class, () -> load(wrongSource));

    JsonObject wrongExecutable = base();
    JsonObject executable = console(true);
    executable.addProperty("journalExecutable", "/bin/sh");
    wrongExecutable.add("console", executable);
    assertThrows(IllegalArgumentException.class, () -> load(wrongExecutable));
  }

  @Test
  void consoleBoundsAndRedactionPatternsAreValidated() throws Exception {
    JsonObject oversized = base();
    JsonObject console = console(true);
    console.addProperty("queueCapacity", 65537);
    oversized.add("console", console);
    assertThrows(IllegalArgumentException.class, () -> load(oversized));

    JsonObject invalidPattern = base();
    JsonObject redact = console(true);
    redact.add("redactPatterns", new Gson().toJsonTree(List.of("[")));
    invalidPattern.add("console", redact);
    assertThrows(IllegalArgumentException.class, () -> load(invalidPattern));
  }
}
