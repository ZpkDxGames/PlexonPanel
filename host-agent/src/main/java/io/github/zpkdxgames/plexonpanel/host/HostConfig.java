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
  /** Backward-compatible constructor for tests and integrations predating the command channel. */
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
      String rcloneConfig,
      List<String> liveSnapshotExcludes) {
    public BackupConfig(
        boolean enabled,
        String directory,
        List<String> include,
        int retentionCount,
        int intervalMinutes,
        long maximumBytes,
        boolean restoreEnabled,
        String rcloneExecutable,
        String rcloneRemote,
        String rcloneConfig) {
      this(
          enabled,
          directory,
          include,
          retentionCount,
          intervalMinutes,
          maximumBytes,
          restoreEnabled,
          rcloneExecutable,
          rcloneRemote,
          rcloneConfig,
          HostConfig.defaultLiveSnapshotExcludes());
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

  public record CommandChannelConfig(
      boolean enabled,
      String host,
      int port,
      String secretFile,
      int commandTimeoutMillis,
      int readinessTimeoutMillis) {
    static CommandChannelConfig defaults() {
      return new CommandChannelConfig(false, "127.0.0.1", 25575, "", 5000, 1500);
    }
  }

  public static List<String> defaultLiveSnapshotExcludes() {
    return List.of("logs", "crash-reports", "cache", ".cache", "tmp", "plugins/spark/tmp");
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
    HostConfig parsed = new Gson().fromJson(raw, HostConfig.class);
    ConsoleConfig console = parsed.console == null ? ConsoleConfig.defaults() : parsed.console;
    CommandChannelConfig commandChannel =
        parsed.commandChannel == null ? CommandChannelConfig.defaults() : parsed.commandChannel;
    BackupConfig backups = parsed.backups;
    if (backups != null && backups.liveSnapshotExcludes == null) {
      BackupConfig b = backups;
      backups =
          new BackupConfig(
              b.enabled,
              b.directory,
              b.include,
              b.retentionCount,
              b.intervalMinutes,
              b.maximumBytes,
              b.restoreEnabled,
              b.rcloneExecutable,
              b.rcloneRemote,
              b.rcloneConfig,
              defaultLiveSnapshotExcludes());
    }
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
            backups,
            console,
            commandChannel);
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
        || b.maximumBytes > 1099511627776L
        || b.liveSnapshotExcludes == null
        || b.liveSnapshotExcludes.size() > 32)
      throw new IllegalArgumentException("Invalid backup configuration");
    if (b.include.stream()
        .anyMatch(
            s ->
                !s.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")
                    || s.equals("..")
                    || s.startsWith(".")))
      throw new IllegalArgumentException(
          "Backup includes must be named top-level files or directories");
    if (b.liveSnapshotExcludes.stream().anyMatch(s -> !validSnapshotExclusion(s)))
      throw new IllegalArgumentException("Invalid live snapshot exclusion");
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

  private static boolean validSnapshotExclusion(String value) {
    if (value == null || value.isBlank() || value.length() > 200 || value.indexOf('\\') >= 0)
      return false;
    if (value.startsWith("/") || value.endsWith("/") || value.contains("//")) return false;
    for (String part : value.split("/"))
      if (part.isBlank()
          || part.equals(".")
          || part.equals("..")
          || !part.matches("[A-Za-z0-9._-]{1,64}")) return false;
    return true;
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
    if (channel == null) throw new IllegalArgumentException("Invalid command channel configuration");
    if (!isLoopbackHost(channel.host))
      throw new IllegalArgumentException("Host command channel must target loopback only");
    if (channel.port < 1 || channel.port > 65535)
      throw new IllegalArgumentException("Invalid Host command-channel port");
    if (channel.commandTimeoutMillis < 250 || channel.commandTimeoutMillis > 30_000)
      throw new IllegalArgumentException("Invalid Host command timeout");
    if (channel.readinessTimeoutMillis < 250 || channel.readinessTimeoutMillis > 30_000)
      throw new IllegalArgumentException("Invalid Host readiness timeout");
    String secret = channel.secretFile == null ? "" : channel.secretFile.trim();
    if (channel.enabled && (secret.isEmpty() || !Path.of(secret).isAbsolute()))
      throw new IllegalArgumentException("Enabled Host command channel requires an absolute secret file");
    if (!secret.isEmpty() && !Path.of(secret).isAbsolute())
      throw new IllegalArgumentException("Host command-channel secret path must be absolute");
  }

  private static boolean isLoopbackWebSocket(URI uri) {
    if (!"ws".equalsIgnoreCase(uri.getScheme())) return false;
    String host = uri.getHost();
    return host != null && isLoopbackHost(host);
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
