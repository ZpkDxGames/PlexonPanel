package io.github.zpkdxgames.plexonpanel.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@EnabledOnOs(OS.LINUX)
class DockerReadClientTest {
  private static final String ID = "a".repeat(64);
  private static final String OTHER_ID = "b".repeat(64);
  private static final String INSPECT = "/v1.44/containers/tonimbedrock/json";
  private static final String STATS = "/v1.44/containers/" + ID + "/stats?stream=false&one-shot=true";
  private static final String LOGS = "/v1.44/containers/" + ID
      + "/logs?stdout=true&stderr=true&timestamps=true&tail=";
  private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
  private static final Instant START = NOW.minusSeconds(20);

  @TempDir Path tempDir;

  @Test
  void samplesDockerMemoryAndOneCoreCpuAndResetsAtRestart() throws Exception {
    try (var docker = new Fixture()) {
      String running = running(ID, START, false);
      docker.snapshot(running, stats(ID, NOW.minusSeconds(2), 1_000_000_000, "0", "4000000000"));
      docker.snapshot(running, stats(ID, NOW.minusSeconds(1), 2_500_000_000L, "3067890", "4000000000"));
      docker.snapshot(running(ID, NOW.minusSeconds(2), false),
          stats(ID, NOW, 1_000_000, "207000", "4000000000"));

      DockerReadClient client = docker.client();
      var first = client.snapshot();
      assertEquals("running", first.containerState());
      assertEquals("starting", first.containerHealth());
      assertEquals("unless-stopped", first.containerRestartPolicy());
      assertEquals("itzg/minecraft-bedrock-server:stable", first.containerImage());
      assertEquals(20, first.containerUptimeSeconds());
      assertEquals(START, first.containerStartedAt());
      assertEquals(0, first.containerMemoryBytes());
      assertEquals(4_000_000_000L, first.containerMemoryLimitBytes());
      assertEquals(NOW.minusSeconds(2), first.containerStatsCapturedAt());
      assertNull(first.containerCpuPercent());
      assertTrue(first.toWire().get("gameplayReady").isJsonNull());
      assertTrue(first.toWire().get("playersOnline").isJsonNull());
      assertFalse(first.toWire().toString().contains("should-never-leave-docker"));

      var next = client.snapshot();
      assertEquals(150.0, next.containerCpuPercent());
      assertEquals(3_067_890, next.containerMemoryBytes());
      assertEquals("PERCENT_OF_ONE_CORE", next.toWire().get("cpuUnit").getAsString());
      assertNull(client.snapshot().containerCpuPercent());
    }
  }

  @Test
  void staleMalformedAndUnavailableStatsNeverProduceZeroReadings() throws Exception {
    try (var docker = new Fixture()) {
      String running = running(ID, START, true);
      docker.json(INSPECT, running);
      docker.json(STATS, stats(ID, START.minusSeconds(1), 1_000, "200", "400"));
      docker.json(INSPECT, running);
      docker.json(STATS, stats(ID, NOW.plusSeconds(1), 2_000, "300", "400"));
      docker.json(INSPECT, running);
      docker.reply(STATS, 500, "sensitive Docker error");
      docker.snapshot(running, stats(ID, NOW, 4_000, "\"0\"", "1.2"));

      DockerReadClient client = docker.client();
      for (int i = 0; i < 4; i++) {
        var current = client.snapshot();
        assertEquals("running", current.containerState());
        assertNull(current.containerCpuPercent());
        assertNull(current.containerMemoryBytes());
        assertNull(current.containerMemoryLimitBytes());
        assertNull(current.containerStatsCapturedAt());
        assertTrue(current.toWire().get("containerStatsCapturedAt").isJsonNull());
      }
    }
  }

