package io.github.zpkdxgames.plexonpanel.telemetry;

import java.lang.management.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;

public final class SystemMetrics {
  private final Path root;
  private LinuxMetrics.Cpu previous;
  private long scannedAt;
  private Long directoryBytes;

  public SystemMetrics(Path root) {
    this.root = root;
    try {
      previous = LinuxMetrics.cpu(Files.readAllLines(Path.of("/proc/stat")).getFirst());
    } catch (Exception ignored) {
    }
  }

  public synchronized Map<String, Object> collect() {
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("capturedAt", Instant.now().toString());
    var os = ManagementFactory.getOperatingSystemMXBean();
    var extended = os instanceof com.sun.management.OperatingSystemMXBean e ? e : null;
    Double host = extended == null ? null : percent(extended.getCpuLoad()),
        process = extended == null ? null : percent(extended.getProcessCpuLoad());
    String provider = host == null ? "unavailable" : "MXBean";
    try {
      var sample = LinuxMetrics.cpu(Files.readAllLines(Path.of("/proc/stat")).getFirst());
      Double proc = LinuxMetrics.load(previous, sample);
      previous = sample;
      if (proc != null) {
        host = proc;
        provider = "/proc/stat";
      }
    } catch (Exception ignored) {
    }
    r.put("hostCpuPercent", host);
    r.put("processCpuPercent", process);
    r.put("available", host != null);
    r.put("cpuProvider", provider);
    r.put("systemCpuLoad", host == null ? -1 : host / 100);
    r.put("processCpuLoad", process == null ? -1 : process / 100);
    r.put("logicalProcessors", os.getAvailableProcessors());
    r.put("availableProcessors", os.getAvailableProcessors());
    r.put("loadAverage1m", os.getSystemLoadAverage() >= 0 ? os.getSystemLoadAverage() : null);
    r.put("systemLoadAverage", os.getSystemLoadAverage());
    try {
      String[] loads = Files.readString(Path.of("/proc/loadavg")).strip().split("\\s+");
      r.put("loadAverage1m", Double.parseDouble(loads[0]));
      r.put("loadAverage5m", Double.parseDouble(loads[1]));
      r.put("loadAverage15m", Double.parseDouble(loads[2]));
    } catch (Exception ignored) {
    }
    var memory = ManagementFactory.getMemoryMXBean();
    var heap = memory.getHeapMemoryUsage();
    r.put("jvmHeapUsedBytes", heap.getUsed());
    r.put("jvmHeapCommittedBytes", heap.getCommitted());
    r.put("jvmHeapMaximumBytes", heap.getMax());
    r.put("jvmNonHeapBytes", memory.getNonHeapMemoryUsage().getUsed());
    long direct =
        ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
            .filter(x -> x.getName().equals("direct"))
            .mapToLong(BufferPoolMXBean::getMemoryUsed)
            .sum();
    r.put("directBufferBytes", direct);
    long total = extended == null ? -1 : extended.getTotalMemorySize(),
        available = extended == null ? -1 : extended.getFreeMemorySize(),
        swap = extended == null ? -1 : extended.getTotalSwapSpaceSize(),
        swapFree = extended == null ? -1 : extended.getFreeSwapSpaceSize();
    try {
      var mem = LinuxMetrics.memory(Files.readString(Path.of("/proc/meminfo")));
      total = mem.getOrDefault("MemTotal", total);
      available = mem.getOrDefault("MemAvailable", available);
      swap = mem.getOrDefault("SwapTotal", swap);
      swapFree = mem.getOrDefault("SwapFree", swapFree);
    } catch (Exception ignored) {
    }
    r.put("physicalMemoryTotalBytes", known(total));
    r.put("physicalMemoryAvailableBytes", known(available));
    r.put("physicalMemoryUsedBytes", total >= 0 && available >= 0 ? total - available : null);
    r.put("swapTotalBytes", known(swap));
    r.put("swapUsedBytes", swap >= 0 && swapFree >= 0 ? swap - swapFree : null);
    try {
      r.put(
          "processRssBytes",
          LinuxMetrics.memory(Files.readString(Path.of("/proc/self/status"))).get("VmRSS"));
    } catch (Exception ignored) {
    }
    try {
      var disk = Files.getFileStore(root);
      long diskTotal = disk.getTotalSpace(), free = disk.getUsableSpace();
      r.put("diskTotalBytes", diskTotal);
      r.put("diskUsableBytes", free);
      r.put("diskUnallocatedBytes", disk.getUnallocatedSpace());
      r.put("diskUsedBytes", diskTotal - free);
      r.put("diskUsedPercent", diskTotal > 0 ? 100.0 * (diskTotal - free) / diskTotal : null);
    } catch (Exception ignored) {
    }
    long count = 0, millis = 0;
    for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gc.getCollectionCount() > 0) count += gc.getCollectionCount();
      if (gc.getCollectionTime() > 0) millis += gc.getCollectionTime();
    }
    r.put("gcCollections", count);
    r.put("gcPauseTotalMillis", millis);
    r.put("heapPressurePercent", heap.getMax() > 0 ? 100.0 * heap.getUsed() / heap.getMax() : null);
    r.put("processUptimeMillis", ManagementFactory.getRuntimeMXBean().getUptime());
    r.put("javaVersion", System.getProperty("java.version"));
    r.put("operatingSystem", System.getProperty("os.name"));
    r.put("architecture", System.getProperty("os.arch"));
    if (System.currentTimeMillis() - scannedAt >= 180000) {
      scannedAt = System.currentTimeMillis();
      directoryBytes = directorySize(root, 2000, 50000);
    }
    r.put("serverDirectoryBytes", directoryBytes);
    r.put("directorySampledAt", Instant.ofEpochMilli(scannedAt).toString());
    return r;
  }

  public static Long directorySize(Path root, long milliseconds, int maximumFiles) {
    long deadline = System.nanoTime() + milliseconds * 1000000;
    long[] total = {0};
    int[] count = {0};
    boolean[] complete = {true};
    try {
      Files.walkFileTree(
          root,
          EnumSet.noneOf(FileVisitOption.class),
          64,
          new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
              if (System.nanoTime() > deadline) {
                complete[0] = false;
                return FileVisitResult.TERMINATE;
              }
              return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFile(Path path, BasicFileAttributes a) {
              if (++count[0] > maximumFiles || System.nanoTime() > deadline) {
                complete[0] = false;
                return FileVisitResult.TERMINATE;
              }
              if (a.isRegularFile()) total[0] += a.size();
              return FileVisitResult.CONTINUE;
            }

            public FileVisitResult visitFileFailed(Path path, java.io.IOException e) {
              complete[0] = false;
              return FileVisitResult.SKIP_SUBTREE;
            }
          });
      return complete[0] ? total[0] : null;
    } catch (Exception e) {
      return null;
    }
  }

  private static Long known(long n) {
    return n < 0 ? null : n;
  }

  private static Double percent(double n) {
    return Double.isFinite(n) && n >= 0 ? Math.min(100, n * 100) : null;
  }
}
