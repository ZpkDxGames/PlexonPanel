package io.github.zpkdxgames.plexonpanel.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LinuxMetricsTest {
  @Test
  void guestTicksAreNotCountedTwice() {
    var c = LinuxMetrics.cpu("cpu 10 2 8 70 5 1 3 1 999 888");
    assertEquals(100, c.total());
    assertEquals(75, c.idle());
  }

  @Test
  void loadUsesDeltasAndPreservesRealIdleZero() {
    assertEquals(
        25.0, LinuxMetrics.load(new LinuxMetrics.Cpu(100, 75), new LinuxMetrics.Cpu(200, 150)));
    assertEquals(
        0.0, LinuxMetrics.load(new LinuxMetrics.Cpu(100, 75), new LinuxMetrics.Cpu(200, 175)));
  }

  @Test
  void firstCounterResetAndInvalidCountersAreUnavailable() {
    assertNull(LinuxMetrics.load(null, new LinuxMetrics.Cpu(100, 75)));
    assertNull(LinuxMetrics.load(new LinuxMetrics.Cpu(100, 75), new LinuxMetrics.Cpu(50, 30)));
    assertThrows(IllegalArgumentException.class, () -> LinuxMetrics.cpu("cpu -1 2 3 4"));
  }

  @Test
  void memoryValuesAreBytesAndMissingIsNotZero() {
    var m = LinuxMetrics.memory("MemTotal: 2048 kB\nMemAvailable: 1024 kB\nSwapFree: 0 kB\n");
    assertEquals(2097152L, m.get("MemTotal"));
    assertEquals(0L, m.get("SwapFree"));
    assertNull(m.get("NotProvided"));
  }
}