  @Test
  void rejectsNameIdAndIncarnationMismatch() throws Exception {
    try (var docker = new Fixture()) {
      docker.json(INSPECT, running(ID, START, false).replace("/tonimbedrock", "/other"));
      docker.json(INSPECT, running(ID, START, false));
      docker.json(STATS, stats(OTHER_ID, NOW, 5_000, "100", "900"));
      docker.json(INSPECT, running(ID, START, false));
      docker.json(STATS, stats(ID, NOW, 5_000, "100", "900"));
      docker.json(INSPECT, running(ID, START.plusSeconds(1), false));
      docker.json(INSPECT, running(ID, START, true));
      docker.reply(LOGS + "2", 200, NOW.minusSeconds(1) + " private data\n");
      docker.json(INSPECT, running(OTHER_ID, START, true));

      DockerReadClient client = docker.client();
      assertThrows(IOException.class, client::snapshot);
      assertThrows(IOException.class, client::snapshot);
      assertThrows(IOException.class, client::snapshot);
      assertThrows(IOException.class, () -> client.logs(null, 2));
    }
  }

  @ParameterizedTest
  @MethodSource("nonRunningStates")
  void stopsReportingCpuAndMemoryForOtherStates(String status, boolean running, boolean paused,
                                               boolean restarting, Instant startedAt, String expected)
      throws Exception {
    try (var docker = new Fixture()) {
      docker.json(INSPECT, inspect(ID, status, running, paused, restarting, startedAt, false));
      BedrockSnapshot snapshot = docker.client().snapshot();
      assertEquals(expected, snapshot.containerState());
      assertEquals(7, snapshot.restartCount());
      assertEquals(true, snapshot.oomKilled());
      assertNull(snapshot.containerCpuPercent());
      assertNull(snapshot.containerMemoryBytes());
      assertNull(snapshot.containerUptimeSeconds());
    }
  }

  private static Stream<Arguments> nonRunningStates() {
    return Stream.of(
        Arguments.of("created", false, false, false, Instant.parse("0001-01-01T00:00:00Z"), "created"),
        Arguments.of("exited", false, false, false, START, "exited"),
        Arguments.of("paused", true, true, false, START, "paused"),
        Arguments.of("restarting", true, false, true, START, "restarting"),
        Arguments.of("dead", false, false, false, START, "dead"),
        Arguments.of("running", false, false, false, START, "unknown"),
        Arguments.of("running", true, false, false, NOW.plusSeconds(1), "unknown"));
  }

  @Test
  void distinguishesAbsentMalformedAndUnreachableDocker() throws Exception {
    try (var docker = new Fixture()) {
      docker.reply(INSPECT, 404, "unused");
      docker.reply(INSPECT, 403, "local-path-not-forwarded");
      docker.reply(INSPECT, 200, "{invalid Docker JSON}");
      docker.add(INSPECT, ascii("HTTP/1.1 200 OK\r\nContent-Length: 131073\r\n\r\n"));
      DockerReadClient client = docker.client();
      assertEquals("absent", client.snapshot().containerState());
      assertFalse(assertThrows(IOException.class, client::snapshot).getMessage().contains("local-path"));
      assertThrows(IOException.class, client::snapshot);
      assertThrows(IOException.class, client::snapshot);
    }
    assertThrows(IOException.class,
        () -> new DockerReadClient(tempDir.resolve("missing.sock"), Clock.fixed(NOW, ZoneOffset.UTC)).snapshot());
  }

