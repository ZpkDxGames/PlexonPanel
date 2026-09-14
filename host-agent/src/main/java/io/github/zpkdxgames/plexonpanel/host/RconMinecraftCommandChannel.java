package io.github.zpkdxgames.plexonpanel.host;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Minimal Source-RCON client restricted to the fixed maintenance command set. */
public final class RconMinecraftCommandChannel implements MinecraftCommandChannel {
  private static final int AUTH_TYPE = 3;
  private static final int AUTH_RESPONSE_TYPE = 2;
  private static final int COMMAND_TYPE = 2;
  private static final int COMMAND_RESPONSE_TYPE = 0;
  private static final int AUTH_REQUEST_ID = 0x504c5801;
  private static final int COMMAND_REQUEST_ID = 0x504c5802;
  private static final int MAX_PACKET_LENGTH = 65_536;
  private static final int MAX_SECRET_BYTES = 1_024;

  private final HostConfig.CommandChannelConfig config;

  public RconMinecraftCommandChannel(HostConfig.CommandChannelConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  @Override
  public boolean enabled() {
    return config.enabled();
  }

  @Override
  public Result maintenanceNotice(MaintenanceOperation operation, int remainingSeconds) {
    if (operation == null || remainingSeconds < 0 || remainingSeconds > 86_400)
      return Result.failed("MAINTENANCE_NOTICE_INVALID");
    String text =
        "Server maintenance: " + operation.label() + " in " + humanDuration(remainingSeconds) + ".";
    String command =
        "tellraw @a {\"text\":\""
            + text
            + "\",\"color\":\"yellow\",\"bold\":true}";
    return executeFixed(command, false);
  }

  @Override
  public Result saveAllFlush() {
    return executeFixed("save-all flush", true);
  }

  @Override
  public Result readinessProbe() {
    return executeFixed("list", false);
  }

  private Result executeFixed(String command, boolean requireAffirmativeResponse) {
    if (!config.enabled()) return Result.failed("COMMAND_CHANNEL_DISABLED");
    byte[] secret = null;
    try {
      secret = readSecret();
      InetAddress target = resolveLoopbackTarget();
      try (Socket socket = new Socket()) {
        int timeout = config.commandTimeoutMillis();
        socket.connect(new InetSocketAddress(target, config.port()), timeout);
        socket.setSoTimeout(timeout);
        socket.setTcpNoDelay(true);
        InputStream input = new BufferedInputStream(socket.getInputStream());
        OutputStream output = new BufferedOutputStream(socket.getOutputStream());

        writePacket(output, AUTH_REQUEST_ID, AUTH_TYPE, secret);
        authenticate(input);

        writePacket(
            output,
            COMMAND_REQUEST_ID,
            COMMAND_TYPE,
            command.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 8; i++) {
          Packet response = readPacket(input);
          if (response.requestId == -1) throw new RconFailure("RCON_PROTOCOL_ERROR");
          if (response.requestId == COMMAND_REQUEST_ID && response.type == COMMAND_RESPONSE_TYPE) {
            if (requireAffirmativeResponse && !affirmative(response.body))
              return Result.failed("RCON_COMMAND_REJECTED");
            return Result.ok();
          }
        }
        throw new RconFailure("RCON_PROTOCOL_ERROR");
      }
    } catch (SecretFailure e) {
      return Result.failed(e.code);
    } catch (SocketTimeoutException e) {
      return Result.failed("RCON_TIMEOUT");
    } catch (ConnectException | NoRouteToHostException | UnknownHostException e) {
      return Result.failed("RCON_UNAVAILABLE");
    } catch (RconFailure e) {
      return Result.failed(e.code);
    } catch (IOException e) {
      return Result.failed("RCON_IO_FAILED");
    } finally {
      if (secret != null) Arrays.fill(secret, (byte) 0);
    }
  }

  private static boolean affirmative(String response) {
    if (response == null || response.isBlank()) return false;
    String normalized = response.trim().toLowerCase(Locale.ROOT);
    return !normalized.contains("unknown command")
        && !normalized.contains("unknown or incomplete command")
        && !normalized.contains("incorrect argument")
        && !normalized.contains("syntax error")
        && !normalized.contains("exception")
        && !normalized.startsWith("error")
        && !normalized.startsWith("failed");
  }

  private InetAddress resolveLoopbackTarget() throws IOException, RconFailure {
    InetAddress[] addresses = InetAddress.getAllByName(config.host());
    if (addresses.length == 0) throw new UnknownHostException(config.host());
    for (InetAddress address : addresses)
      if (!address.isLoopbackAddress()) throw new RconFailure("RCON_TARGET_NOT_LOOPBACK");
    return addresses[0];
  }

  private void authenticate(InputStream input) throws IOException, RconFailure {
    for (int i = 0; i < 3; i++) {
      Packet response = readPacket(input);
      if (response.requestId == -1) throw new RconFailure("RCON_AUTH_FAILED");
      if (response.type == AUTH_RESPONSE_TYPE) {
        if (response.requestId != AUTH_REQUEST_ID) throw new RconFailure("RCON_AUTH_FAILED");
        return;
      }
    }
    throw new RconFailure("RCON_AUTH_FAILED");
  }

  private byte[] readSecret() throws IOException, SecretFailure {
    Path path = Path.of(config.secretFile()).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) < 1
        || Files.size(path) > MAX_SECRET_BYTES)
      throw new SecretFailure("RCON_SECRET_INVALID");
    try {
      Set<PosixFilePermission> permissions =
          Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
      if (!permissions.contains(PosixFilePermission.OWNER_READ)
          || permissions.contains(PosixFilePermission.OWNER_EXECUTE)
          || permissions.contains(PosixFilePermission.GROUP_READ)
          || permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
          || permissions.contains(PosixFilePermission.OTHERS_READ)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_EXECUTE))
        throw new SecretFailure("RCON_SECRET_PERMISSIONS");
    } catch (UnsupportedOperationException e) {
      throw new SecretFailure("RCON_SECRET_PERMISSIONS");
    }
    byte[] raw = Files.readAllBytes(path);
    int length = raw.length;
    while (length > 0 && (raw[length - 1] == '\n' || raw[length - 1] == '\r')) length--;
    if (length == 0) {
      Arrays.fill(raw, (byte) 0);
      throw new SecretFailure("RCON_SECRET_INVALID");
    }
    for (int i = 0; i < length; i++) {
      if (raw[i] == 0 || raw[i] == '\n' || raw[i] == '\r') {
        Arrays.fill(raw, (byte) 0);
        throw new SecretFailure("RCON_SECRET_INVALID");
      }
    }
    byte[] secret = Arrays.copyOf(raw, length);
    Arrays.fill(raw, (byte) 0);
    return secret;
  }

  private static void writePacket(OutputStream output, int requestId, int type, byte[] body)
      throws IOException, RconFailure {
    if (body.length > MAX_PACKET_LENGTH - 10) throw new RconFailure("RCON_PROTOCOL_ERROR");
    int length = 10 + body.length;
    ByteBuffer packet = ByteBuffer.allocate(length + 4).order(ByteOrder.LITTLE_ENDIAN);
    packet.putInt(length);
    packet.putInt(requestId);
    packet.putInt(type);
    packet.put(body);
    packet.put((byte) 0);
    packet.put((byte) 0);
    output.write(packet.array());
    output.flush();
  }

  private static Packet readPacket(InputStream input) throws IOException, RconFailure {
    byte[] lengthBytes = readExactly(input, 4);
    int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    if (length < 10 || length > MAX_PACKET_LENGTH) throw new RconFailure("RCON_PROTOCOL_ERROR");
    byte[] packet = readExactly(input, length);
    ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    int requestId = buffer.getInt();
    int type = buffer.getInt();
    int bodyLength = length - 10;
    byte[] body = new byte[bodyLength];
    buffer.get(body);
    if (buffer.get() != 0 || buffer.get() != 0) throw new RconFailure("RCON_PROTOCOL_ERROR");
    String response = new String(body, StandardCharsets.UTF_8);
    Arrays.fill(body, (byte) 0);
    Arrays.fill(packet, (byte) 0);
    return new Packet(requestId, type, response);
  }

  private static byte[] readExactly(InputStream input, int length) throws IOException {
    byte[] result = input.readNBytes(length);
    if (result.length != length) throw new EOFException("RCON packet ended early");
    return result;
  }

  private static String humanDuration(int seconds) {
    if (seconds >= 60 && seconds % 60 == 0) {
      int minutes = seconds / 60;
      return minutes + (minutes == 1 ? " minute" : " minutes");
    }
    return seconds + (seconds == 1 ? " second" : " seconds");
  }

  private record Packet(int requestId, int type, String body) {}

  private static final class RconFailure extends Exception {
    final String code;

    RconFailure(String code) {
      this.code = code;
    }
  }

  private static final class SecretFailure extends Exception {
    final String code;

    SecretFailure(String code) {
      this.code = code;
    }
  }
}
