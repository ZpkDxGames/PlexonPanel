package io.github.zpkdxgames.plexonpanel.host;

import java.time.Instant;
import java.util.Map;

/** systemd cgroup accounting for the configured Minecraft unit, not the Host's own JVM. */
final class ServiceResourceSampler {
  record Sample(String scope, String source, String cpuUnit, Double cpuPercent,
      Long memoryBytes, boolean cpuAvailable, boolean memoryAvailable, String capturedAt) {}
  private long previousPid = -1, previousNanos, previousCpu;
  private boolean previousValid;

  synchronized Sample sample(Map<String, String> fields, long pid, long nowNanos, Instant capturedAt) {
    boolean active = "active".equals(fields.get("ActiveState")) && pid > 0;
    Long usedCpu = unsigned(fields.get("CPUUsageNSec"));
    Long memory = active ? unsigned(fields.get("MemoryCurrent")) : null;
    Double cpu = null;
    if (active && usedCpu != null && previousValid && previousPid == pid) {
      long elapsed = nowNanos - previousNanos;
      long used = usedCpu - previousCpu;
      if (elapsed > 0 && used >= 0) cpu = 100.0 * used / elapsed;
    }
    previousValid = active && usedCpu != null;
    previousPid = pid;
    previousNanos = nowNanos;
    previousCpu = usedCpu == null ? 0 : usedCpu;
    return new Sample("MINECRAFT_SERVICE", "SYSTEMD_CGROUP", "PERCENT_OF_ONE_CORE", cpu,
        memory, cpu != null, memory != null, capturedAt.toString());
  }

  private static Long unsigned(String value) {
    if (value == null || !value.matches("[0-9]{1,19}")) return null;
    try { return Long.parseLong(value); }
    catch (NumberFormatException unavailable) { return null; }
  }
}
