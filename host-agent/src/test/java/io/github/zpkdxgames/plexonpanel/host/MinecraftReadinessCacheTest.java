package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MinecraftReadinessCacheTest {
  @Test
  void passiveSnapshotsProbeOnlyOnTransitionToActive() {
    CountingChannel channel = new CountingChannel();
    MinecraftReadinessCache cache = new MinecraftReadinessCache(channel);

    assertTrue(cache.observeServiceState(true));
    assertEquals(1, channel.probes);
    assertTrue(cache.observeServiceState(true));
    assertTrue(cache.observeServiceState(true));
    assertEquals(1, channel.probes, "steady-state telemetry must reuse cached readiness");

    assertFalse(cache.observeServiceState(false));
    assertEquals(1, channel.probes, "inactive service must not open RCON");
    assertTrue(cache.observeServiceState(true));
    assertEquals(2, channel.probes, "new active transition gets one fresh readiness probe");
  }

  @Test
  void explicitRefreshAndOperationsCanDemandFreshReadiness() {
    CountingChannel channel = new CountingChannel();
    MinecraftReadinessCache cache = new MinecraftReadinessCache(channel);

    assertTrue(cache.refresh(true));
    assertEquals(1, channel.probes);
    assertTrue(cache.probeNow());
    assertEquals(2, channel.probes);

    cache.markStopped();
    assertFalse(cache.observeServiceState(false));
    assertEquals(2, channel.probes);
    cache.markStarted();
    assertTrue(cache.observeServiceState(true));
    assertEquals(
        2,
        channel.probes,
        "verified lifecycle start should not be probed again immediately");
  }

  @Test
  void disabledOrFailedChannelFailsClosed() {
    CountingChannel disabled = new CountingChannel();
    disabled.enabled = false;
    MinecraftReadinessCache disabledCache = new MinecraftReadinessCache(disabled);
    assertFalse(disabledCache.observeServiceState(true));
    assertEquals(0, disabled.probes);

    CountingChannel failed = new CountingChannel();
    failed.result = MinecraftCommandChannel.Result.failed("RCON_UNAVAILABLE");
    MinecraftReadinessCache failedCache = new MinecraftReadinessCache(failed);
    assertFalse(failedCache.observeServiceState(true));
    assertEquals(1, failed.probes);
  }

  private static final class CountingChannel implements MinecraftCommandChannel {
    int probes;
    boolean enabled = true;
    Result result = Result.ok();

    @Override
    public boolean enabled() {
      return enabled;
    }

    @Override
    public Result maintenanceNotice(MaintenanceOperation operation, int remainingSeconds) {
      return Result.ok();
    }

    @Override
    public Result saveAllFlush() {
      return Result.ok();
    }

    @Override
    public Result readinessProbe() {
      probes++;
      return result;
    }
  }
}
