package io.github.zpkdxgames.plexonpanel.config;

import io.github.zpkdxgames.plexonpanel.identity.FleetIdentity;
import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

/** Explicit schema-5 migration. Planning reads disk/defaults without changing identity state. */
public final class FleetConfigMigration {
  private FleetConfigMigration() {}
  public record Plan(int sourceSchema, int targetSchema, String serverId, String nodeId,
      String instanceKey, String serverName, boolean changesRequired) {}
  public static Plan plan(FileConfiguration defaults, Path path, FleetIdentity identity) throws IOException {
    var original = read(path);
    var candidate = candidate(defaults, original, identity);
    return new Plan(marker(original), 5, identity.serverId().toString(), identity.nodeId().toString(),
        identity.instanceKey(), identity.serverName(), !original.saveToString().equals(candidate.saveToString()));
  }
  public static Plan migrate(FileConfiguration defaults, Path path, FleetIdentity identity) throws IOException {
    return migrate(defaults, path, identity, AtomicFiles::writeUtf8);
  }
  static Plan migrate(FileConfiguration defaults, Path path, FleetIdentity identity, ConfigMigration.ConfigWriter writer) throws IOException {
    Plan plan = plan(defaults, path, identity);
    if (!plan.changesRequired()) return plan;
    String source = Files.readString(path);
    var candidate = candidate(defaults, read(path), identity);
    Path backup = path.resolveSibling(path.getFileName() + ".pre-v5-backup");
    if (!Files.readString(path).equals(source)) throw new IOException("PAPER_CONFIG_CHANGED_DURING_MIGRATION");
    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
    else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backup))
      throw new IOException("PAPER_MIGRATION_BACKUP_UNSAFE");
    writer.write(path, candidate.saveToString());
    return plan;
  }
  private static YamlConfiguration read(Path path) throws IOException {
    InstanceLayout.rejectSymlinks(path);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 65_536) throw new IOException("PAPER_CONFIG_INVALID");
    var config = new YamlConfiguration();
    try { config.loadFromString(Files.readString(path)); marker(config); }
    catch (Exception invalid) { throw new IOException("PAPER_CONFIG_INVALID"); }
    return config;
  }
  private static int marker(FileConfiguration config) {
    Object raw = config.get("schema-version", null);
    if (raw == null) return 0;
    int version;
    try { if (!(raw instanceof Number)) throw new ArithmeticException(); version = new java.math.BigDecimal(raw.toString()).intValueExact(); }
    catch (RuntimeException invalid) { throw new IllegalArgumentException("PAPER_SCHEMA_INVALID"); }
    if (version < 0 || version > 5) throw new IllegalArgumentException("PAPER_SCHEMA_UNSUPPORTED");
    return version;
  }
  private static YamlConfiguration candidate(FileConfiguration defaults, YamlConfiguration source, FleetIdentity identity) throws IOException {
    marker(source);
    var config = new YamlConfiguration();
    try { config.loadFromString(source.saveToString()); }
    catch (Exception invalid) { throw new IOException("PAPER_CONFIG_INVALID"); }
    if (source.get("fleet", null) != null &&
        (!identity.nodeId().toString().equals(source.getString("fleet.node-id")) ||
         !identity.instanceKey().equals(source.getString("fleet.instance-key"))))
      throw new IOException("FLEET_BINDING_CHANGE_REQUIRES_OFFLINE_MIGRATION");
    config.setDefaults(defaults.getDefaults() == null ? defaults : defaults.getDefaults());
    config.options().copyDefaults(true);
    config.set("schema-version", 5);
    config.set("fleet.node-id", identity.nodeId().toString());
    config.set("fleet.instance-key", identity.instanceKey());
    config.set("fleet.server-name", identity.serverName());
    try { PanelSettings.load(config); }
    catch (RuntimeException invalid) { throw new IOException("PAPER_MIGRATION_CONFIG_INVALID"); }
    return config;
  }
}
