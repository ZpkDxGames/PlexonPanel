package io.github.zpkdxgames.plexonpanel.bedrock;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase.EXITED;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase.RUNNING;
import static io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Readiness.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledOnOs(OS.LINUX)
class BedrockDockerOperationsTest {
  private static final String ID = "a".repeat(64);
  private static final String IMAGE = "sha256:" + "b".repeat(64);
  private static final String INSPECT = "GET /v1.44/containers/tonimbedrock/json";
  private static final String PREFIX = "POST /v1.44/containers/" + ID;
  private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
  private static final Instant START = NOW.minusSeconds(20);

  @TempDir Path temp;

  @Test
  void stopAndStartAreOnlySentToTheInspectedImmutableContainer() throws Exception {
    try (var docker = new Fixture(List.of(
        inspect("running", START), inspect("running", START),
        Reply.empty(PREFIX + "/stop?signal=SIGTERM&t=60"), inspect("exited", START),
        inspect("exited", START), Reply.empty(PREFIX + "/start"),
        inspect("running", START.plusSeconds(1))))) {
      var operations = docker.operations();
      var running = operations.inspect();
      assertEquals(RUNNING, running.phase());
      operations.stop(running, Duration.ofSeconds(60));
      var stopped = operations.inspect();
      assertEquals(EXITED, stopped.phase());
      assertEquals(0, stopped.exitCode());
      operations.start(stopped);
      var started = operations.inspect();
      assertEquals(START.plusSeconds(1), started.startedAt());
      assertEquals(UNKNOWN, started.readiness());
      assertEquals("", docker.attachedBytes());
    }
  }

  @Test
  void doesNotDispatchWhenImageMountOrLaunchChanges() throws Exception {
    Reply correct = inspect("running", START);
    try (var docker = new Fixture(List.of(
        correct.withBody(correct.body().replace(IMAGE, "sha256:" + "c".repeat(64))),
        correct.withBody(correct.body().replace("/fixture/data", "/unapproved")),
        correct, inspect("running", START.plusSeconds(1))))) {
      var operations = docker.operations();
      assertThrows(IOException.class, operations::inspect);
      assertThrows(IOException.class, operations::inspect);
      var previousLaunch = operations.inspect();
      assertThrows(IOException.class, () -> operations.stop(previousLaunch, Duration.ofSeconds(60)));
      assertEquals("", docker.attachedBytes());
    }
  }

  @Test
  void onlyWritesOneAllowedLineAfterRecheckingTheUpgradedLaunch() throws Exception {
    Reply running = inspect("running", START);
    try (var docker = new Fixture(List.of(running, running, Reply.attach(), running))) {
      var operations = docker.operations();
      var expected = operations.inspect();
      assertThrows(IOException.class,
          () -> operations.writeLine(expected, "list\nstop\n".getBytes(StandardCharsets.UTF_8)));
      assertThrows(IOException.class,
          () -> operations.writeLine(expected, "stop\n".getBytes(StandardCharsets.UTF_8)));
      operations.writeLine(expected, "allowlist add \"A Player\"\n".getBytes(StandardCharsets.UTF_8));
      assertEquals("allowlist add \"A Player\"\n", docker.attachedBytes());
    }
  }

  @Test
  void blocksAttachForIncompatibleStdinAndForReplacementDuringUpgrade() throws Exception {
    Reply running = inspect("running", START);
    try (var docker = new Fixture(List.of(
        running, running.withBody(running.body().replace("\"StdinOnce\":false", "\"StdinOnce\":true")),
        running, Reply.attach(), inspect("running", START.plusSeconds(1))))) {
      var operations = docker.operations();
      var expected = operations.inspect();
      byte[] command = "list\n".getBytes(StandardCharsets.UTF_8);
      assertThrows(IOException.class, () -> operations.writeLine(expected, command));
      assertThrows(IOException.class, () -> operations.writeLine(expected, command));
      assertEquals("", docker.attachedBytes());
    }
  }

  private static Reply inspect(String status, Instant started) {
    return new Reply(INSPECT, """
        {"Id":"%s","Name":"/tonimbedrock","Image":"%s","Created":"%s",
         "Config":{"Tty":true,"OpenStdin":true,"StdinOnce":false,"StopSignal":"SIGTERM"},
         "State":{"Status":"%s","Running":%s,"Paused":false,"Restarting":false,"Dead":false,
                   "OOMKilled":false,"ExitCode":0,"StartedAt":"%s","Health":{"Status":"healthy"}},
         "HostConfig":{"RestartPolicy":{"Name":"unless-stopped"}},
         "Mounts":[{"Type":"bind","Source":"/fixture/data","Destination":"/data","RW":true}]}
        """.formatted(ID, IMAGE, NOW.minusSeconds(60), status, status.equals("running"), started), false);
  }

  private record Reply(String path, String body, boolean upgraded) {
    static Reply empty(String path) {
      return new Reply(path, null, false);
    }

    static Reply attach() {
      return new Reply(PREFIX + "/attach?stream=1&stdin=1&stdout=0&stderr=0&logs=0", null, true);
    }

    Reply withBody(String value) {
      return new Reply(path, value, upgraded);
    }

    String response() {
      if (upgraded) {
        return "HTTP/1.1 101 UPGRADED\r\nConnection: Upgrade\r\nUpgrade: tcp\r\n\r\n";
      }
      if (body == null) {
        return "HTTP/1.1 204 No Content\r\n\r\n";
      }
      return "HTTP/1.1 200 OK\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }
  }

  private final class Fixture implements AutoCloseable {
    private final Path socket = temp.resolve("docker.sock");
    private final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    private final FutureTask<String> task;

    Fixture(List<Reply> replies) throws IOException {
      server.bind(UnixDomainSocketAddress.of(socket));
      task = new FutureTask<>(() -> respond(replies));
      Thread.ofVirtual().start(task);
    }

    BedrockDockerOperations operations() {
      return new BedrockDockerOperations(socket, Path.of("/fixture/data"), IMAGE, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    String attachedBytes() throws Exception {
      try {
        return task.get(6, TimeUnit.SECONDS);
      } catch (ExecutionException failed) {
        throw new AssertionError("Docker fixture request sequence differed", failed.getCause());
      }
    }

    private String respond(List<Reply> replies) throws IOException {
      SocketChannel attached = null;
      try {
        for (Reply reply : replies) {
          SocketChannel client = server.accept();
          try {
            String headers = readHeaders(client);
            assertEquals(reply.path() + " HTTP/1.1", headers.split("\r\n", 2)[0]);
            Channels.newOutputStream(client).write(reply.response().getBytes(StandardCharsets.UTF_8));
            if (reply.upgraded()) {
              attached = client;
            }
          } finally {
            if (client != attached) {
              client.close();
            }
          }
        }
        return attached == null ? "" : new String(Channels.newInputStream(attached).readAllBytes(), StandardCharsets.UTF_8);
      } finally {
        if (attached != null) {
          attached.close();
        }
      }
    }

    @Override
    public void close() throws IOException {
      server.close();
      task.cancel(true);
      java.nio.file.Files.deleteIfExists(socket);
    }
  }

  private static String readHeaders(SocketChannel channel) throws IOException {
    var input = Channels.newInputStream(channel);
    var headers = new StringBuilder();
    while (headers.length() < 8192) {
      int next = input.read();
      if (next < 0) break;
      headers.append((char) next);
      if (headers.length() >= 4 && headers.substring(headers.length() - 4).equals("\r\n\r\n")) {
        return headers.toString();
      }
    }
    throw new IOException("Incomplete fixture request headers");
  }
}
