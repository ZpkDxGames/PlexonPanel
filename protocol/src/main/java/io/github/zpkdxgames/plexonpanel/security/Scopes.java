package io.github.zpkdxgames.plexonpanel.security;

import java.util.*;

/** Canonical protocol 3 scopes. Roles never override local capabilities. */
public final class Scopes {
  private Scopes() {}

  public static final Set<String> ALL =
      Set.of(
          "overview.view",
          "telemetry.view",
          "players.view",
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
          "backup.view",
          "backup.create",
          "backup.download",
          "backup.delete",
          "backup.restore",
          "server.status",
          "server.start",
          "server.stop",
          "server.restart",
          "audit.view.self",
          "audit.view",
          "devices.view",
          "devices.revoke",
          "settings.view");
  public static final Map<String, String> ACTIONS;
  public static final Map<String, Set<String>> ROLES;
  public static final Set<String> HIGH_RISK =
      Set.of(
          "player.ban",
          "player.kill",
          "player.op",
          "player.deop",
          "files.delete",
          "backup.delete",
          "backup.restore",
          "server.stop",
          "server.restart",
          "devices.revoke");

  static {
    Map<String, String> actions = new TreeMap<>();
    for (String s : ALL)
      if (s.startsWith("player.")
          || s.startsWith("files.")
          || s.startsWith("backup.")
          || s.startsWith("server.")) actions.put(s, s);
    actions.put("files.download.chunk", "files.download");
    actions.put("files.transfer.cancel", "files.download");
    actions.put("console.execute", "console.execute.allowed");
    actions.put("chat.global.send", "chat.send");
    actions.put("player.deop", "player.op");
    actions.put("player.whitelist.add", "player.whitelist");
    actions.put("player.whitelist.remove", "player.whitelist");
    actions.put("plugin.command.reload", "plugins.reload");
    actions.put("plugin.view-configs", "plugins.config");
    actions.put("plugin.open-data-folder", "plugins.config");
    actions.put("backup.download.chunk", "backup.download");
    actions.put("backup.download.cancel", "backup.download");
    actions.put("backup.list", "backup.view");
    actions.put("backup.restore.prepare", "backup.restore");
    actions.put("audit.list", "audit.view");
    actions.put("audit.self", "audit.view.self");
    actions.put("devices.list", "devices.view");
    actions.put("devices.revoke", "devices.revoke");
    actions.put("settings.view", "settings.view");
    ACTIONS = Map.copyOf(actions);
    Set<String> observer =
        Set.of(
            "overview.view",
            "telemetry.view",
            "players.view",
            "plugins.view",
            "console.view.errors",
            "audit.view.self",
            "server.status",
            "settings.view");
    Set<String> moderator =
        plus(
            observer,
            "chat.view",
            "chat.send",
            "player.message",
            "player.kick",
            "player.ban",
            "player.unban",
            "player.whitelist");
    Set<String> administrator =
        plus(
            moderator,
            "console.execute.allowed",
            "console.view.full",
            "files.list",
            "files.read",
            "files.write",
            "files.download",
            "backup.view",
            "backup.create",
            "plugins.config",
            "plugins.reload",
            "server.restart",
            "audit.view",
            "devices.view",
            "devices.revoke");
    ROLES =
        Map.of(
            "Observer",
            observer,
            "Moderator",
            moderator,
            "Administrator",
            administrator,
            "Owner",
            ALL);
  }

  private static Set<String> plus(Set<String> base, String... additions) {
    Set<String> result = new TreeSet<>(base);
    result.addAll(List.of(additions));
    return Set.copyOf(result);
  }

  public static Set<String> validate(Collection<String> values) {
    if (values == null || values.size() > ALL.size() || !ALL.containsAll(values))
      throw new IllegalArgumentException("Unknown scope in local role");
    return Set.copyOf(values);
  }

  public static String required(String action) {
    String scope = ACTIONS.get(action);
    if (scope == null) throw new SecurityException("UNKNOWN_ACTION");
    return scope;
  }
}
