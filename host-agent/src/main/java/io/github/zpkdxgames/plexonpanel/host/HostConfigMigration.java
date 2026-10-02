package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import io.github.zpkdxgames.plexonpanel.identity.FleetIdentity;
import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import io.github.zpkdxgames.plexonpanel.identity.NodeIdentity;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;

/** Adds the Host configuration schema marker without rewriting identities or operator keys. */
final class HostConfigMigration {
  private HostConfigMigration() {}

  static boolean needsMigration(Path configPath) throws IOException {
    return schema(read(configPath)) < HostConfig.CURRENT_SCHEMA_VERSION;
  }
  static int schemaVersion(Path configPath) throws IOException { return schema(read(configPath)); }

  static void migrate(Path configPath) throws IOException {
    JsonObject raw = read(configPath);
    int schema = schema(raw);
    if (schema >= HostConfig.CURRENT_SCHEMA_VERSION) return;
    PosixFileAttributes original =
        Files.readAttributes(configPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

    Path backup = configPath.resolveSibling(configPath.getFileName() + ".pre-v4-backup");
    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
      Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
    } else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(backup)) {
      throw new IOException("Refusing to replace an unsafe Host config migration backup");
    }

    raw.addProperty("schemaVersion", HostConfig.CURRENT_SCHEMA_VERSION);
    AtomicFiles.writeUtf8(
        configPath, new GsonBuilder().setPrettyPrinting().create().toJson(raw) + "\n");
    Files.setOwner(configPath, original.owner());
    PosixFileAttributeView attributes =
        Files.getFileAttributeView(configPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    attributes.setGroup(original.group());
    attributes.setPermissions(original.permissions());
  }

  record FleetPlan(int sourceSchema, int targetSchema, String serverId, String nodeId, String instanceKey,
      String serviceName, String backupName, boolean changesRequired) {}

  static FleetPlan planFleet(Path configPath, UUID nodeId, String instanceKey) throws IOException {
    JsonObject raw = read(configPath);
    InstanceLayout layout = new InstanceLayout(instanceKey);
    InstanceLayout.requirePath(configPath.toAbsolutePath().normalize().toString(), layout.hostConfig());
    return planFleet(raw, nodeId, instanceKey);
  }

  static FleetPlan planFleet(JsonObject raw, UUID nodeId, String instanceKey) {
    JsonObject target = fleetCandidate(raw, nodeId, instanceKey);
    HostConfig config = HostConfig.fromJson(target);
    return new FleetPlan(schema(raw), 5, config.serverId(), nodeId.toString(), instanceKey,
        config.serviceName(), "host-config.json.pre-v5-backup", !raw.equals(target));
  }

  static void migrateFleet(Path configPath, UUID nodeId, String instanceKey) throws IOException {
    FleetPlan plan = planFleet(configPath, nodeId, instanceKey);
    if (!plan.changesRequired()) return;
    JsonObject target = fleetCandidate(read(configPath), nodeId, instanceKey);
    publishFleet(configPath, target, AtomicFiles::writeUtf8);
  }

  @FunctionalInterface interface Writer { void write(Path path, String text) throws IOException; }
  static void publishFleet(Path configPath, JsonObject target, Writer writer) throws IOException {
    HostConfig.fromJson(target); // Validate before creating any rollback file or replacing the marker.
    InstanceLayout.rejectSymlinks(configPath);
    PosixFileAttributes original = Files.readAttributes(configPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Path backup = configPath.resolveSibling(configPath.getFileName() + ".pre-v5-backup");
    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
    else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backup)) throw new IOException("HOST_MIGRATION_BACKUP_UNSAFE");
    writer.write(configPath, new GsonBuilder().setPrettyPrinting().create().toJson(target) + "\n");
    var attributes = Files.getFileAttributeView(configPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    attributes.setOwner(original.owner()); attributes.setGroup(original.group()); attributes.setPermissions(original.permissions());
  }

  static JsonObject fleetCandidate(JsonObject raw, UUID nodeId, String instanceKey) {
    schema(raw);
    JsonObject target = raw.deepCopy();
    FleetIdentity identity = FleetIdentity.parse(target.get("serverId").getAsString(), nodeId.toString(), instanceKey, target.get("serverName").getAsString());
    if (target.has("fleet")) {
      var old = new com.google.gson.Gson().fromJson(target.get("fleet"), HostConfig.FleetConfig.class);
      if (old == null || !identity.nodeId().toString().equals(old.nodeId()) || !identity.instanceKey().equals(old.instanceKey()))
        throw new IllegalArgumentException("FLEET_BINDING_CHANGE_REQUIRES_OFFLINE_MIGRATION");
    }
    JsonObject fleet = new JsonObject(); fleet.addProperty("nodeId", identity.nodeId().toString()); fleet.addProperty("instanceKey", identity.instanceKey());
    target.add("fleet", fleet); target.addProperty("schemaVersion", 5);
    HostConfig.fromJson(target);
    return target;
  }

  private static JsonObject read(Path configPath) throws IOException {
    InstanceLayout.rejectSymlinks(configPath);
    if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(configPath))
      throw new IOException("Host config must be a regular non-symlink file");
    if (Files.size(configPath) > 65_536L) throw new IOException("Host config exceeds limit");

    JsonObject raw;
    try {
      raw = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
    } catch (RuntimeException error) {
      throw new IOException("Host config is not a valid JSON object");
    }
    schema(raw);
    return raw;
  }

  private static int schema(JsonObject raw) {
    if (raw.has("schemaVersion")
        && (!raw.get("schemaVersion").isJsonPrimitive()
            || !raw.get("schemaVersion").getAsJsonPrimitive().isNumber()
            || raw.get("schemaVersion").getAsBigDecimal().stripTrailingZeros().scale() > 0))
      throw new IllegalArgumentException("Host configuration schemaVersion must be an integer");
    int schema;
    try { schema = raw.has("schemaVersion") ? raw.get("schemaVersion").getAsBigDecimal().intValueExact() : 0; }
    catch (RuntimeException invalid) { throw new IllegalArgumentException("Unsupported Host configuration schemaVersion"); }
    if (schema < 0 || schema > 5)
      throw new IllegalArgumentException("Unsupported Host configuration schemaVersion " + schema);
    return schema;
  }
}
