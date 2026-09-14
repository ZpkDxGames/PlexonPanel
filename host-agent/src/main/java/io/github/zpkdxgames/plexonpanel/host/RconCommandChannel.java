package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Host-local Minecraft RCON client restricted to the fixed maintenance command set.
 *
 * <p>No generic command method is exposed to the dashboard/control plane. The password is read from
 * a protected Host-local file for each connection and is never returned in status or exception text.
 */
public final class RconCommandChannel {
  private static final int SERVERDATA_RESPONSE_VALUE = 0;
  private static final int SERVERDATA_EXECCOMMAND = 2;
  private static final int SERVERDATA_AUTH = 3;
  private static final int MAX_PACKET_BYTES = 65_536;
  private static final int MAX_SECRET_BYTES = 512;
  private static final Set<Integer> WARNING_SECONDS = Set.of(1800, 900, 60, 30, 15, 5);

  private final HostConfig.CommandChannelConfig config;

  public RconCommandChannel(HostConfig.CommandChannelConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  public boolean enabled() {
    return config.enabled();
  }

  public Map<String, Object> safeStatus() {
    return Map.of(
        "enabled", config.enabled(),
        "transport", "RCON",
        "target", "LOOPBACK",
        "port", config.port());
  }

  /** Sends one of the six fixed manual-backup warning messages. */
  public void broadcastBackupWarning(int secondsRemaining) {
    if (!WARNING_SECONDS.contains(secondsRemaining))
      throw new IllegalArgumentException("Unsupported maintenance warning boundary");
    String human =
        switch (secondsRemaining) {
          case 1800 -> "30 minutes";
          case 900 -> "15 minutes";
          case 60 -> "1 minute";
          case 30 -> "30 seconds";
          case 15 -> "15 seconds";
          case 5 -> "5 seconds";
          default -> throw new IllegalArgumentException("Unsupported maintenance warning boundary");
        };
    executeFixed(
        "tellraw @a {\"text\":\"PlexonCraft full backup in "
            + human
            + ". The server will restart automatically.\",\"color\":\"yellow\"}",
        "COUNTDOWN",
        config.commandTimeoutMillis(),
        false);
  }

  /** Flushes Minecraft saves immediately before the Host begins the systemd stop phase. */
  public void saveAllFlush() {
    String response =
        executeFixed("save-all flush", "FINAL_SAVE", config.commandTimeoutMillis(), true);
    String lower = response.toLowerCase(Locale.ROOT);
    if (response.isBlank()
        || lower.contains("unknown command")
        || lower.contains("unknown or incomplete")
        || lower.contains("incorrect argument")
        || lower.contains("exception")
        || lower.startsWith("error"))
      throw new OperationFailure(
          "SAVE_FLUSH_REJECTED",
          "FINAL_SAVE",
          "Minecraft did not affirm the final save flush; the server was left running.",
          true);
  }

  /** Fixed readiness probe for later lifecycle verification; not exposed as arbitrary command execution. */
  public boolean probeReady() {
    try {
      executeFixed("list", "VERIFYING_STARTUP", config.readinessTimeoutMillis(), false);
      return true;
    } catch (OperationFailure failure) {
      return false;
    }
  }

  private String executeFixed(String command, String phase, int timeoutMillis, boolean requireResponse) {
    if (!config.enabled())
      throw new OperationFailure(
          "COMMAND_CHANNEL_DISABLED",
          phase,
          "The Host-local Minecraft command channel is not enabled.",
          false);

    byte[] secret = readSecret(phase);
    try (Socket socket = new Socket()) {
      socket.connect(new InetSocketAddress(config.host(), config.port()), timeoutMillis);
      socket.setSoTimeout(timeoutMillis);
      socket.setTcpNoDelay(true);
      try (InputStream input = new BufferedInputStream(socket.getInputStream());
          OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
        int authId = positiveRequestId();
        writePacket(output, authId, SERVERDATA_AUTH, secret);
        Packet auth = readPacket(input);
        if (auth.requestId() == -1 || auth.requestId() != authId)
          throw new OperationFailure(
              "RCON_AUTH_FAILED",
              phase,
              "Minecraft rejected the Host-local command-channel credential.",
              false);

        int commandId = nextRequestId(authId);
        writePacket(
            output,
            commandId,
            SERVERDATA_EXECCOMMAND,
            command.getBytes(StandardCharsets.UTF_8));
        Packet response = readPacket(input);
        if (response.requestId() != commandId
            || (response.type() != SERVERDATA_RESPONSE_VALUE
                && response.type() != SERVERDATA_EXECCOMMAND))
          throw new OperationFailure(
              "RCON_PROTOCOL_ERROR",
              phase,
              "Minecraft returned an invalid command-channel response.",
              true);
        String text = new String(response.payload(), StandardCharsets.UTF_8).trim();
        if (requireResponse && text.isBlank())
          throw new OperationFailure(
              "RCON_EMPTY_RESPONSE",
              phase,
              "Minecraft returned no affirmative response to the maintenance command.",
              true);
        return text;
      }
    } catch (SocketTimeoutException timeout) {
      throw new OperationFailure(
          "RCON_TIMEOUT",
          phase,
          "The Host-local Minecraft command channel timed out.",
          true);
    } catch (ConnectException | NoRouteToHostException unavailable) {
      throw new OperationFailure(
          "RCON_UNAVAILABLE",
          phase,
          "The Host-local Minecraft command channel is unavailable.",
          true);
    } catch (OperationFailure failure) {
      throw failure;
    } catch (IOException failure) {
      throw new OperationFailure(
          "RCON_IO_FAILED",
          phase,
          "The Host-local Minecraft command channel failed safely.",
          true);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  private byte[] readSecret(String phase) {
    Path file = Path.of(config.secretFile()).toAbsolutePath().normalize();
    try {
      if (Files.isSymbolicLink(file)
          || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
          || Files.size(file) < 1
          || Files.size(file) > MAX_SECRET_BYTES)
        throw new IOException("invalid secret file");
      validateSecretPermissions(file);
      byte[] raw = Files.readAllBytes(file);
      int end = raw.length;
      while (end > 0 && (raw[end - 1] == '\n' || raw[end - 1] == '\r')) end--;
      if (end < 1 || end > 256) {
        Arrays.fill(raw, (byte) 0);
        throw new IOException("invalid secret length");
      }
      for (int i = 0; i < end; i++)
        if (raw[i] == 0 || raw[i] == '\n' || raw[i] == '\r') {
          Arrays.fill(raw, (byte) 0);
          throw new IOException("invalid secret bytes");
        }
      byte[] secret = Arrays.copyOf(raw, end);
      Arrays.fill(raw, (byte) 0);
      return secret;
    } catch (IOException failure) {
      throw new OperationFailure(
          "RCON_SECRET_UNAVAILABLE",
          phase,
          "The Host-local command-channel credential cannot be read safely.",
          false);
    }
  }

  private static void validateSecretPermissions(Path file) throws IOException {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
      if (permissions.contains(PosixFilePermission.OTHERS_READ)
          || permissions.contains(PosixFilePermission.OTHERS_WRITE)
          || permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
          || permissions.contains(PosixFilePermission.GROUP_WRITE)
          || permissions.contains(PosixFilePermission.GROUP_EXECUTE))
        throw new IOException("unsafe secret permissions");
    } catch (UnsupportedOperationException ignored) {
      // HostMain already enforces Linux; this keeps unit tests portable on non-POSIX filesystems.
    }
  }

  private static void writePacket(OutputStream output, int requestId, int type, byte[] payload)
      throws IOException {
    if (payload.length > MAX_PACKET_BYTES - 10) throw new IOException("RCON payload too large");
    int length = payload.length + 10;
    ByteBuffer buffer = ByteBuffer.allocate(length + 4).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(length);
    buffer.putInt(requestId);
    buffer.putInt(type);
    buffer.put(payload);
    buffer.put((byte) 0);
    buffer.put((byte) 0);
    output.write(buffer.array());
    output.flush();
  }

  private static Packet readPacket(InputStream input) throws IOException {
    byte[] lengthBytes = input.readNBytes(4);
    if (lengthBytes.length != 4) throw new EOFException("RCON packet length missing");
    int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    if (length < 10 || length > MAX_PACKET_BYTES) throw new IOException("Invalid RCON packet length");
    byte[] packet = input.readNBytes(length);
    if (packet.length != length) throw new EOFException("Incomplete RCON packet");
    ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    int requestId = buffer.getInt();
    int type = buffer.getInt();
    int payloadLength = length - 10;
    byte[] payload = new byte[payloadLength];
    buffer.get(payload);
    byte zero1 = buffer.get(), zero2 = buffer.get();
    if (zero1 != 0 || zero2 != 0) throw new IOException("Invalid RCON packet terminator");
    return new Packet(requestId, type, payload);
  }

  private static int positiveRequestId() {
    return ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE - 1);
  }

  private static int nextRequestId(int current) {
    return current == Integer.MAX_VALUE - 1 ? 1 : current + 1;
  }

  private record Packet(int requestId, int type, byte[] payload) {}
}
