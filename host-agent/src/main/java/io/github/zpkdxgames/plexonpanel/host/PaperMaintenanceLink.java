package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.util.*;
import java.util.concurrent.*;

/** Request/response bridge for Paper broadcasts and save-all flush during maintenance. */
public final class PaperMaintenanceLink implements AutoCloseable {
  private final HostConnection connection;
  private final DeviceRegistry devices;
  private final ConcurrentMap<String, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

  public PaperMaintenanceLink(HostConnection connection, DeviceRegistry devices) {
    this.connection = connection;
    this.devices = devices;
  }

  public void accept(DecodedMessage message) {
    if (!message.envelope().type().equals("maintenance.coordination.result")) return;
    try {
      String requestId = message.body().get("requestId").getAsString();
      CompletableFuture<Boolean> future = pending.remove(requestId);
      if (future != null) future.complete(message.body().get("success").getAsBoolean());
    } catch (Exception ignored) {
    }
  }

  public boolean notice(
      DeviceRegistry.Device device,
      boolean automatic,
      String text,
      String title)
      throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("message", text);
    payload.put("title", title == null ? "" : title);
    return request("notice", device, automatic, payload);
  }

  public boolean flush(DeviceRegistry.Device device, boolean automatic) throws Exception {
    return request("flush", device, automatic, Map.of());
  }

  private boolean request(
      String operation,
      DeviceRegistry.Device device,
      boolean automatic,
      Map<String, Object> values)
      throws Exception {
    if (pending.size() >= 8) throw new IllegalStateException("Paper maintenance queue full");
    String requestId = UUID.randomUUID().toString();
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    pending.put(requestId, future);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestId", requestId);
    body.put("operation", operation);
    body.put("automatic", automatic);
    body.putAll(values);
    if (device != null) {
      body.put("deviceId", device.deviceId());
      body.put("generation", devices.snapshot().generation());
    }
    try {
      if (!connection.send("maintenance.coordination", body, MessagePriority.CRITICAL)) return false;
      return future.get(12, TimeUnit.SECONDS);
    } finally {
      pending.remove(requestId);
    }
  }

  @Override
  public void close() {
    for (CompletableFuture<Boolean> future : pending.values()) future.complete(false);
    pending.clear();
  }
}
