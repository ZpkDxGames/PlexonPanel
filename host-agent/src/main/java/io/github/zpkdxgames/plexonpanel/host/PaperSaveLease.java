package io.github.zpkdxgames.plexonpanel.host;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import io.github.zpkdxgames.plexonpanel.protocol.DecodedMessage;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import java.util.*;
import java.util.concurrent.*;

/** Host-side renewable Paper save lease with bounded structured failures. */
public final class PaperSaveLease implements AutoCloseable {
  public record Result(boolean success, String code, String phase, String message) {}

  private record Pending(String operation, CompletableFuture<Result> result) {}

  private final HostConnection connection;
  private final DeviceRegistry devices;
  private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
  private final ScheduledExecutorService renewer = Executors.newSingleThreadScheduledExecutor();

  public PaperSaveLease(HostConnection connection, DeviceRegistry devices) {
    this.connection = connection;
    this.devices = devices;
  }

  public void accept(DecodedMessage message) {
    try {
      if (!message.envelope().type().equals("backup.coordination.result")) return;
      String requestId = text(message.body(), "requestId", 36);
      UUID.fromString(requestId);
      Pending future = pending.remove(requestId);
      if (future == null) return;
      boolean success = bool(message.body(), "success");
      String code = optional(message.body(), "code", success ? "OK" : "PAPER_COORDINATION_DENIED", 64);
      String phase = optional(message.body(), "phase", "COORDINATING_PAPER", 48);
      String safeMessage =
          optional(
              message.body(),
              "message",
              success ? "Paper coordination completed." : "Paper declined the save coordination request.",
              320);
      if (!code.matches("[A-Z][A-Z0-9_]{0,63}") || !phase.matches("[A-Z][A-Z0-9_]{0,47}")) {
        future.result.complete(
            new Result(
                false,
                "PAPER_COORDINATION_UNAVAILABLE",
                "COORDINATING_PAPER",
                "Paper returned an invalid coordination result."));
        return;
      }
      future.result.complete(new Result(success, code, phase, safeMessage));
    } catch (Exception ignored) {
      // Invalid/mismatched responses are not allowed to complete another request.
    }
  }

  private Result request(Map<String, Object> body, String operation, int seconds) {
    String id = body.get("requestId").toString();
    if (pending.size() >= 4)
      return new Result(
          false,
          "PAPER_COORDINATION_UNAVAILABLE",
          "COORDINATING_PAPER",
          "Too many Paper save coordination requests are already pending.");
    CompletableFuture<Result> future = new CompletableFuture<>();
    pending.put(id, new Pending(operation, future));
    try {
      if (!connection.send("backup.coordination", body, MessagePriority.CRITICAL))
        return new Result(
            false,
            "PAPER_COORDINATION_UNAVAILABLE",
            "COORDINATING_PAPER",
            "The relay could not accept the save coordination request for Paper.");
      try {
        return future.get(seconds, TimeUnit.SECONDS);
      } catch (TimeoutException e) {
        return new Result(
            false,
            "PAPER_COORDINATION_TIMEOUT",
            "COORDINATING_PAPER",
            "Paper did not answer the save coordination request in time.");
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Result(
            false,
            "PAPER_COORDINATION_UNAVAILABLE",
            "COORDINATING_PAPER",
            "Save coordination was interrupted before Paper answered.");
      } catch (ExecutionException e) {
        return new Result(
            false,
            "PAPER_COORDINATION_UNAVAILABLE",
            "COORDINATING_PAPER",
            "Paper save coordination could not be completed.");
      }
    } finally {
      pending.remove(id);
    }
  }

  public Lease prepare(DeviceRegistry.Device device, boolean automatic) {
    String leaseId = UUID.randomUUID().toString(), requestId = UUID.randomUUID().toString();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("requestId", requestId);
    body.put("leaseId", leaseId);
    body.put("operation", "prepare");
    body.put("automatic", automatic);
    addDeviceContext(body, device);
    Result prepared = request(body, "prepare", 15);
    if (!prepared.success()) throw failure(prepared);
    Lease lease = new Lease(leaseId, device, automatic);
    lease.task = renewer.scheduleAtFixedRate(lease::renew, 20, 20, TimeUnit.SECONDS);
    return lease;
  }

  private void addDeviceContext(Map<String, Object> body, DeviceRegistry.Device device) {
    if (device == null) return;
    try {
      body.put("deviceId", device.deviceId());
      body.put("generation", devices.snapshot().generation());
    } catch (Exception failure) {
      throw new OperationFailure(
          "PAPER_COORDINATION_UNAVAILABLE",
          "COORDINATING_PAPER",
          "The Host could not read the local device authorization generation.",
          true);
    }
  }

  private static OperationFailure failure(Result result) {
    return new OperationFailure(
        result.code(), result.phase(), result.message(), result.code().contains("TIMEOUT"));
  }

  public final class Lease implements AutoCloseable {
    private final String id;
    private final DeviceRegistry.Device device;
    private final boolean automatic;
    private volatile boolean healthy = true, closed;
    private volatile Result unhealthyReason;
    private ScheduledFuture<?> task;

    private Lease(String id, DeviceRegistry.Device device, boolean automatic) {
      this.id = id;
      this.device = device;
      this.automatic = automatic;
    }

    private void renew() {
      if (closed) return;
      try {
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("leaseId", id);
        body.put("operation", "renew");
        body.put("automatic", automatic);
        addDeviceContext(body, device);
        Result result = request(body, "renew", 8);
        if (!result.success()) {
          unhealthyReason = result;
          healthy = false;
        }
      } catch (OperationFailure failure) {
        unhealthyReason =
            new Result(false, failure.code(), failure.phase(), failure.getMessage());
        healthy = false;
      }
    }

    public void check() {
      if (!healthy) {
        Result reason = unhealthyReason;
        if (reason != null)
          throw new OperationFailure(
              "SAVE_LEASE_LOST",
              "COORDINATING_PAPER",
              "The Paper save lease became unhealthy: " + bounded(reason.message()),
              true);
        throw new OperationFailure(
            "SAVE_LEASE_LOST",
            "COORDINATING_PAPER",
            "The Paper save lease became unhealthy during the snapshot.",
            true);
      }
    }

    public void close() {
      if (closed) return;
      closed = true;
      if (task != null) task.cancel(false);
      try {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", UUID.randomUUID().toString());
        body.put("leaseId", id);
        body.put("operation", "resume");
        body.put("automatic", automatic);
        addDeviceContext(body, device);
        request(body, "resume", 8);
      } catch (OperationFailure ignored) {
        // Paper watchdog restores the original autosave state if resume delivery fails.
      }
    }
  }

  private static String bounded(String value) {
    if (value == null || value.isBlank()) return "Paper did not provide a reason.";
    return value.length() <= 180 ? value : value.substring(0, 180);
  }

  public void close() {
    renewer.shutdownNow();
    for (Pending future : pending.values())
      future.result.complete(
          new Result(
              false,
              "PAPER_COORDINATION_UNAVAILABLE",
              "COORDINATING_PAPER",
              "The Host is shutting down."));
    pending.clear();
  }
}
