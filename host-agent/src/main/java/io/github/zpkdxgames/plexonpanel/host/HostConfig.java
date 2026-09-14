package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.console.ConsoleRedactor;
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
    BackupConfig backups,
    ConsoleConfig console,
    CommandChannelConfig commandChannel) {
  public HostConfig(
      String serverId,
      String serverName,
      String relayUrl,
      String relayPublicKey,
      String serverRoot,
      String dataDirectory,
      String accessRegistry,
      String serviceName,
      Map<String, Boolean> capabilities,
      BackupConfig backups,
      ConsoleConfig console) {
    this(
        serverId,
        serverName,
        relayUrl,
        relayPublicKey,
        serverRoot,
        dataDirectory,
        accessRegistry,
        serviceName,
        capabilities,
        backups,
        console,
        CommandChannelConfig.defaults());
  }

  /** Host-local storage/provider configuration for manual full restore points only. */
  public record BackupConfig(
      boolean enabled,
      String directory,
      boolean restoreEnabled,
      String rcloneExecutable,
      String rcloneRemote,
      String rcloneConfig) {
    /**
     * Source-compatibility constructor for tests/config helpers written before Step 5. Legacy live
     * snapshot fields are deliberately ignored and cannot schedule or authorize a backup.
     */
    public BackupConfig(
        boolean enabled,
        String directory,
        List<String> ignoredInclude,
        int ignoredRetentionCount,
        int ignoredIntervalMinutes,
        long ignoredMaximumBytes,
        boolean restoreEnabled,
        String rcloneExecutable,
        String rcloneRemote,
        String rcloneConfig) {
      this(enabled, directory, restoreEnabled, rcloneExecutable, rcloneRemote, rcloneConfig);
    }

    /** Compatibility for the former liveSnapshotExcludes record shape. */
    public BackupConfig(
        boolean enabled,
        String directory,
        List<String> ignoredInclude,
        int ignoredRetentionCount,
        int ignoredIntervalMinutes,
        long ignoredMaximumBytes,
        boolean restoreEnabled,
        String rcloneExecutable,
        String rcloneRemote,
        String rcloneConfig,
        List<String> ignoredLiveSnapshotExcludes) {
      this(enabled, directory, restoreEnabled, rcloneExecutable, rcloneRemote, rcloneConfig);
    }
  }

  public record ConsoleConfig(
      boolean enabled,
      String source,
      String journalExecutable,
      int initialReplayLines,
      int recentLines,
      int queueCapacity,
      int batchSize,
      long batchIntervalMillis,
      int maximumLineBytes,
      long cursorPersistenceMillis,
      List<String> redactPatterns) {
    static ConsoleConfig defaults() {
      return new ConsoleConfig(
          false,
          "JOURNALD",
          "/usr/bin/journalctl",
          500,
          2500,
          4096,
          100,
          200L,
          8192,
          1000L,
          List.of());
    }
  }

  /** Host-local fixed maintenance command transport. The secret value is never stored here. */
  public record CommandChannelConfig(
      boolean enabled,
      String host,
      int port,
      String secretFile,
      int commandTimeoutMillis,
      int readinessTimeoutSeconds) {
    static CommandChannelConfig defaults() {
      return new CommandChannelConfig(false, "127.0.0.1", 25575, "", 5000, 180);
    }
  }

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
            "backups",
            "console",
            "commandChannel")
        .containsAll(raw.keySet()))
      throw new IllegalArgumentException("Unknown host configuration key");
    if (raw.has("commandChannel")) {
      JsonElement commandRaw = raw.get("commandChannel");
      if (!commandRaw.isJsonObject()
          || !Set.of(
                  "enabled",
                  "host",
                  "port",
                  "secretFile",
                  "commandTimeoutMillis",
                  "readinessTimeoutSeconds")
              .containsAll(commandRaw.getAsJsonObject().keySet()))
        throw new IllegalArgumentException("Unknown Host maintenance command-channel key");
    }

    // Gson ignores retired nested backup members from older host-config files. They are never
    // copied back into the runtime record and therefore cannot re-enable legacy scheduling.
    HostConfig parsed = new Gson().fromJson(raw, HostConfig.class);
    HostConfig c =
        new HostConfig(
            parsed.serverId,
            parsed.serverName,
            parsed.relayUrl,
            parsed.relayPublicKey,
            parsed.serverRoot,
            parsed.dataDirectory,
            parsed.accessRegistry,
            parsed.serviceName,
            parsed.capabilities,
            parsed.backups,
            parsed.console == null ? ConsoleConfig.defaults() : parsed.console,
            parsed.commandChannel == null ? CommandChannelConfig.defaults() : parsed.commandChannel);
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
    if (c.serviceName == null
        || !c.serviceName.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,90}\\.service"))
      throw new IllegalArgumentException("Invalid configured systemd service");
    if (c.capabilities == null || !Scopes.ALL.containsAll(c.capabilities.keySet()))
      throw new IllegalArgumentException("Unknown host scope");
    for (String scope : c.capabilities.keySet())
      if (!scope.startsWith("files.")
          && !scope.startsWith("backup.")
          && !scope.startsWith("maintenance.")
          && !scope.startsWith("provider.")
          && !scope.startsWith("server.")
          && !scope.startsWith("audit.")
          && !scope.startsWith("devices.")
          && !scope.equals("telemetry.view")
          && !scope.equals("settings.view")
          && !scope.equals("console.view.errors")
          && !scope.equals("console.view.full"))
        throw new IllegalArgumentException("Scope is not a host capability");
    for (String value : List.of(c.serverRoot, c.dataDirectory, c.accessRegistry))
      if (!Path.of(value).isAbsolute())
        throw new IllegalArgumentException("Host paths must be absolute");

    BackupConfig b = c.backups;
    if (b == null || b.directory == null || !Path.of(b.directory).isAbsolute())
      throw new IllegalArgumentException("Invalid backup configuration");
    if (b.rcloneRemote != null && !b.rcloneRemote.isBlank()) {
      if (!"/usr/bin/rclone".equals(b.rcloneExecutable)
          || !b.rcloneRemote.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}:[A-Za-z0-9_ /.-]{0,200}")
          || b.rcloneRemote.contains("..")
          || b.rcloneConfig == null
          || !Path.of(b.rcloneConfig).isAbsolute())
        throw new IllegalArgumentException("Invalid locally configured rclone provider");
    }
    validateConsole(c.console);
    validateCommandChannel(c.commandChannel);
    return c;
  }

  private static void validateConsole(ConsoleConfig console) {
    if (!"JOURNALD".equals(console.source))
      throw new IllegalArgumentException("Unsupported Host console source");
    if (!"/usr/bin/journalctl".equals(console.journalExecutable))
      throw new IllegalArgumentException("Host journal executable must be /usr/bin/journalctl");
    if (console.initialReplayLines < 0 || console.initialReplayLines > 5000)
      throw new IllegalArgumentException("Invalid console initial replay size");
    if (console.recentLines < 100 || console.recentLines > 10000)
      throw new IllegalArgumentException("Invalid console recent history size");
    if (console.queueCapacity < 128 || console.queueCapacity > 65536)
      throw new IllegalArgumentException("Invalid console queue capacity");
    if (console.batchSize < 1 || console.batchSize > 100)
      throw new IllegalArgumentException("Invalid console batch size");
    if (console.batchIntervalMillis < 50 || console.batchIntervalMillis > 5000)
      throw new IllegalArgumentException("Invalid console batch interval");
    if (console.maximumLineBytes < 512 || console.maximumLineBytes > 65536)
      throw new IllegalArgumentException("Invalid console maximum line size");
    if (console.cursorPersistenceMillis < 250 || console.cursorPersistenceMillis > 60000)
      throw new IllegalArgumentException("Invalid console cursor persistence interval");
    if (console.redactPatterns == null || console.redactPatterns.size() > 32)
      throw new IllegalArgumentException("Invalid console redaction patterns");
    new ConsoleRedactor(console.redactPatterns);
  }

  private static void validateCommandChannel(CommandChannelConfig channel) {
    if (channel == null
        || !isLoopbackHost(channel.host)
        || channel.port < 1
        || channel.port > 65_535
        || channel.commandTimeoutMillis < 250
        || channel.commandTimeoutMillis > 30_000
        || channel.readinessTimeoutSeconds < 30
        || channel.readinessTimeoutSeconds > 1_800)
      throw new IllegalArgumentException("Invalid Host maintenance command channel");
    if (channel.enabled) {
      if (channel.secretFile == null
          || channel.secretFile.isBlank()
          || !Path.of(channel.secretFile).isAbsolute())
        throw new IllegalArgumentException("RCON secret must use a Host-local absolute file path");
    } else if (channel.secretFile != null
        && !channel.secretFile.isBlank()
        && !Path.of(channel.secretFile).isAbsolute()) {
      throw new IllegalArgumentException("RCON secret file path must be absolute");
    }
  }

  private static boolean isLoopbackWebSocket(URI uri) {
    if (!"ws".equalsIgnoreCase(uri.getScheme())) return false;
    return isLoopbackHost(uri.getHost());
  }

  private static boolean isLoopbackHost(String host) {
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
    for (String s : List.of("console.view.errors", "console.view.full"))
      result.put(s, Boolean.TRUE.equals(result.get(s)) && console.enabled);
    result.put("console.execute.allowed", false);
    return Map.copyOf(result);
  }
}