  @Test
  void readsChunkedTtyLogsAndOnlyReportsVersionMatchedToTheCurrentStart() throws Exception {
    try (var docker = new Fixture()) {
      Instant since = START.plusNanos(15);
      String current = running(ID, START, true);
      String lines = """
          %s before requested time
          %s [2026-11-21 11:59:40:004 INFO] Starting Server\r
          %s [2026-11-21 11:59:40:005 INFO] Version: 1.26.66.1\r
          %s Привет 🦊\r
          """.formatted(START, START.plusMillis(4), START.plusMillis(5), START.plusSeconds(1));
      docker.json(INSPECT, current);
      docker.add(LOGS + "12&since=" + START.getEpochSecond() + ".000000015", chunked(lines));
      docker.json(INSPECT, current);
      docker.snapshot(current, stats(ID, NOW, 5_000, "100", "900"));
      docker.snapshot(running(ID, NOW.minusSeconds(1), true), stats(ID, NOW, 100, "110", "900"));

      DockerReadClient client = docker.client();
      var observed = client.logs(since, 12);
      assertEquals(3, observed.size());
      assertTrue(observed.stream().allMatch(line -> line.stream().equals("combined")));
      assertEquals("Привет 🦊", observed.getLast().content());
      var snapshot = client.snapshot();
      assertEquals("1.26.66.1", snapshot.version());
      assertEquals(START.plusMillis(5), snapshot.versionCapturedAt());
      assertTrue(snapshot.toWire().get("gameplayReady").isJsonNull());
      assertNull(client.snapshot().version());
    }
  }

  @Test
  void rejectsInvalidLogWindowsWithoutOpeningDocker() throws Exception {
    try (var docker = new Fixture()) {
      DockerReadClient client = docker.client();
      assertThrows(IOException.class, () -> client.logs(null, 129));
      assertThrows(IOException.class, () -> client.logs(NOW.plusSeconds(1), 1));
      assertThrows(IOException.class, () -> client.logs(Instant.MIN, 1));
      assertThrows(IOException.class, () -> client.logs(null, 0));
    }
  }

  @Test
  void backfillsRetainedStartupVersionWithoutSendingOldLogsOrProbingAgain() throws Exception {
    try (var docker = new Fixture()) {
      Instant oldStart = NOW.minusSeconds(3_600);
      Instant since = NOW.minusSeconds(600);
      String current = running(ID, oldStart, true);
      String recent = START + " recent log\n";
      String recentRoute = LOGS + "12&since=" + since.getEpochSecond() + ".000000000";
      docker.json(INSPECT, current);
      docker.reply(recentRoute, 200, recent);
      docker.json(INSPECT, current);
      String olderRoute = LOGS + "all&since=" + oldStart.getEpochSecond() + ".000000000&until="
          + oldStart.plusSeconds(30).getEpochSecond() + ".000000000";
      docker.reply(olderRoute, 200, """
          %s [2026-11-21 11:00:01:000 INFO] Starting Server
          %s [2026-11-21 11:00:01:001 INFO] Version: 1.26.66.1
          """.formatted(oldStart.plusSeconds(1), oldStart.plusMillis(1_001)));
      docker.json(INSPECT, current);
      docker.json(INSPECT, current);
      docker.reply(recentRoute, 200, recent);
      docker.json(INSPECT, current);
      docker.snapshot(current, stats(ID, NOW, 5_000, "100", "900"));

      DockerReadClient client = docker.client();
      for (int i = 0; i < 2; i++) {
        var logs = client.logs(since, 12);
        assertEquals(List.of(new BedrockLogLine(START, "combined", "recent log", false)), logs);
      }
      assertEquals("1.26.66.1", client.snapshot().version());
    }
  }

  @Test
  void slowDockerIsStoppedByAnElapsedDeadline() throws Exception {
    try (var docker = new Fixture()) {
      docker.add(INSPECT, null);
      var client = new DockerReadClient(docker.path, Clock.fixed(NOW, ZoneOffset.UTC), System::nanoTime,
          Duration.ofMillis(100));
      assertThrows(IOException.class, client::snapshot);
    }
  }

  private static String running(String id, Instant time, boolean tty) {
    return inspect(id, "running", true, false, false, time, tty);
  }

  private static String inspect(String id, String status, boolean running, boolean paused,
                                boolean restarting, Instant time, boolean tty) {
    return """
        {"Id":"%s","Name":"/tonimbedrock","Created":"%s","RestartCount":7,
         "Config":{"Tty":%s,"Image":"itzg/minecraft-bedrock-server:stable","Env":["PASSWORD=should-never-leave-docker"]},
         "State":{"Status":"%s","Running":%s,"Paused":%s,"Restarting":%s,"StartedAt":"%s","OOMKilled":true,
                  "Health":{"Status":"starting"}}, "HostConfig":{"RestartPolicy":{"Name":"unless-stopped"}}}
        """.formatted(id, NOW.minusSeconds(86_400), tty, status, running, paused, restarting, time);
  }

