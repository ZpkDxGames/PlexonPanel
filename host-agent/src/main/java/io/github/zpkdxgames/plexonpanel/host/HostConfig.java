package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.security.Scopes;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

public record HostConfig(
    String serverId,
    String serverName,
    String relayUrl,
    String relayPublicKey,
    String serverRoot,
    String dataDirectory,
    String accessRegistry,
    String serviceName,
    Map<String, Boolean> capabilities,
    BackupConfig backups) {
  public record BackupConfig(
      boolean enabled,
      String directory,
      List<String> include,
      int retentionCount,
      int intervalMinutes,
      long maximumBytes,
      boolean restoreEnabled,
      String rcloneExecutable,
      String rcloneRemote,
      String rcloneConfig) {}

  public static HostConfig load(Path path) throws java.io.IOException {
    if (Files.size(path) > 65536) throw new java.io.IOException("Host config exceeds limit");
    JsonObject raw = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    if (!Set.of(
            "serverId",
            "serverName",
            "relayUrl",
            "relayPublicKey",
            "serverRoot",
            "dataDirectory",
            "accessRegistry",
            "serviceName",
            "capabilities",
            "backups")
        .containsAll(raw.keySet()))
      throw new IllegalArgumentException("Unknown host configuration key");
    HostConfig c = new Gson().fromJson(raw, HostConfig.class);
    UUID.fromString(c.serverId);
    if (c.serverName == null || c.serverName.length() > 64 || c.serverName.isBlank())
      throw new IllegalArgumentException("Invalid server label");
    URI uri = URI.create(c.relayUrl);
    if ((!"wss".equalsIgnoreCase(uri.getScheme()) && !isLoopbackWebSocket(uri))
        || !"/v1/agent".equals(uri.getPath())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException(
          "Pinned WSS /v1/agent URL required (ws:// is allowed only for loopback)");
    io.github.zpkdxgames.plexonpanel.identity.KeyCodec.decodePublic(c.relayPublicKey);
    if (!c.serviceName.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,90}\\.service"))
      throw new IllegalArgumentException("Invalid configured systemd service");
    if (c.capabilities == null || !Scopes.ALL.containsAll(c.capabilities.keySet()))
      throw new IllegalArgumentException("Unknown host scope");
    for (String scope : c.capabilities.keySet())
      if (!scope.startsWith("files.")
          && !scope.startsWith("backup.")
          && !scope.startsWith("server.")
          && !scope.startsWith("audit.")
          && !scope.startsWith("devices.")
          && !scope.equals("telemetry.view")
          && !scope.equals("settings.view"))
        throw new IllegalArgumentException("Scope is not a host capability");
    for (String value : List.of(c.serverRoot, c.dataDirectory, c.accessRegistry))
      if (!Path.of(value).isAbsolute())
        throw new IllegalArgumentException("Host paths must be absolute");
    var b = c.backups;
    if (b == null
        || b.directory == null
        || !Path.of(b.directory).isAbsolute()
        || b.include == null
        || b.include.size() > 64
        || b.retentionCount < 1
        || b.retentionCount > 1000
        || b.intervalMinutes < 0
        || b.intervalMinutes > 525600
        || b.maximumBytes < 1048576
        || b.maximumBytes > 1099511627776L)
      throw new IllegalArgumentException("Invalid backup configuration");
    if (b.include.stream()
        .anyMatch(
            s ->
                !s.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                    || s.equals("..")
                    || s.startsWith(".")))
      throw new IllegalArgumentException(
          "Backup includes must be named top-level files or directories");
    if (b.rcloneRemote != null && !b.rcloneRemote.isBlank()) {
      if (!"/usr/bin/rclone".equals(b.rcloneExecutable)
          || !b.rcloneRemote.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}:[A-Za-z0-9_ /.-]{0,200}")
          || b.rcloneRemote.contains("..")
          || b.rcloneConfig == null
          || !Path.of(b.rcloneConfig).isAbsolute())
        throw new IllegalArgumentException("Invalid locally configured rclone provider");
    }
    return c;
  }

  private static boolean isLoopbackWebSocket(URI uri) {
    if (!"ws".equalsIgnoreCase(uri.getScheme())) return false;
    String host = uri.getHost();
    if (host == null) return false;
    String normalized = host.toLowerCase(Locale.ROOT);
    return normalized.equals("127.0.0.1")
        || normalized.equals("localhost")
        || normalized.equals("::1")
        || normalized.equals("[::1]");
  }

  public Map<String, Boolean> effectiveCapabilities() {
    Map<String, Boolean> result = new TreeMap<>();
    for (String s : Scopes.ALL) result.put(s, false);
    result.putAll(capabilities);
    for (String s :
        List.of(
            "backup.view", "backup.create", "backup.download", "backup.delete", "backup.restore"))
      result.put(
          s,
          Boolean.TRUE.equals(result.get(s))
              && backups.enabled
              && (!s.equals("backup.restore") || backups.restoreEnabled));
    return Map.copyOf(result);
  }
}
