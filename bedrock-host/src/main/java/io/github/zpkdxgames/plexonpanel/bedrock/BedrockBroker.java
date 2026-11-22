package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class BedrockBroker implements AutoCloseable {
  private static final int VERSION = 1;
  private static final int REQUEST_BYTES = 1024;
  private static final int RESPONSE_BYTES = 48 * 1024;
  private static final Duration LOCAL_IO_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(15);
  private static final long MIN_INTERVAL_NANOS = Duration.ofSeconds(2).toNanos();
  private static final Set<String> REQUEST_FIELDS =
      Set.of("version", "operation", "serverId", "nodeId", "instanceKey");

  enum Operation { STATUS, LOGS }

  @FunctionalInterface
  interface Readings {
    JsonObject read(Operation operation) throws IOException;
  }

  private final Path path;
  private final String hostUser;
  private final BedrockConfig.Binding binding;
  private final Readings readings;
  private final ServerSocketChannel server;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final EnumMap<Operation, Long> lastRequest = new EnumMap<>(Operation.class);

  BedrockBroker(Path path, String hostUser, BedrockConfig.Binding binding, Readings readings)
      throws IOException {
    this.path = path;
    this.hostUser = hostUser;
    this.binding = binding;
    this.readings = readings;
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    boolean bound = false;
    try {
      server.bind(UnixDomainSocketAddress.of(path), 8);
      bound = true;
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-rw----"));
    } catch (IOException | RuntimeException failure) {
      server.close();
      if (bound) Files.deleteIfExists(path);
      throw failure;
    }
  }

  void serve() throws IOException {
    while (!closed.get()) {
      SocketChannel client;
      try {
        client = server.accept();
      } catch (ClosedChannelException stopped) {
        if (closed.get()) return;
        throw stopped;
      }
      try (client; var frames = new UnixFrames(client)) {
        if (!frames.peerIs(hostUser)) continue;
        JsonObject request = UnixFrames.readObject(frames.read(REQUEST_BYTES, LOCAL_IO_TIMEOUT));
        Operation operation = validate(request, binding);
        JsonObject response = request(operation, binding);
        try {
          long now = System.nanoTime();
          Long previous = lastRequest.get(operation);
          if (previous != null && now - previous < MIN_INTERVAL_NANOS) {
            response.addProperty("error", "RATE_LIMITED");
          } else {
            lastRequest.put(operation, now);
            response.add("result", readings.read(operation));
          }
        } catch (IOException | RuntimeException failure) {
          // Never leak the daemon's raw response, endpoint, environment or credentials.
          response.addProperty("error", "DOCKER_UNAVAILABLE");
        }
        frames.write(response, RESPONSE_BYTES, LOCAL_IO_TIMEOUT);
      } catch (IOException | RuntimeException failure) {
        // Reject malformed/oversized clients without returning identifying details.
      }
    }
  }

  static JsonObject read(
      Path path, String brokerUser, BedrockConfig.Binding binding, Operation operation) throws IOException {
    try (var socket = SocketChannel.open(StandardProtocolFamily.UNIX); var frames = new UnixFrames(socket)) {
      frames.connect(path, LOCAL_IO_TIMEOUT);
      if (!frames.peerIs(brokerUser)) throw new IOException("Unexpected broker principal");
      frames.write(request(operation, binding), REQUEST_BYTES, LOCAL_IO_TIMEOUT);
      JsonObject response = UnixFrames.readObject(frames.read(RESPONSE_BYTES, RESPONSE_TIMEOUT));
      JsonElement result = response.remove("result");
      Operation returned = validate(response, binding);
      if (operation != returned || result == null || !result.isJsonObject())
        throw new IOException("Docker reading is unavailable");
      return result.getAsJsonObject();
    }
  }

  static Operation validate(JsonObject request, BedrockConfig.Binding binding) throws IOException {
    var version = request.get("version");
    if (!request.keySet().equals(REQUEST_FIELDS)
        || version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()
        || !Integer.toString(VERSION).equals(version.getAsString())
        || !binding.serverId().toString().equals(string(request, "serverId"))
        || !binding.nodeId().toString().equals(string(request, "nodeId"))
        || !BedrockConfig.INSTANCE.equals(string(request, "instanceKey"))) {
      throw new IOException("Unexpected broker request identity");
    }
    String operation = string(request, "operation");
    if (operation == null) throw new IOException("Unsupported broker operation");
    try {
      return Operation.valueOf(operation);
    } catch (IllegalArgumentException error) {
      throw new IOException("Unsupported broker operation", error);
    }
  }

  private static JsonObject request(Operation operation, BedrockConfig.Binding binding) {
    JsonObject object = new JsonObject();
    object.addProperty("version", VERSION);
    object.addProperty("operation", operation.name());
    object.addProperty("serverId", binding.serverId().toString());
    object.addProperty("nodeId", binding.nodeId().toString());
    object.addProperty("instanceKey", BedrockConfig.INSTANCE);
    return object;
  }

  private static String string(JsonObject object, String key) {
    var value = object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : null;
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) return;
    try {
      server.close();
    } finally {
      Files.deleteIfExists(path);
    }
  }
}
