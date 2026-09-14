package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RconSaveFlushAffirmationTest {
  @TempDir Path temporary;

  @Test
  void explicitCommandRejectionDoesNotAffirmSaveFlush() throws Exception {
    assertRejected("Unknown command. Type \"/help\" for help.");
  }

  @Test
  void blankCommandResponseDoesNotAffirmSaveFlush() throws Exception {
    assertRejected("");
  }

  private void assertRejected(String commandResponse) throws Exception {
    Path secret = temporary.resolve("rcon.secret");
    Files.writeString(secret, "affirmation-secret\n");
    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));

    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> serving =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  Packet auth = readPacket(socket.getInputStream());
                  assertEquals(3, auth.type());
                  assertEquals("affirmation-secret", auth.body());
                  writePacket(socket.getOutputStream(), auth.id(), 2, "");
                  Packet command = readPacket(socket.getInputStream());
                  assertEquals(2, command.type());
                  assertEquals("save-all flush", command.body());
                  writePacket(socket.getOutputStream(), command.id(), 0, commandResponse);
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              });

      var channel =
          new RconMinecraftCommandChannel(
              new HostConfig.CommandChannelConfig(
                  true,
                  "127.0.0.1",
                  server.getLocalPort(),
                  secret.toAbsolutePath().toString(),
                  1000,
                  30));
      var result = channel.saveAllFlush();
      assertFalse(result.success());
      assertEquals("RCON_COMMAND_REJECTED", result.code());
      if (!commandResponse.isEmpty()) assertFalse(result.toString().contains(commandResponse));
      serving.get(2, TimeUnit.SECONDS);
    }
  }

  private static Packet readPacket(InputStream input) throws IOException {
    byte[] lengthBytes = input.readNBytes(4);
    if (lengthBytes.length != 4) throw new EOFException();
    int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    byte[] packet = input.readNBytes(length);
    if (packet.length != length) throw new EOFException();
    ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    int id = buffer.getInt();
    int type = buffer.getInt();
    byte[] body = new byte[length - 10];
    buffer.get(body);
    return new Packet(id, type, new String(body, StandardCharsets.UTF_8));
  }

  private static void writePacket(OutputStream output, int id, int type, String body)
      throws IOException {
    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
    int length = payload.length + 10;
    ByteBuffer packet = ByteBuffer.allocate(length + 4).order(ByteOrder.LITTLE_ENDIAN);
    packet.putInt(length);
    packet.putInt(id);
    packet.putInt(type);
    packet.put(payload);
    packet.put((byte) 0);
    packet.put((byte) 0);
    output.write(packet.array());
    output.flush();
  }

  private record Packet(int id, int type, String body) {}
}
