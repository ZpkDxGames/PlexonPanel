package io.github.zpkdxgames.plexonpanel.config;

import io.github.zpkdxgames.plexonpanel.files.*;
import io.github.zpkdxgames.plexonpanel.security.Scopes;
import java.nio.file.*;
import java.util.*;
import org.bukkit.configuration.file.FileConfiguration;

public record ControlPolicy(
    Map<String, Boolean> capabilities,
    Map<String, Set<String>> roles,
    String defaultRole,
    int credentialDays,
    String consoleMode,
    Map<String, String> pluginReloads,
    SafeFiles files,
    String hostPublicKey) {
  public static ControlPolicy load(FileConfiguration config, PanelSettings settings, Path data)
      throws java.io.IOException {
    Map<String, Boolean> c = new TreeMap<>();
    for (String s : Scopes.ALL) c.put(s, false);
    for (String s : List.of("overview.view", "telemetry.view", "players.view", "plugins.view"))
      c.put(s, settings.telemetry().enabled());
    c.put("players.history.view", settings.playerHistory().enabled());
    c.put(
        "players.location",
        settings.telemetry().enabled() && settings.telemetry().includePlayerLocation());
    c.put(
        "players.address",
        settings.telemetry().enabled() && settings.telemetry().includePlayerAddress());
    c.put("console.view.errors", settings.console().errorsEnabled());
    c.put("console.view.full", settings.console().streamEnabled());
    c.put("chat.view", settings.chat().streamEnabled());
    boolean remote = settings.remoteActions().enabled();
    c.put("chat.send", remote && settings.chat().allowDashboardSend());
    c.put(
        "chat.send.minimessage",
        remote
            && settings.chat().allowDashboardSend()
            && settings.chat().allowMiniMessageFromDashboard());
    String mode = config.getString("remote-actions.console.mode", "ALLOWLIST");
    if (!Set.of("DISABLED", "ALLOWLIST", "ALLOWLIST_WITH_CONFIRMATION").contains(mode))
      throw new IllegalArgumentException("Invalid console policy mode");
    c.put(
        "console.execute.allowed",
        remote && settings.remoteActions().console().enabled() && !mode.equals("DISABLED"));
    for (String action :
        List.of(
            "message",
            "kick",
            "ban",
            "unban",
            "whitelist",
            "teleport",
            "gamemode",
            "heal",
            "feed",
            "kill",
            "op"))
      c.put(
          "player." + action,
          remote
              && config.getBoolean(
                  "remote-actions.players." + action, Set.of("message", "kick").contains(action)));
    for (String scope :
        List.of("audit.view", "audit.view.self", "devices.view", "settings.view", "server.status"))
      c.put(scope, true);
    c.put("backup.create", remote && config.getBoolean("backups.enabled", false));
    c.put("devices.revoke", remote && config.getBoolean("access.allow-dashboard-revoke", false));
    Map<String, Set<String>> roles = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    roles.putAll(Scopes.ROLES);
    var custom = config.getConfigurationSection("access.roles");
    if (custom != null)
      for (String name : custom.getKeys(false)) {
        if (roles.containsKey(name) || !name.matches("[A-Za-z][A-Za-z0-9_-]{0,31}"))
          throw new IllegalArgumentException("Invalid or reserved custom role: " + name);
        roles.put(name, Scopes.validate(custom.getStringList(name + ".scopes")));
      }
    String defaultRole = config.getString("access.default-pair-role", "Observer");
    if (!roles.containsKey(defaultRole))
      throw new IllegalArgumentException("Unknown default pairing role");
    int days = config.getInt("access.device-credential-days", 30);
    if (days < 1 || days > 30)
      throw new IllegalArgumentException("Credential lifetime must be 1–30 days");
    SafeFiles files = null;
    if (config.getBoolean("files.enabled", false)) {
      Map<String, Path> roots = new TreeMap<>();
      Set<String> writable = new HashSet<>();
      var section = config.getConfigurationSection("files.roots");
      if (section != null)
        for (String name : section.getKeys(false)) {
          if (section.getBoolean(name + ".read", true))
            roots.put(name, Path.of(section.getString(name + ".path", "/opt/plexoncraft/server")));
          if (section.getBoolean(name + ".write", false)) writable.add(name);
        }
      if (roots.isEmpty()) roots.put("server", Path.of("/opt/plexoncraft/server"));
      files = new SafeFiles(new PathPolicy(roots, List.of(data)), writable);
      for (String action :
          List.of("list", "read", "download", "write", "create", "rename", "delete", "upload"))
        c.put(
            "files." + action,
            config.getBoolean(
                    "files.permissions." + action,
                    Set.of("list", "read", "download").contains(action))
                && (Set.of("list", "read", "download").contains(action) || remote));
      c.put("plugins.config", Boolean.TRUE.equals(c.get("files.read")));
    }
    Map<String, String> reloads = new TreeMap<>();
    var reloadConfig = config.getConfigurationSection("remote-actions.plugin-reload-commands");
    if (reloadConfig != null)
      for (String key : reloadConfig.getKeys(false))
        reloads.put(key, reloadConfig.getString(key, ""));
    c.put(
        "plugins.reload",
        remote && !reloads.isEmpty() && Boolean.TRUE.equals(c.get("console.execute.allowed")));
    return new ControlPolicy(
        Map.copyOf(c),
        Collections.unmodifiableMap(roles),
        defaultRole,
        days,
        mode,
        Map.copyOf(reloads),
        files,
        config.getString("host.public-key", ""));
  }

  public String roleName(String requested) {
    return roles.keySet().stream()
        .filter(n -> n.equalsIgnoreCase(requested))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown role"));
  }
}
