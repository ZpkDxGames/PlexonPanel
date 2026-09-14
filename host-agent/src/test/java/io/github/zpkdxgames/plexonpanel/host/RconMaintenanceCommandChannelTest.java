package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RconMaintenanceCommandChannelTest {
  @TempDir Path temporary;

  @Test
  void endpointIsRestrictedToLoopback() {
    var config = new HostConfig.MaintenanceCommandConfig(true, "192.0.2.10", 25575, "/tmp/rcon", 1, 5);
    assertThrows(IllegalArgumentException.class, () -> new RconMaintenanceCommandChannel(config));
  }

  @Test
  void authenticationFailureIsClassifiedWithoutLeakingSecret() throws Exception {
    Path secret = secret("super-secret-password");
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ExecutorService worker = Executors.newSingleThreadExecutor();
      Future<?> peer =
          worker.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  InputStream input = socket.getInputStream();
                  OutputStream output = socket.getOutputStream();
                  int length = readIntLe(input);
                  byte[] payload = input.readNBytes(length);
                  assertEquals(length, payload.length);
                  writePacket(output, -1, 2, "");
                } catch (IOException failure) {
                  throw new UncheckedIOException(failure);
                }
              });

      var config =
          new HostConfig.MaintenanceCommandConfig(
              true, "127.0.0.1", server.getLocalPort(), secret.toString(), 2, 5);
      IOException failure =
          assertThrows(IOException.class, () -> new RconMaintenanceCommandChannel(config).saveAllFlush());
      assertEquals("RCON_AUTH_FAILED", failure.getMessage());
      assertFalse(failure.toString().contains("super-secret-password"));
      assertFalse(failure.toString().contains(secret.toString()));
      peer.get(5, TimeUnit.SECONDS);
      worker.shutdownNow();
    }
  }

  @Test
  void commandTimeoutIsClassifiedSafely() throws Exception {
    Path secret = secret("timeout-secret");
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      ExecutorService worker = Executors.newSingleThreadExecutor();
      Future<?> peer =
          worker.submit(
              () -> {
                try (Socket socket = server.accept()) {
                  Thread.sleep(1500L);
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                } catch (IOException failure) {
                  throw new UncheckedIOException(failure);
                }
              });

      var config =
          new HostConfig.MaintenanceCommandConfig(
              true, "127.0.0.1", server.getLocalPort(), secret.toString(), 1, 5);
      IOException failure =
          assertThrows(IOException.class, () -> new RconMaintenanceCommandChannel(config).saveAllFlush());
      assertEquals("RCON_TIMEOUT", failure.getMessage());
      peer.get(5, TimeUnit.SECONDS);
      worker.shutdownNow();
    }
  }

  private Path secret(String value) throws Exception {
    Path file = temporary.resolve("rcon.password");
    Files.writeString(file, value + System.lineSeparator());
    try {
      Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // CI production target is Linux; this keeps the unit test portable.
    }
    return file;
  }

  private static void writePacket(OutputStream output, int requestId, int type, String body)
      throws IOException {
    byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    writeIntLe(output, bytes.length + 10);
    writeIntLe(output, requestId);
    writeIntLe(output, type);
    output.write(bytes);
    output.write(0);
    output.write(0);
    output.flush();
  }

  private static int readIntLe(InputStream input) throws IOException {
    byte[] value = input.readNBytes(4);
    if (value.length != 4) throw new EOFException();
    return (value[0] & 0xff)
        | ((value[1] & 0xff) << 8)
        | ((value[2] & 0xff) << 16)
        | ((value[3] & 0xff) << 24);
  }

  private static void writeIntLe(OutputStream output, int value) throws IOException {
    output.write(value & 0xff);
    output.write((value >>> 8) & 0xff);
    output.write((value >>> 16) & 0xff);
    output.write((value >>> 24) & 0xff);
  }
}
