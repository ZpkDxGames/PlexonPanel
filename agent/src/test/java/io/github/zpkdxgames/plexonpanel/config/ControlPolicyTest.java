package io.github.zpkdxgames.plexonpanel.config;

import static org.junit.jupiter.api.Assertions.*;

import io.github.zpkdxgames.plexonpanel.security.Scopes;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlPolicyTest {
  @TempDir Path temporary;

  private Path fixture(String local, String rootRelative) {
    Path localPath = Path.of(local);
    if (Files.isRegularFile(localPath)) return localPath;
    Path rootPath = Path.of(rootRelative);
    if (Files.isRegularFile(rootPath)) return rootPath;
    throw new IllegalStateException("Missing test fixture: " + local + " / " + rootRelative);
  }

  private ControlPolicy load(Path source) throws Exception {
    YamlConfiguration config = YamlConfiguration.loadConfiguration(source.toFile());
    if (config.getBoolean("files.enabled", false)) {
      Path serverRoot = temporary.resolve("server");
      Files.createDirectories(serverRoot);
      config.set("files.roots.server.path", serverRoot.toString());
    }
    return ControlPolicy.load(config, PanelSettings.load(config), temporary.resolve("panel-data"));
  }

  @Test
  void fullControlPresetEnablesEveryPaperSupportedScopeOnly() throws Exception {
    ControlPolicy policy =
        load(fixture("examples/config-full-control.yml", "agent/examples/config-full-control.yml"));

    Set<String> paperSupported =
        Set.of(
            "overview.view",
            "telemetry.view",
            "players.view",
            "players.history.view",
            "players.location",
            "players.address",
            "console.view.errors",
            "console.view.full",
            "console.execute.allowed",
            "chat.view",
            "chat.send",
            "chat.send.minimessage",
            "player.message",
            "player.kick",
            "player.ban",
            "player.unban",
            "player.whitelist",
            "player.teleport",
            "player.gamemode",
            "player.heal",
            "player.feed",
            "player.kill",
            "player.op",
            "plugins.view",
            "plugins.config",
            "plugins.reload",
            "files.list",
            "files.read",
            "files.write",
            "files.create",
            "files.rename",
            "files.delete",
            "files.download",
            "files.upload",
            "backup.create",
            "server.status",
            "audit.view.self",
            "audit.view",
            "devices.view",
            "devices.revoke",
            "settings.view");

    for (String scope : paperSupported)
      assertTrue(policy.capabilities().get(scope), () -> "Expected Paper capability: " + scope);
    for (String scope : Scopes.ALL)
      if (!paperSupported.contains(scope))
        assertFalse(
            policy.capabilities().get(scope), () -> "Paper must not claim Host-only scope: " + scope);

    assertEquals("plexonpanel reload", policy.pluginReloads().get("PlexonPanel"));
  }

  @Test
  void pluginReloadIsAdvertisedOnlyWhenItsConfiguredCommandIsActuallyAllowed() throws Exception {
    Path source = fixture("examples/config-full-control.yml", "agent/examples/config-full-control.yml");
    YamlConfiguration config = YamlConfiguration.loadConfiguration(source.toFile());
    Path serverRoot = temporary.resolve("server");
    Files.createDirectories(serverRoot);
    config.set("files.roots.server.path", serverRoot.toString());
    config.set("remote-actions.console.allow", List.of("^tps$"));

    ControlPolicy policy =
        ControlPolicy.load(config, PanelSettings.load(config), temporary.resolve("panel-data"));
    assertTrue(policy.capabilities().get("console.execute.allowed"));
    assertFalse(policy.capabilities().get("plugins.reload"));
  }

  @Test
  void conservativePublicDefaultsRemainConservative() throws Exception {
    ControlPolicy policy =
        load(
            fixture(
                "src/main/resources/config.yml", "agent/src/main/resources/config.yml"));

    for (String scope :
        List.of(
            "console.execute.allowed",
            "chat.send",
            "chat.send.minimessage",
            "player.ban",
            "player.op",
            "files.write",
            "files.delete",
            "backup.create",
            "devices.revoke",
            "plugins.reload",
            "server.start",
            "server.stop",
            "server.restart"))
      assertFalse(policy.capabilities().get(scope), () -> "Public default became dangerous: " + scope);
  }
}
