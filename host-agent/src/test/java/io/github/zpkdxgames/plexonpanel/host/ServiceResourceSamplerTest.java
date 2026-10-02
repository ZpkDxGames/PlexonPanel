package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServiceResourceSamplerTest {
  private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");
  private static Map<String, String> fields(String cpu, String memory) {
    return Map.of("ActiveState", "active", "CPUUsageNSec", cpu, "MemoryCurrent", memory);
  }

  @Test void samplesServiceCpuAcrossIntervalsWithoutConfusingHostOrNodeTotals() {
    var sampler = new ServiceResourceSampler();
    var initial = sampler.sample(fields("1000000000", "536870912"), 10, 1_000_000_000L, NOW);
    assertFalse(initial.cpuAvailable());
    assertEquals(536870912L, initial.memoryBytes());
    var next = sampler.sample(fields("4000000000", "1073741824"), 10, 3_000_000_000L, NOW.plusSeconds(2));
    assertEquals(150.0, next.cpuPercent()); // Three CPU-seconds over two wall-seconds.
    assertEquals("PERCENT_OF_ONE_CORE", next.cpuUnit());
    assertEquals("MINECRAFT_SERVICE", next.scope());
    assertEquals("SYSTEMD_CGROUP", next.source());
    assertEquals(NOW.plusSeconds(2).toString(), next.capturedAt());
    assertTrue(next.memoryAvailable());
  }

  @Test void restartCounterResetAndUnavailableAccountingDoNotInventConsumption() {
    var sampler = new ServiceResourceSampler();
    sampler.sample(fields("1000", "1024"), 10, 1000, NOW);
    assertNull(sampler.sample(fields("2000", "1024"), 11, 2000, NOW).cpuPercent());
    assertNull(sampler.sample(fields("1", "1024"), 11, 3000, NOW).cpuPercent());
    var absent = sampler.sample(fields("[not set]", "[not set]"), 11, 4000, NOW);
    assertFalse(absent.cpuAvailable());
    assertFalse(absent.memoryAvailable());
    assertNull(absent.cpuPercent());
    assertNull(absent.memoryBytes());
    var invalid = sampler.sample(fields("18446744073709551615", "-1"), 11, 5000, NOW);
    assertNull(invalid.cpuPercent());
    assertNull(invalid.memoryBytes());
  }

  @Test void offlineServiceAndZeroIntervalsHaveNoLiveResourceSample() {
    var sampler = new ServiceResourceSampler();
    sampler.sample(fields("1000", "1024"), 10, 1000, NOW);
    assertNull(sampler.sample(fields("2000", "1024"), 10, 1000, NOW).cpuPercent());
    var stopped = sampler.sample(Map.of("ActiveState", "inactive", "CPUUsageNSec", "3000", "MemoryCurrent", "1024"),
        0, 2000, NOW);
    assertNull(stopped.cpuPercent());
    assertNull(stopped.memoryBytes());
    assertNull(sampler.sample(fields("4000", "1024"), 10, 3000, NOW).cpuPercent());
  }
}
