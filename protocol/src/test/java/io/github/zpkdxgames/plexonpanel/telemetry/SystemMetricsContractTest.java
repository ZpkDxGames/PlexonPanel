package io.github.zpkdxgames.plexonpanel.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SystemMetricsContractTest {
  @TempDir Path root;

  @Test
  void unavailableCpuNeverMasqueradesAsRealZero() {
    Map<String, Object> snapshot = new SystemMetrics(root).collect();
    boolean hostAvailable = Boolean.TRUE.equals(snapshot.get("available"));
    Object hostPercent = snapshot.get("hostCpuPercent");
    double hostLoad = ((Number) snapshot.get("systemCpuLoad")).doubleValue();
    if (hostAvailable) {
      assertNotNull(hostPercent);
      assertTrue(((Number) hostPercent).doubleValue() >= 0.0d);
      assertTrue(hostLoad >= 0.0d);
    } else {
      assertNull(hostPercent);
      assertEquals(-1.0d, hostLoad);
    }

    Object processPercent = snapshot.get("processCpuPercent");
    double processLoad = ((Number) snapshot.get("processCpuLoad")).doubleValue();
    if (processPercent == null) assertEquals(-1.0d, processLoad);
    else {
      assertTrue(((Number) processPercent).doubleValue() >= 0.0d);
      assertTrue(processLoad >= 0.0d);
    }
  }
}
