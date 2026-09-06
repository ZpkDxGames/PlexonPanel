package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PaperSaveLease implements AutoCloseable {
  private final HostConnection connection;
  private final DeviceRegistry devices;
  private final Map<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();
  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

  public PaperSaveLease(HostConnection connection, DeviceRegistry devices) {
    this.connection = connection;
    this.devices = devices;
  }

  public void accept(DecodedMessage m) {
    if (!m.envelope().type().equals("backup.coordination.result")) return;
    String id = m.body().get("requestId").getAsString();
    var f = pending.remove(id);
    if (f != null) f.complete(m.body().get("success").getAsBoolean());
  }

  public Lease prepare(DeviceRegistry.Device device, boolean automatic) throws Exception {
    String lease = UUID.randomUUID().toString();
    if (!request(lease, "prepare", device, automatic))
      throw new IllegalStateException("Paper save preparation denied");
    AtomicBoolean healthy = new AtomicBoolean(true);
    var timer =
        scheduler.scheduleAtFixedRate(
            () -> {
              try {
                if (!request(lease, "renew", device, automatic)) healthy.set(false);
              } catch (Exception e) {
                healthy.set(false);
              }
            },
            20,
            20,
            TimeUnit.SECONDS);
    return new Lease(
        healthy,
        () -> {
          timer.cancel(false);
          try {
            request(lease, "resume", device, automatic);
          } catch (Exception ignored) {
            /* Paper watchdog restores saves within 60 seconds. */
          }
        });
  }

  private boolean request(
      String lease, String operation, DeviceRegistry.Device device, boolean automatic)
      throws Exception {
    String id = UUID.randomUUID().toString();
    var future = new CompletableFuture<Boolean>();
    if (pending.size() >= 4) throw new IllegalStateException("Lease queue full");
    pending.put(id, future);
    Map<String, Object> body = new HashMap<>();
    body.put("requestId", id);
    body.put("leaseId", lease);
    body.put("operation", operation);
    body.put("automatic", automatic);
    if (device != null) {
      body.put("deviceId", device.deviceId());
      body.put("generation", devices.snapshot().generation());
    }
    try {
      if (!connection.send("backup.coordination", body, MessagePriority.CRITICAL)) return false;
      return future.get(10, TimeUnit.SECONDS);
    } finally {
      pending.remove(id);
    }
  }

  public record Lease(AtomicBoolean healthy, Runnable release) implements AutoCloseable {
    public void check() {
      if (!healthy.get()) throw new IllegalStateException("Paper save lease lost");
    }

    public void close() {
      release.run();
    }
  }

  public void close() {
    scheduler.shutdownNow();
    for (var f : pending.values()) f.complete(false);
    pending.clear();
  }
}
