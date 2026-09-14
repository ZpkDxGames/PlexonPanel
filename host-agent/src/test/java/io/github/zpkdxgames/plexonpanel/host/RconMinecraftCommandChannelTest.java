package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RconMinecraftCommandChannelTest {
  @TempDir Path temporary;

  @Test
  void onlyFixedMaintenanceCommandsReachRcon() throws Exception {
    Path secret = secret("correct-horse");
    try (FakeRconServer server = new FakeRconServer("correct-horse")) {
      var channel = new RconMinecraftCommandChannel(config(server.port(), secret, 1000));

      assertTrue(
          channel
              .maintenanceNotice(MinecraftCommandChannel.MaintenanceOperation.FULL_BACKUP, 1800)
              .success());
      assertTrue(channel.saveAllFlush().success());
      assertTrue(channel.readinessProbe().success());

      assertEquals(
          List.of(
              "tellraw @a {\"text\":\"Server maintenance: full backup in 30 minutes.\",\"color\":\"yellow\",\"bold\":true}",
              "save-all flush",
              "list"),
          server.commands());
    }

    assertTrue(
        Arrays.stream(RconMinecraftCommandChannel.class.getMethods())
            .noneMatch(method -> Set.of("execute", "command", "sendCommand").contains(method.getName())));
  }

  @Test
  void authenticationFailureIsClassifiedWithoutLeakingSecret() throws Exception {
    Path secret = secret("wrong-secret-value");
    try (FakeRconServer server = new FakeRconServer("expected-secret-value")) {
      var channel = new RconMinecraftCommandChannel(config(server.port(), secret, 1000));
      var result = channel.saveAllFlush();

      assertFalse(result.success());
      assertEquals("RCON_AUTH_FAILED", result.code());
      assertFalse(result.toString().contains("wrong-secret-value"));
      assertTrue(server.commands().isEmpty());
    }
  }

  @Test
  void commandTimeoutIsClassifiedSafely() throws Exception {
    Path secret = secret("timeout-secret");
    try (ServerSocket server =
        new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      Thread stalled =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (Socket ignored = server.accept()) {
                      Thread.sleep(2000);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    } catch (IOException ignored) {
                    }
                  });
      try {
        var channel =
            new RconMinecraftCommandChannel(config(server.getLocalPort(), secret, 250));
        var result = channel.saveAllFlush();
        assertFalse(result.success());
        assertEquals("RCON_TIMEOUT", result.code());
        assertFalse(result.toString().contains("timeout-secret"));
      } finally {
        stalled.interrupt();
        stalled.join(1000);
      }
    }
  }

  @Test
  void secretFileMustNotBeReadableByGroupOrOthers() throws Exception {
    Path secret = temporary.resolve("rcon-insecure.secret");
    Files.writeString(secret, "secret\n");
    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
    var channel = new RconMinecraftCommandChannel(config(25575, secret, 1000));

    var result = channel.saveAllFlush();
    assertFalse(result.success());
    assertEquals("RCON_SECRET_PERMISSIONS", result.code());
  }

  @Test
  void executableSecretFileIsRejected() throws Exception {
    Path secret = temporary.resolve("rcon-executable.secret");
    Files.writeString(secret, "secret\n");
    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rwx------"));
    var channel = new RconMinecraftCommandChannel(config(25575, secret, 1000));

    var result = channel.saveAllFlush();
    assertFalse(result.success());
    assertEquals("RCON_SECRET_PERMISSIONS", result.code());
  }

  private Path secret(String value) throws Exception {
    Path secret = temporary.resolve(UUID.randomUUID() + ".secret");
    Files.writeString(secret, value + "\n");
    Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-------"));
    return secret;
  }

  private static HostConfig.CommandChannelConfig config(int port, Path secret, int timeoutMillis) {
    return new HostConfig.CommandChannelConfig(
        true,
        "127.0.0.1",
        port,
        secret.toAbsolutePath().toString(),
        timeoutMillis,
        30);
  }

  private static final class FakeRconServer implements AutoCloseable {
    private final String expectedSecret;
    private final ServerSocket server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Queue<String> commands = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    FakeRconServer(String expectedSecret) throws Exception {
      this.expectedSecret = expectedSecret;
      this.server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
      executor.submit(this::serve);
    }

    int port() {
      return server.getLocalPort();
    }

    List<String> commands() {
      return List.copyOf(commands);
    }

    private void serve() {
      while (!closed) {
        try (Socket socket = server.accept()) {
          socket.setSoTimeout(3000);
          InputStream input = socket.getInputStream();
          OutputStream output = socket.getOutputStream();
          Packet auth = readPacket(input);
          if (!expectedSecret.equals(auth.body)) {
            writePacket(output, -1, 2, "");
            continue;
          }
          writePacket(output, auth.requestId, 2, "");
          Packet command = readPacket(input);
          commands.add(command.body);
          writePacket(output, command.requestId, 0, "OK");
        } catch (SocketException closedSocket) {
          if (!closed) throw new RuntimeException(closedSocket);
        } catch (Exception error) {
          if (!closed) throw new RuntimeException(error);
        }
      }
    }

    private static Packet readPacket(InputStream input) throws Exception {
      byte[] prefix = input.readNBytes(4);
      if (prefix.length != 4) throw new EOFException();
      int length = ByteBuffer.wrap(prefix).order(ByteOrder.LITTLE_ENDIAN).getInt();
      byte[] packet = input.readNBytes(length);
      if (packet.length != length) throw new EOFException();
      ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
      int requestId = buffer.getInt();
      int type = buffer.getInt();
      byte[] body = new byte[length - 10];
      buffer.get(body);
      if (buffer.get() != 0 || buffer.get() != 0) throw new IOException("bad terminator");
      return new Packet(requestId, type, new String(body, StandardCharsets.UTF_8));
    }

    private static void writePacket(OutputStream output, int requestId, int type, String body)
        throws Exception {
      byte[] payload = body.getBytes(StandardCharsets.UTF_8);
      int length = payload.length + 10;
      ByteBuffer packet = ByteBuffer.allocate(length + 4).order(ByteOrder.LITTLE_ENDIAN);
      packet.putInt(length);
      packet.putInt(requestId);
      packet.putInt(type);
      packet.put(payload);
      packet.put((byte) 0);
      packet.put((byte) 0);
      output.write(packet.array());
      output.flush();
    }

    @Override
    public void close() throws Exception {
      closed = true;
      server.close();
      executor.shutdownNow();
      executor.awaitTermination(2, TimeUnit.SECONDS);
    }

    private record Packet(int requestId, int type, String body) {}
  }
}
