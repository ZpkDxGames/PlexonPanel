package io.github.zpkdxgames.plexonpanel.host;

import java.util.Locale;

/** Shared security and volatility classification for live snapshots and diagnostics. */
final class LiveSnapshotPolicy {
  private LiveSnapshotPolicy() {}

  static boolean allowed(HostConfig config, String name) {
    String normalized = normalize(name);
    String[] parts = normalized.split("/", -1);
    if (parts.length == 0 || !config.backups().include().contains(parts[0])) return false;
    for (String part : parts) {
      String low = part.toLowerCase(Locale.ROOT);
      if (low.equals("..")
          || low.equals(".")
          || low.startsWith(".")
          || low.equals("plexonpanel")
          || low.equals("plexonpanel-host")
          || low.equals("logs")
          || low.contains("rclone")
          || low.endsWith(".key")
          || low.endsWith(".pem")
          || low.endsWith(".db")
          || low.endsWith(".sqlite")
          || low.endsWith(".mv.db")) return false;
    }
    return true;
  }

  static boolean volatileExcluded(HostConfig config, String name) {
    String normalized = normalize(name).toLowerCase(Locale.ROOT);
    for (String configured : config.backups().liveSnapshotExcludes()) {
      String excluded = normalize(configured).toLowerCase(Locale.ROOT);
      if (normalized.equals(excluded) || normalized.startsWith(excluded + "/")) return true;
    }
    return false;
  }

  static String normalize(String name) {
    return name.replace('\\', '/').replaceAll("^/+", "");
  }
}
