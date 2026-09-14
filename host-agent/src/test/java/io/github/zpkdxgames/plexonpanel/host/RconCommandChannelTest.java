package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RconCommandChannelTest {
  @TempDir Path temporary;

  @Test
  void warningAndSaveUseOnlyFixedCommands() throws Exception {
    Path secret = protectedSecret("test-rcon-password");
    List<String> commands = new CopyOnWriteArrayList<>();
    try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> serving =
          executor.submit(
              () -> {
                try {
                  serveSuccess(server, "test-rcon-password", commands, "Broadcast complete");
                  serveSuccess(server, "test-rcon-password", commands, "Saved the game");
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              });
      RconCommandChannel channel = channel(server.getLocalPort(), secret);
      channel.broadcastBackupWarning(1800);
      channel.saveAllFlush();
      serving.get(3, TimeUnit.SECONDS);
    }

    assertEquals(2, commands.size());
    assertTrue(commands.get(0).startsWith("tellraw @a "));
    assertTrue(commands.get(0).contains("30 minutes"));
    assertEquals("save-all flush", commands.get(1));
    assertTrue(commands.stream().noneMatch(value -> value.contains("test-rcon-password")));
  }

  @Test
  void authenticationFailureIsSafeAndDoesNotExposeSecret() throws Exception {
    Path secret = protectedSecret("do-not-leak-me");
    try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> serving =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  Packet auth = readPacket(socket.getInputStream());
                  writePacket(socket.getOutputStream(), -1, 2, "");
                  assertEquals("do-not-leak-me", auth.payload());
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              });
      OperationFailure failure =
          assertThrows(OperationFailure.class, () -> channel(server.getLocalPort(), secret).saveAllFlush());
      serving.get(3, TimeUnit.SECONDS);
      assertEquals("RCON_AUTH_FAILED", failure.code());
      assertFalse(failure.getMessage().contains("do-not-leak-me"));
      assertFalse(failure.safeData().toString().contains("do-not-leak-me"));
    }
  }

  @Test
  void commandTimeoutIsClassifiedSafely() throws Exception {
    Path secret = protectedSecret("timeout-secret");
    try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<?> serving =
          executor.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  Packet auth = readPacket(socket.getInputStream());
                  assertEquals("timeout-secret", auth.payload());
                  writePacket(socket.getOutputStream(), auth.id(), 2, "");
                  Packet command = readPacket(socket.getInputStream());
                  assertEquals("save-all flush", command.payload());
                  Thread.sleep(600);
                } catch (Exception error) {
                  throw new CompletionException(error);
                }
              });
      RconCommandChannel channel =
          new RconCommandChannel(
              new HostConfig.CommandChannelConfig(
                  true,
                  "127.0.0.1",
                  server.getLocalPort(),
                  secret.toAbsolutePath().toString(),
                  250,
                  250));
      OperationFailure failure =
          assertThrows(OperationFailure.class, channel::saveAllFlush);
      assertEquals("RCON_TIMEOUT", failure.code());
      assertFalse(failure.getMessage().contains("timeout-secret"));
      assertFalse(failure.safeData().toString().contains("timeout-secret"));
      serving.get(2, TimeUnit.SECONDS);
    }
  }

  @Test
  void unsupportedWarningCannotBecomeGenericCommandExecution() {
    RconCommandChannel channel =
        new RconCommandChannel(
            new HostConfig.CommandChannelConfig(
                false, "127.0.0.1", 25575, "", 1000, 1000));
    assertThrows(IllegalArgumentException.class, () -> channel.broadcastBackupWarning(42));
    assertTrue(
        Arrays.stream(RconCommandChannel.class.getDeclaredMethods())
            .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
            .noneMatch(
                method ->
                    Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class})),
        "No public arbitrary String command route may exist");
  }

  @Test
  void worldReadableSecretIsRejectedBeforeNetworkUse() throws Exception {
    Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"));
    Path secret = temporary.resolve("rcon.password");
    Files.writeString(secret, "unsafe-secret\n");
    Files.setPosixFilePermissions(
        secret,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OTHERS_READ));
    RconCommandChannel channel = channel(25575, secret);
    OperationFailure failure =
        assertThrows(OperationFailure.class, () -> channel.broadcastBackupWarning(1800));
    assertEquals("RCON_SECRET_UNAVAILABLE", failure.code());
    assertFalse(failure.getMessage().contains("unsafe-secret"));
  }

  private Path protectedSecret(String value) throws Exception {
    Path secret = temporary.resolve(UUID.randomUUID() + ".password");
    Files.writeString(secret, value + "\n");
    if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
      Files.setPosixFilePermissions(
          secret, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    return secret;
  }

  private static RconCommandChannel channel(int port, Path secret) {
    return new RconCommandChannel(
        new HostConfig.CommandChannelConfig(
            true, "127.0.0.1", port, secret.toAbsolutePath().toString(), 1500, 750));
  }

  private static void serveSuccess(
      ServerSocket server, String password, List<String> commands, String response) throws Exception {
    try (Socket socket = server.accept()) {
      Packet auth = readPacket(socket.getInputStream());
      assertEquals(3, auth.type());
      assertEquals(password, auth.payload());
      writePacket(socket.getOutputStream(), auth.id(), 2, "");
      Packet command = readPacket(socket.getInputStream());
      assertEquals(2, command.type());
      commands.add(command.payload());
      writePacket(socket.getOutputStream(), command.id(), 0, response);
    }
  }

  private static Packet readPacket(InputStream input) throws IOException {
    byte[] lengthBytes = input.readNBytes(4);
    if (lengthBytes.length != 4) throw new EOFException();
    int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    byte[] body = input.readNBytes(length);
    if (body.length != length) throw new EOFException();
    ByteBuffer buffer = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
    int id = buffer.getInt(), type = buffer.getInt();
    int payloadLength = length - 10;
    byte[] payload = new byte[payloadLength];
    buffer.get(payload);
    return new Packet(id, type, new String(payload, StandardCharsets.UTF_8));
  }

  private static void writePacket(OutputStream output, int id, int type, String payload)
      throws IOException {
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    ByteBuffer buffer = ByteBuffer.allocate(bytes.length + 14).order(ByteOrder.LITTLE_ENDIAN);
    buffer.putInt(bytes.length + 10);
    buffer.putInt(id);
    buffer.putInt(type);
    buffer.put(bytes);
    buffer.put((byte) 0).put((byte) 0);
    output.write(buffer.array());
    output.flush();
  }

  private record Packet(int id, int type, String payload) {}
}
