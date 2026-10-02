package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Read-only deployment validation; private settings and credential digests never leave memory. */
final class InstanceConfigurationAudit {
  private InstanceConfigurationAudit() {}
  static void validate(HostConfig config, NodeInstanceRegistry.Entry entry) throws IOException {
    Path propertiesPath = Path.of(config.serverRoot()).resolve("server.properties");
    InstanceLayout.rejectSymlinks(propertiesPath);
    if (!Files.isRegularFile(propertiesPath, LinkOption.NOFOLLOW_LINKS) || Files.size(propertiesPath) > 1_048_576)
      throw new IOException("MINECRAFT_PROPERTIES_UNAVAILABLE");
    var properties = new Properties();
    try (var reader = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) { properties.load(reader); }
    validateProperties(properties, config.commandChannel(), entry);
  }
  static void validateProperties(Properties properties, HostConfig.CommandChannelConfig channel, NodeInstanceRegistry.Entry entry) throws IOException {
    if (port(properties.getProperty("server-port", "25565")) != entry.minecraftPort())
      throw new IOException("REGISTERED_MINECRAFT_PORT_MISMATCH");
    String rcon = properties.getProperty("enable-rcon", "false").strip().toLowerCase(Locale.ROOT);
    if (!Set.of("true", "false").contains(rcon) || Boolean.parseBoolean(rcon) != channel.enabled())
      throw new IOException("RCON_ENABLEMENT_MISMATCH");
    if (!channel.enabled()) return;
    if (!Objects.equals(entry.rconPort(), channel.port()) || port(properties.getProperty("rcon.port", "25575")) != channel.port())
      throw new IOException("REGISTERED_RCON_PORT_MISMATCH");
    byte[] secret = secret(Path.of(channel.secretFile()));
    try {
      byte[] configured = properties.getProperty("rcon.password", "").getBytes(StandardCharsets.UTF_8);
      try { if (!MessageDigest.isEqual(secret, configured)) throw new IOException("RCON_SECRET_BINDING_MISMATCH"); }
      finally { Arrays.fill(configured, (byte) 0); }
    } finally { Arrays.fill(secret, (byte) 0); }
  }
  static List<NodeInstanceRegistry.Entry> validateNode(NodeInstanceRegistry.Registry registry) throws IOException, InterruptedException {
    List<Path> credentials = new ArrayList<>();
    for (var entry : registry.instances()) {
      var layout = new InstanceLayout(entry.instanceKey());
      HostConfig config = HostConfig.load(layout.hostConfig());
      if (HostConfigMigration.schemaVersion(layout.hostConfig()) != 5) throw new IOException("FLEET_SCHEMA_REQUIRED");
      registry.require(config.fleetIdentity());
      InstanceFilePolicy.validate(config, layout.hostConfig());
      validate(config, entry);
      if (config.commandChannel().enabled()) credentials.add(layout.rconSecret());
    }
    requireDistinctSecrets(credentials);
    return registry.instances();
  }
  static void requireDistinctSecrets(List<Path> paths) throws IOException {
    Set<String> credentialDigests = new HashSet<>();
    for (Path path : paths) {
      byte[] value = secret(path);
      try {
        String fingerprint = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value));
        if (!credentialDigests.add(fingerprint)) throw new IOException("RCON_SECRET_REUSE_DENIED");
      } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException("CREDENTIAL_VALIDATION_UNAVAILABLE"); }
      finally { Arrays.fill(value, (byte) 0); }
    }
  }
  static byte[] secret(Path path) throws IOException {
    InstanceLayout.rejectSymlinks(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024)
      throw new IOException("RCON_SECRET_INVALID");
    byte[] raw = Files.readAllBytes(path);
    int end = raw.length;
    while (end > 0 && (raw[end-1] == '\r' || raw[end-1] == '\n')) end--;
    if (end < 16) { Arrays.fill(raw, (byte) 0); throw new IOException("RCON_SECRET_TOO_SHORT"); }
    for (int i = 0; i < end; i++) if (raw[i] < 32 || raw[i] > 126) {
      Arrays.fill(raw, (byte) 0); throw new IOException("RCON_SECRET_INVALID");
    }
    byte[] value = Arrays.copyOf(raw, end); Arrays.fill(raw, (byte) 0); return value;
  }
  private static int port(String value) throws IOException {
    try { int port = Integer.parseInt(value.strip()); if (port < 1 || port > 65535) throw new NumberFormatException(); return port; }
    catch (NumberFormatException invalid) { throw new IOException("MINECRAFT_PORT_INVALID"); }
  }
}
