package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal Source-RCON client dedicated to fixed maintenance operations.
 *
 * <p>The password is loaded from a Host-local secret file for each connection and is never returned,
 * logged, audited, or placed in a dashboard/relay payload. The configured endpoint is restricted to
 * loopback addresses.
 */
final class RconMaintenanceCommandChannel implements MaintenanceCommandChannel {
  private static final int SERVERDATA_RESPONSE_VALUE = 0;
  private static final int SERVERDATA_EXECCOMMAND = 2;
  private static final int SERVERDATA_AUTH_RESPONSE = 2;
  private static final int SERVERDATA_AUTH = 3;
  private static final int MAX_PACKET_BYTES = 1_048_576;
  private static final int MAX_SECRET_BYTES = 4096;
  private static final Gson GSON = new Gson();

  private final HostConfig.MaintenanceCommandConfig config;
  private final InetAddress address;
  private final AtomicInteger requestIds = new AtomicInteger(1000);

  RconMaintenanceCommandChannel(HostConfig.MaintenanceCommandConfig config) {
    this.config = Objects.requireNonNull(config, "config");
    this.address = resolveLoopback(config.host());
  }

  @Override
  public void warning(Operation operation, int remainingSeconds) throws IOException {
    Objects.requireNonNull(operation, "operation");
    if (remainingSeconds < 1 || remainingSeconds > 86_400)
      throw new IllegalArgumentException("Invalid maintenance warning boundary");
    String text = "Server maintenance: " + operation.label() + " in " + humanDuration(remainingSeconds) + ".";
    String json =
        GSON.toJson(
            Map.of(
                "text", text,
                "color", "yellow",
                "bold", true));
    command("tellraw @a " + json);
  }

  @Override
  public void saveAllFlush() throws IOException {
    String response = command("save-all flush");
    String normalized = response.toLowerCase(Locale.ROOT);
    if (normalized.contains("unknown or incomplete command")
        || normalized.contains("incorrect argument")
        || normalized.contains("unknown command"))
      throw failure("RCON_SAVE_FLUSH_FAILED");
  }

  @Override
  public boolean ready() {
    try {
      command("list");
      return true;
    } catch (Exception unavailable) {
      return false;
    }
  }

  private String command(String fixedCommand) throws IOException {
    if (!config.enabled()) throw failure("COMMAND_CHANNEL_DISABLED");
    byte[] secret = readSecret();
    int timeoutMillis = Math.multiplyExact(config.commandTimeoutSeconds(), 1000);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(address, config.port()), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      socket.setTcpNoDelay(true);
      try (InputStream input = new BufferedInputStream(socket.getInputStream());
          OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
        int authId = nextRequestId();
        writePacket(output, authId, SERVERDATA_AUTH, secret);
        boolean authenticated = false;
        for (int i = 0; i < 2; i++) {
          Packet packet = readPacket(input);
          if (packet.requestId() == -1) throw failure("RCON_AUTH_FAILED");
          if (packet.requestId() == authId && packet.type() == SERVERDATA_AUTH_RESPONSE) {
            authenticated = true;
            break;
          }
        }
        if (!authenticated) throw failure("RCON_PROTOCOL_FAILED");

        int commandId = nextRequestId();
        writePacket(
            output,
            commandId,
            SERVERDATA_EXECCOMMAND,
            fixedCommand.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 2; i++) {
          Packet packet = readPacket(input);
          if (packet.requestId() == -1) throw failure("RCON_AUTH_FAILED");
          if (packet.requestId() == commandId && packet.type() == SERVERDATA_RESPONSE_VALUE)
            return packet.body();
        }
        throw failure("RCON_PROTOCOL_FAILED");
      }
    } catch (SocketTimeoutException timeout) {
      throw failure("RCON_TIMEOUT");
    } catch (ConnectException | NoRouteToHostException connect) {
      throw failure("RCON_CONNECT_FAILED");
    } catch (IOException error) {
      if (safeCode(error.getMessage())) throw error;
      throw failure("RCON_IO_FAILED");
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  private byte[] readSecret() throws IOException {
    Path secretPath = Path.of(config.secretFile()).toAbsolutePath().normalize();
    if (!Files.isRegularFile(secretPath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(secretPath)) throw failure("RCON_SECRET_INVALID");
    long size = Files.size(secretPath);
    if (size < 1 || size > MAX_SECRET_BYTES) throw failure("RCON_SECRET_INVALID");
    validateSecretPermissions(secretPath);
    byte[] bytes = Files.readAllBytes(secretPath);
    int length = bytes.length;
    while (length > 0 && (bytes[length - 1] == '\n' || bytes[length - 1] == '\r')) length--;
    if (length < 1) {
      Arrays.fill(bytes, (byte) 0);
      throw failure("RCON_SECRET_INVALID");
    }
    for (int i = 0; i < length; i++) {
      if (bytes[i] == 0) {
        Arrays.fill(bytes, (byte) 0);
        throw failure("RCON_SECRET_INVALID");
      }
    }
    return length == bytes.length ? bytes : Arrays.copyOf(bytes, length);
  }

  private static void validateSecretPermissions(Path path) throws IOException {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      if (permissions.contains(PosixFilePermission.OTHERS_READ)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
          || permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.GROUP_EXECUTE))
        throw failure("RCON_SECRET_PERMISSIONS");
    } catch (UnsupportedOperationException ignored) {
      // The production Host is Linux/POSIX; keeping this fallback makes unit tests portable.
    }
  }

