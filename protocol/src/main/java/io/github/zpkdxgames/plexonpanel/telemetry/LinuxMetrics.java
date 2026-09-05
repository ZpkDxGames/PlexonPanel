package io.github.zpkdxgames.plexonpanel.telemetry;

import java.util.*;

public final class LinuxMetrics {
  public record Cpu(long total, long idle) {}

  private LinuxMetrics() {}

  public static Cpu cpu(String line) {
    String[] fields = line.strip().split("\\s+");
    if (fields.length < 5 || !fields[0].equals("cpu"))
      throw new IllegalArgumentException("Invalid /proc/stat");
    long total = 0, idle = 0;
    for (int i = 1; i < Math.min(fields.length, 9); i++) {
      long v = Long.parseLong(fields[i]);
      if (v < 0) throw new IllegalArgumentException("Negative tick count");
      total = Math.addExact(total, v);
      if (i == 4 || i == 5) idle = Math.addExact(idle, v);
    }
    return new Cpu(total, idle);
  }

  public static Double load(Cpu previous, Cpu current) {
    if (previous == null || current.total <= previous.total || current.idle < previous.idle)
      return null;
    long total = current.total - previous.total, idle = current.idle - previous.idle;
    if (idle > total) return null;
    return 100.0 * (total - idle) / total;
  }

  public static Map<String, Long> memory(String text) {
    Map<String, Long> result = new HashMap<>();
    for (String line : text.split("\n")) {
      String[] p = line.strip().split("\\s+");
      if (p.length >= 2 && p[0].endsWith(":")) {
        try {
          long value = Long.parseLong(p[1]);
          if (value >= 0)
            result.put(
                p[0].substring(0, p[0].length() - 1),
                p.length >= 3 && p[2].equals("kB") ? Math.multiplyExact(value, 1024) : value);
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return result;
  }
}