  private static String stats(String id, Instant time, long cpu, String memory, String limit) {
    return """
        {"id":"%s","read":"%s","cpu_stats":{"cpu_usage":{"total_usage":%d}},
         "memory_stats":{"usage":%s,"limit":%s}}
        """.formatted(id, time, cpu, memory, limit);
  }

  private static byte[] chunked(String value) {
    byte[] body = value.getBytes(StandardCharsets.UTF_8);
    int split = body.length / 2;
    var result = new ByteArrayOutputStream();
    result.writeBytes(ascii("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"));
    result.writeBytes(ascii(Integer.toHexString(split) + "\r\n"));
    result.write(body, 0, split);
    result.writeBytes(ascii("\r\n" + Integer.toHexString(body.length - split) + "\r\n"));
    result.write(body, split, body.length - split);
    result.writeBytes(ascii("\r\n0\r\n\r\n"));
    return result.toByteArray();
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private final class Fixture implements AutoCloseable {
    private final Path path = tempDir.resolve("docker.sock");
    private final ServerSocketChannel server;
    private final Thread worker;
    private final Queue<Exchange> script = new ConcurrentLinkedQueue<>();
    private final List<String> unexpected = new ArrayList<>();

    Fixture() throws IOException {
      server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
      server.bind(UnixDomainSocketAddress.of(path));
      worker = Thread.ofVirtual().start(this::serve);
    }

    DockerReadClient client() {
      AtomicLong monotonic = new AtomicLong();
      return new DockerReadClient(path, Clock.fixed(NOW, ZoneOffset.UTC),
          () -> monotonic.addAndGet(1_000_000_000), Duration.ofSeconds(2));
    }

    void snapshot(String inspect, String stats) {
      json(INSPECT, inspect);
      json(STATS, stats);
      json(INSPECT, inspect);
    }

    void json(String route, String value) {
      reply(route, 200, value);
    }

    void reply(String route, int status, String value) {
      byte[] payload = value.getBytes(StandardCharsets.UTF_8);
      var reply = new ByteArrayOutputStream();
      reply.writeBytes(ascii("HTTP/1.1 " + status + " Response\r\nContent-Length: " + payload.length + "\r\n\r\n"));
      reply.writeBytes(payload);
      add(route, reply.toByteArray());
    }

    void add(String route, byte[] reply) {
      script.add(new Exchange("GET " + route + " HTTP/1.1", reply));
    }

    private void serve() {
      while (server.isOpen()) {
        try (SocketChannel client = server.accept()) {
          var input = new BufferedReader(new InputStreamReader(Channels.newInputStream(client), StandardCharsets.US_ASCII));
          String request = input.readLine();
          for (String header; (header = input.readLine()) != null && !header.isEmpty(); ) { }
          Exchange expected = script.poll();
          if (expected == null || !expected.request().equals(request)) {
            unexpected.add(request == null ? "no request" : request);
          } else if (expected.reply() == null) {
            Thread.sleep(Duration.ofSeconds(10));
          } else {
            ByteBuffer response = ByteBuffer.wrap(expected.reply());
            while (response.hasRemaining()) client.write(response);
          }
        } catch (IOException | InterruptedException closed) {
          return;
        }
      }
    }

    @Override public void close() throws Exception {
      server.close();
      worker.interrupt();
      assertTrue(worker.join(Duration.ofSeconds(2)));
      assertEquals(List.of(), unexpected);
      assertTrue(script.isEmpty(), () -> "Unsent Docker fixture requests: " + script);
    }
  }

  private record Exchange(String request, byte[] reply) {}
}
