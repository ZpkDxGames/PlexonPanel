package io.github.zpkdxgames.plexonpanel.identity;

import java.io.IOException;
import java.nio.file.*;
import java.util.Objects;

/** Canonical public layout. Names never select paths or service authorization. */
public record InstanceLayout(String instanceKey) {
  public InstanceLayout { new FleetIdentity(java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"),
      java.util.UUID.fromString("00000000-0000-4000-8000-000000000002"), instanceKey, "Layout"); }
  public Path serverRoot() { return Path.of("/srv/plexonpanel/servers", instanceKey, "server"); }
  public Path hostLauncher() { return Path.of("/srv/plexonpanel/servers", instanceKey, "host"); }
  public Path configDirectory() { return Path.of("/etc/plexonpanel/instances", instanceKey); }
  public Path hostConfig() { return configDirectory().resolve("host-config.json"); }
  public Path stateDirectory() { return Path.of("/var/lib/plexonpanel/instances", instanceKey); }
  public Path backupsDirectory() { return Path.of("/var/backups/plexonpanel/instances", instanceKey); }
  public Path accessRegistry() { return stateDirectory().resolve("access/devices.json"); }
  public Path rconSecret() { return configDirectory().resolve("rcon.secret"); }
  public Path rcloneConfig() { return configDirectory().resolve("rclone.conf"); }
  public String minecraftUnit() { return "minecraft@" + instanceKey + ".service"; }
  public String hostUnit() { return "plexonpanel-host@" + instanceKey + ".service"; }
  public String minecraftUser() { return "mc-" + instanceKey; }
  public String hostUser() { return "pph-" + instanceKey; }
  public String journalNamespace() { return "plexonpanel-" + instanceKey; }

  public static void requirePath(String configured, Path expected) {
    if (configured == null || !Objects.equals(configured, expected.toString()))
      throw new IllegalArgumentException("INSTANCE_PATH_MISMATCH");
  }
  public static void rejectSymlinks(Path path) throws IOException {
    for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent())
      if (Files.isSymbolicLink(part)) throw new IOException("INSTANCE_SYMLINK_DENIED");
  }
}