  private static InetAddress resolveLoopback(String configuredHost) {
    String host = configuredHost == null ? "" : configuredHost.trim();
    if (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(host.toLowerCase(Locale.ROOT)))
      throw new IllegalArgumentException("Maintenance command channel must use loopback RCON");
    String lookup = host.equals("[::1]") ? "::1" : host;
    try {
      InetAddress resolved = InetAddress.getByName(lookup);
      if (!resolved.isLoopbackAddress())
        throw new IllegalArgumentException("Maintenance command channel must resolve to loopback");
      return resolved;
    } catch (UnknownHostException unavailable) {
      throw new IllegalArgumentException("Invalid maintenance command host", unavailable);
    }
  }

  private int nextRequestId() {
    return requestIds.updateAndGet(value -> value >= Integer.MAX_VALUE - 2 ? 1000 : value + 1);
  }

  private static void writePacket(OutputStream output, int requestId, int type, byte[] body)
      throws IOException {
    int length = Math.addExact(body.length, 10);
    writeIntLe(output, length);
    writeIntLe(output, requestId);
    writeIntLe(output, type);
    output.write(body);
    output.write(0);
    output.write(0);
    output.flush();
  }

  private static Packet readPacket(InputStream input) throws IOException {
    int length = readIntLe(input);
    if (length < 10 || length > MAX_PACKET_BYTES) throw failure("RCON_PROTOCOL_FAILED");
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) throw failure("RCON_PROTOCOL_FAILED");
    int requestId = intLe(payload, 0);
    int type = intLe(payload, 4);
    if (payload[length - 1] != 0 || payload[length - 2] != 0)
      throw failure("RCON_PROTOCOL_FAILED");
    String body = new String(payload, 8, length - 10, StandardCharsets.UTF_8);
    return new Packet(requestId, type, body);
  }

  private static int readIntLe(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(4);
    if (bytes.length != 4) throw failure("RCON_PROTOCOL_FAILED");
    return intLe(bytes, 0);
  }

  private static int intLe(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff)
        | ((bytes[offset + 1] & 0xff) << 8)
        | ((bytes[offset + 2] & 0xff) << 16)
        | ((bytes[offset + 3] & 0xff) << 24);
  }

  private static void writeIntLe(OutputStream output, int value) throws IOException {
    output.write(value & 0xff);
    output.write((value >>> 8) & 0xff);
    output.write((value >>> 16) & 0xff);
    output.write((value >>> 24) & 0xff);
  }

  private static String humanDuration(int seconds) {
    if (seconds >= 60 && seconds % 60 == 0) {
      int minutes = seconds / 60;
      return minutes + (minutes == 1 ? " minute" : " minutes");
    }
    return seconds + (seconds == 1 ? " second" : " seconds");
  }

  private static IOException failure(String code) {
    return new IOException(code);
  }

  private static boolean safeCode(String value) {
    return value != null && value.matches("[A-Z0-9_]{3,64}");
  }

  private record Packet(int requestId, int type, String body) {}
}
