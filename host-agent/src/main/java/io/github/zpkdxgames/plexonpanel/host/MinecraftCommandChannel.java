package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fixed-purpose localhost Minecraft RCON channel used only by Host maintenance workflows.
 * Dashboard payloads can never supply a command or credential to this class.
 */
public final class MinecraftCommandChannel {
  private static final int TYPE_RESPONSE = 0;
  private static final int TYPE_COMMAND = 2;
  private static final int TYPE_AUTH = 3;
  private static final int MAX_PACKET = 64 * 1024;
  private final HostConfig.CommandChannelConfig config;

  public MinecraftCommandChannel(HostConfig.CommandChannelConfig config) {
    this.config = Objects.requireNonNull(config);
  }

  public boolean configured() {
    return config.enabled();
  }

  public void requireConfigured() throws OperationFailure {
    if (!configured())
      throw new OperationFailure(
          "MAINTENANCE_COMMAND_CHANNEL_DISABLED",
          "PREFLIGHT",
          "The Host-local Minecraft maintenance command channel is not enabled.",
          false);
  }

  public void notice(int seconds) throws Exception {
    requireConfigured();
    String label = warningLabel(seconds);
    String command =
        "tellraw @a {\"text\":\"[PlexonPanel] Full server backup in "
            + label
            + ". The server will restart automatically.\",\"color\":\"yellow\"}";
    executeChecked(command, "MAINTENANCE_WARNING_FAILED", "COUNTDOWN");
  }

  public void restartNotice(int seconds) throws Exception {
    requireConfigured();
    String command =
        "tellraw @a {\"text\":\"[PlexonPanel] Server restart in "
            + warningLabel(seconds)
            + ".\",\"color\":\"yellow\"}";
    executeChecked(command, "MAINTENANCE_WARNING_FAILED", "COUNTDOWN");
  }

  public void flush() throws Exception {
    requireConfigured();
    String response = execute("save-all flush");
    String normalized = response.toLowerCase(Locale.ROOT);
    if (response.isBlank()
        || normalized.contains("unknown command")
        || normalized.contains("unknown or incomplete command")
        || normalized.contains("incorrect argument")
        || normalized.contains("error"))
      throw new OperationFailure(
          "SAVE_FLUSH_FAILED",
          "FINAL_SAVE",
          "Minecraft did not affirm the final save-all flush.",
          true);
  }

  public boolean reachable() {
    if (!configured()) return false;
    try {
      String response = execute("list");
      return !response.isBlank();
    } catch (Exception ignored) {
      return false;
    }
  }

  public void requireReady() throws Exception {
    requireConfigured();
    String response = execute("list");
    if (response.isBlank())
      throw new OperationFailure(
          "MINECRAFT_NOT_READY",
          "VERIFYING_STARTUP",
          "Minecraft RCON connected but did not return a readiness response.",
          true);
  }

  private void executeChecked(String command, String code, String phase) throws Exception {
    String response = execute(command);
    String normalized = response.toLowerCase(Locale.ROOT);
    if (normalized.contains("unknown command")
        || normalized.contains("unknown or incomplete command")
        || normalized.contains("incorrect argument"))
      throw new OperationFailure(code, phase, "Minecraft rejected a fixed maintenance command.", true);
  }

  private String execute(String command) throws Exception {
    String secret = readSecret();
    int requestId = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
    try (Socket socket = new Socket()) {
      int timeout = config.commandTimeoutMillis();
      socket.connect(new InetSocketAddress(config.host(), config.port()), timeout);
      socket.setSoTimeout(timeout);
      socket.setTcpNoDelay(true);
      InputStream input = new BufferedInputStream(socket.getInputStream());
      OutputStream output = new BufferedOutputStream(socket.getOutputStream());

      writePacket(output, requestId, TYPE_AUTH, secret);
      Packet auth = readPacket(input);
      if (auth.id == -1 || auth.id != requestId)
        throw new OperationFailure(
            "RCON_AUTH_FAILED",
            "COMMAND_CHANNEL",
            "The Host-local Minecraft command channel rejected authentication.",
            false);

      writePacket(output, requestId, TYPE_COMMAND, command);
      Packet response = readPacket(input);
      if (response.id != requestId || response.type != TYPE_RESPONSE)
        throw new OperationFailure(
            "RCON_PROTOCOL_ERROR",
            "COMMAND_CHANNEL",
            "Minecraft returned an invalid maintenance command response.",
            true);
      return response.body;
    } catch (SocketTimeoutException e) {
      throw new OperationFailure(
          "RCON_TIMEOUT",
          "COMMAND_CHANNEL",
          "The Host-local Minecraft command channel timed out.",
          true);
    } catch (ConnectException e) {
      throw new OperationFailure(
          "RCON_UNREACHABLE",
          "COMMAND_CHANNEL",
          "The Host-local Minecraft command channel is unreachable.",
          true);
    }
  }

  private String readSecret() throws Exception {
    Path path = Path.of(config.secretFile()).toAbsolutePath().normalize();
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || Files.size(path) < 1
        || Files.size(path) > 512)
      throw new OperationFailure(
          "RCON_SECRET_UNAVAILABLE",
          "COMMAND_CHANNEL",
          "The Host-local Minecraft command credential file is unavailable.",
          false);
    String value = Files.readString(path, StandardCharsets.UTF_8).strip();
    if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl))
      throw new OperationFailure(
          "RCON_SECRET_INVALID",
          "COMMAND_CHANNEL",
          "The Host-local Minecraft command credential file is invalid.",
          false);
    return value;
  }

  private static void writePacket(OutputStream output, int id, int type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    int length = 10 + bytes.length;
    writeLeInt(output, length);
    writeLeInt(output, id);
    writeLeInt(output, type);
    output.write(bytes);
    output.write(0);
    output.write(0);
    output.flush();
  }

  private static Packet readPacket(InputStream input) throws IOException {
    int length = readLeInt(input);
    if (length < 10 || length > MAX_PACKET) throw new IOException("Invalid RCON packet length");
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) throw new EOFException("Truncated RCON packet");
    int id = leInt(payload, 0), type = leInt(payload, 4);
    if (payload[length - 1] != 0 || payload[length - 2] != 0)
      throw new IOException("Invalid RCON packet terminator");
    String body = new String(payload, 8, length - 10, StandardCharsets.UTF_8);
    return new Packet(id, type, body);
  }

  private static int readLeInt(InputStream input) throws IOException {
    byte[] bytes = input.readNBytes(4);
    if (bytes.length != 4) throw new EOFException("Truncated RCON packet");
    return leInt(bytes, 0);
  }

  private static int leInt(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff)
        | ((bytes[offset + 1] & 0xff) << 8)
        | ((bytes[offset + 2] & 0xff) << 16)
        | ((bytes[offset + 3] & 0xff) << 24);
  }

  private static void writeLeInt(OutputStream output, int value) throws IOException {
    output.write(value & 0xff);
    output.write((value >>> 8) & 0xff);
    output.write((value >>> 16) & 0xff);
    output.write((value >>> 24) & 0xff);
  }

  private static String warningLabel(int seconds) {
    return switch (seconds) {
      case 1800 -> "30 minutes";
      case 900 -> "15 minutes";
      case 300 -> "5 minutes";
      case 60 -> "1 minute";
      case 30 -> "30 seconds";
      case 15 -> "15 seconds";
      case 10 -> "10 seconds";
      case 5 -> "5 seconds";
      default -> seconds + " seconds";
    };
  }

  private record Packet(int id, int type, String body) {}
}
