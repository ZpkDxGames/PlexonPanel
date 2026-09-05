package io.github.zpkdxgames.plexonpanel.control;

import static io.github.zpkdxgames.plexonpanel.control.JsonFields.*;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.files.*;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.*;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class ControlEngine implements AutoCloseable {
  @FunctionalInterface
  public interface Backend {
    Map<String, Object> execute(String action, JsonObject parameters, DeviceRegistry.Device device)
        throws Exception;
  }

  private final DeviceRegistry devices;
  private final Map<String, Boolean> capabilities;
  private final LocalAudit audit;
  private final SafeFiles files;
  private final Backend backend;
  private final MessageSink sink;
  private final BooleanSupplier authenticated;
  private final String serverId;
  private final RequestGate gate = new RequestGate();
  private final ThreadPoolExecutor worker =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(32),
          new NamedThreadFactory("plexonpanel-operations"),
          new ThreadPoolExecutor.AbortPolicy());

  private record Transfer(String deviceId, byte[] bytes, String hash, long expiry, int sequence) {}

  private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();

  public ControlEngine(
      DeviceRegistry devices,
      Map<String, Boolean> capabilities,
      LocalAudit audit,
      SafeFiles files,
      Backend backend,
      MessageSink sink,
      BooleanSupplier authenticated,
      String serverId) {
    this.devices = devices;
    this.capabilities = Map.copyOf(capabilities);
    this.audit = audit;
    this.files = files;
    this.backend = backend;
    this.sink = sink;
    this.authenticated = authenticated;
    this.serverId = serverId;
  }

  public void accept(DecodedMessage message) {
    if (!message.envelope().type().equals("action.request") || !authenticated.getAsBoolean())
      return;
    JsonObject body = message.body().deepCopy();
    try {
      worker.execute(() -> execute(body));
    } catch (RejectedExecutionException busy) {
      try {
        reply(
            text(body, "requestId", 36),
            text(body, "action", 64),
            text(body, "deviceId", 36),
            "NOT_AVAILABLE",
            "BUSY",
            "The operations queue is full.",
            Map.of());
      } catch (RuntimeException ignored) {
      }
    }
  }

  private void execute(JsonObject body) {
    String id = "", action = "", deviceId = "";
    DeviceRegistry.Device device = null;
    boolean operationCompleted = false;
    long started = System.nanoTime();
    JsonObject parameters = new JsonObject();
    try {
      id = text(body, "requestId", 36);
      UUID.fromString(id);
      action = text(body, "action", 64);
      deviceId = text(body, "deviceId", 36);
      UUID.fromString(deviceId);
      long generation = integer(body, "generation", -1, 1, Long.MAX_VALUE);
      if (!body.has("parameters") || !body.get("parameters").isJsonObject())
        throw new IllegalArgumentException("Invalid parameters");
      parameters = body.getAsJsonObject("parameters");
      if (parameters.size() > 16
          || parameters.toString().getBytes(StandardCharsets.UTF_8).length > 49152)
        throw new IllegalArgumentException("Parameters exceed limits");
      if (!authenticated.getAsBoolean()) throw new SecurityException("SESSION_CLOSED");
      device = devices.authorize(deviceId, generation, Scopes.required(action), capabilities);
      gate.accept(deviceId, id, action.endsWith(".chunk"));
      if (Scopes.HIGH_RISK.contains(action) && !bool(parameters, "confirmed"))
        throw new SecurityException("CONFIRMATION_REQUIRED");
      if ((action.equals("player.op")
              || action.equals("player.deop")
              || action.startsWith("backup.restore"))
          && !device.role().equals("Owner")) throw new SecurityException("OWNER_REQUIRED");
      audit.begin(
          entry(id, deviceId, device, action, parameters, "STARTED", "INTENT", started, Map.of()));
      Map<String, Object> data = local(action, parameters, device);
      if (data == null) data = backend.execute(action, parameters, device);
      operationCompleted = true;
      Map<String, Object> metadata = new TreeMap<>();
      for (String key : List.of("oldHash", "sha256", "bytes", "destination", "backupId"))
        if (data.containsKey(key)) metadata.put(key, data.get(key));
      audit.append(
          entry(id, deviceId, device, action, parameters, "SUCCESS", "OK", started, metadata));
      if (new Gson().toJson(data).getBytes(StandardCharsets.UTF_8).length > 56000)
        reply(
            id,
            action,
            deviceId,
            "NOT_AVAILABLE",
            "RESULT_TOO_LARGE",
            "Result exceeds the response limit; narrow the query or use chunked download.",
            Map.of());
      else reply(id, action, deviceId, "SUCCESS", "OK", "Operation completed.", data);
    } catch (Exception error) {
      String status =
          error instanceof SafeFiles.ConflictException
              ? "CONFLICT"
              : error instanceof SecurityException || error instanceof IllegalArgumentException
                  ? "DENIED"
                  : "FAILED";
      String code =
          error instanceof SecurityException
                  && error.getMessage() != null
                  && error.getMessage().matches("[A-Z_]{1,64}")
              ? error.getMessage()
              : status.equals("CONFLICT")
                  ? "STALE_FILE"
                  : status.equals("DENIED") ? "INVALID_PARAMETERS" : "OPERATION_FAILED";
      if (operationCompleted) { status = "NOT_AVAILABLE"; code = "AUDIT_UNAVAILABLE"; }
      try {
        audit.append(
            entry(id, deviceId, device, action, parameters, status, code, started, Map.of()));
      } catch (Exception ignored) {
        code = "AUDIT_UNAVAILABLE";
      }
      String message =
          operationCompleted ? "The operation completed but its result could not be recorded. Verify local state before retrying." : switch (code) {
            case "CAPABILITY_DISABLED" -> "This capability is disabled in local policy.";
            case "SCOPE_DENIED", "OWNER_REQUIRED" ->
                "This device does not have the required scope or role.";
            case "DEVICE_REVOKED", "DEVICE_EXPIRED" -> "This device must be paired again.";
            case "CONFIRMATION_REQUIRED" -> "Confirm this operation before submitting it.";
            case "STALE_FILE" -> "The file changed. Reload it and review your edits.";
            case "DUPLICATE_REQUEST" ->
                "This request ID was already used; the action was not repeated.";
            case "RATE_LIMITED", "BUSY" -> "Too many requests. Wait before trying again.";
            default ->
                "The operation could not be completed. Check local policy, parameters and the"
                    + " server log.";
          };
      reply(id, action, deviceId, status, code, message, Map.of());
    }
  }

  private Map<String, Object> local(String action, JsonObject p, DeviceRegistry.Device device)
      throws Exception {
    if (action.equals("settings.view"))
      return Map.of(
          "capabilities",
          capabilities,
          "protocolVersion",
          3,
          "version",
          "2.0.0",
          "maxEditableBytes",
          SafeFiles.MAX_TEXT_BYTES);
    if (action.equals("devices.list")) return Map.of("devices", devices.snapshot().devices());
    if (action.equals("devices.revoke")) {
      devices.revoke(text(p, "deviceId", 36));
      syncAccess();
      return Map.of();
    }
    if (action.equals("audit.list") || action.equals("audit.self"))
      return audit.list(device.deviceId(), action.equals("audit.self"), p);
    if (!action.startsWith("files.")) return null;
    if (files == null) throw new SecurityException("CAPABILITY_DISABLED");
    String root = optional(p, "root", "server", 32), path = optional(p, "path", "", 512);
    transfers.entrySet().removeIf(e -> e.getValue().expiry < System.currentTimeMillis());
    return switch (action) {
      case "files.list" -> files.list(root, path, (int) integer(p, "page", 0, 0, 100));
      case "files.read" -> files.read(root, path);
      case "files.write" ->
          files.write(
              root,
              path,
              text(p, "content", SafeFiles.MAX_TEXT_BYTES),
              text(p, "sha256", 64),
              false);
      case "files.create", "files.upload" ->
          files.write(root, path, text(p, "content", SafeFiles.MAX_TEXT_BYTES), "", true);
      case "files.rename" ->
          files.rename(root, path, text(p, "destination", 512), text(p, "sha256", 64));
      case "files.delete" -> files.delete(root, path, text(p, "sha256", 64));
      case "files.download" -> {
        if (transfers.size() >= 4
            || transfers.values().stream().anyMatch(t -> t.deviceId.equals(device.deviceId())))
          throw new SecurityException("BUSY");
        byte[] bytes = files.download(root, path);
        String id = UUID.randomUUID().toString();
        String hash = SafeFiles.hash(bytes);
        transfers.put(
            id,
            new Transfer(device.deviceId(), bytes, hash, System.currentTimeMillis() + 120000, 0));
        yield Map.of(
            "transferId",
            id,
            "bytes",
            bytes.length,
            "sha256",
            hash,
            "chunkBytes",
            SafeFiles.CHUNK_BYTES);
      }
      case "files.download.chunk" -> {
        String id = text(p, "transferId", 36);
        Transfer t = transfers.get(id);
        if (t == null || !t.deviceId.equals(device.deviceId()))
          throw new SecurityException("TRANSFER_EXPIRED");
        int sequence = (int) integer(p, "sequence", -1, 0, 512);
        if (sequence != t.sequence) throw new SecurityException("TRANSFER_SEQUENCE");
        int start = sequence * SafeFiles.CHUNK_BYTES,
            end = Math.min(t.bytes.length, start + SafeFiles.CHUNK_BYTES);
        boolean eof = end == t.bytes.length;
        if (eof) transfers.remove(id);
        else transfers.put(id, new Transfer(t.deviceId, t.bytes, t.hash, t.expiry, sequence + 1));
        yield Map.of(
            "transferId",
            id,
            "sequence",
            sequence,
            "eof",
            eof,
            "sha256",
            t.hash,
            "data",
            Base64.getEncoder().encodeToString(Arrays.copyOfRange(t.bytes, start, end)));
      }
      case "files.transfer.cancel" -> {
        String id = text(p, "transferId", 36);
        Transfer t = transfers.get(id);
        if (t != null && t.deviceId.equals(device.deviceId())) transfers.remove(id);
        yield Map.of();
      }
      default -> throw new SecurityException("UNKNOWN_ACTION");
    };
  }

  public void syncAccess() throws java.io.IOException {
    sink.send("access.sync", devices.snapshot(), MessagePriority.CRITICAL);
  }

  private void reply(
      String id,
      String action,
      String device,
      String status,
      String code,
      String message,
      Map<String, Object> data) {
    sink.send(
        "action.result",
        OperationResult.result(id, action, device, status, code, message, data),
        MessagePriority.CRITICAL);
  }

  private Map<String, Object> entry(
      String id,
      String deviceId,
      DeviceRegistry.Device device,
      String action,
      JsonObject p,
      String outcome,
      String code,
      long started,
      Map<String, Object> metadata) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("timestamp", Instant.now().toString());
    e.put("requestId", id);
    e.put("serverId", serverId);
    e.put("deviceId", deviceId);
    e.put("role", device == null ? "unknown" : device.role());
    e.put("actorLabel", device == null ? "unverified device" : device.name());
    e.put("actionType", action);
    e.put("target", target(action, p));
    e.put("outcome", outcome);
    e.put("durationMillis", (System.nanoTime() - started) / 1000000);
    e.put("code", code);
    e.put("metadata", metadata);
    return e;
  }

  private String target(String action, JsonObject p) {
    try {
      if (action.startsWith("files."))
        return optional(p, "root", "server", 32) + ":" + optional(p, "path", "", 512);
      if (action.startsWith("player.")) return text(p, "playerId", 36);
      if (action.startsWith("backup.")) return optional(p, "backupId", "backups", 64);
      if (action.equals("devices.revoke")) return text(p, "deviceId", 36);
    } catch (RuntimeException ignored) {
    }
    return action;
  }

  public void close() {
    worker.shutdownNow();
    transfers.clear();
  }
}
