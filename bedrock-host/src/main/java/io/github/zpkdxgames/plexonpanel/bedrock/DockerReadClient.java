package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;

/** Only inspect, one-shot stats, and bounded logs of the pinned Bedrock container. */
final class DockerReadClient {
  static final String CONTAINER_NAME = "tonimbedrock";
  static final int MAX_LOG_LINES = 128;
  private static final String PREFIX = "/v1.44/containers/";
  private static final String INSPECT = PREFIX + CONTAINER_NAME + "/json";
  private static final String STATS = "/stats?stream=false&one-shot=true";
  private static final String LOGS = "/logs?stdout=true&stderr=true&timestamps=true&tail=";
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration STATS_MAX_AGE = Duration.ofSeconds(15);
  private static final Duration STARTUP_WINDOW = Duration.ofSeconds(30);
  private static final double MAX_CPU_PERCENT = 100_000.0;
  private static final long MAX_MEMORY_BYTES = 1L << 50;
  private static final int MAX_INSPECT_BYTES = 131_072;
  private static final int MAX_STATS_BYTES = 131_072;
  private static final int MAX_LOG_BYTES = 524_288;
  private static final Set<String> STATES = Set.of(
      "created", "running", "paused", "restarting", "removing", "exited", "dead");
  private static final Set<String> ACTIVE_STATES = Set.of("running", "paused", "restarting");
  private static final Set<String> HEALTH_STATES = Set.of("starting", "healthy", "unhealthy");
  private static final Set<String> RESTART_POLICIES = Set.of("no", "always", "unless-stopped", "on-failure");
  private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();

  private final DockerUnixHttp http;
  private final Clock clock;
  private final LongSupplier monotonicNanos;
  private CpuSample previousCpu;
  private VersionEvidence version;
  private Startup startup;
  private Target startupProbed;

  DockerReadClient(Path dockerSocket, Clock clock) {
    this(dockerSocket, clock, System::nanoTime, REQUEST_TIMEOUT);
  }

  DockerReadClient(Path dockerSocket, Clock clock, LongSupplier monotonicNanos, Duration timeout) {
    this.http = new DockerUnixHttp(dockerSocket, timeout);
    this.clock = clock;
    this.monotonicNanos = monotonicNanos;
  }

  synchronized BedrockSnapshot snapshot() throws IOException {
    Inspection initial = inspect();
    if (initial == null) {
      previousCpu = null;
      startup = null;
      version = null;
      startupProbed = null;
      return BedrockSnapshot.absent(clock.instant());
    }
    ResourceStats stats = null;
    Inspection current = initial;
    if (initial.running()) {
      stats = readStats(initial.target());
      if (stats != null) {
        // Confirm the name still identifies the same running incarnation after sampling.
        current = inspect();
        if (current == null || !current.running() || !initial.target().equals(current.target())) {
          previousCpu = null;
          throw new IOException("Bedrock container changed during stats collection");
        }
      }
    }

    Instant now = current.capturedAt();
    if (stats != null && !freshStats(stats.capturedAt(), now, current.startedAt())) stats = null;
    Double cpu = cpuPercent(current.target(), stats);
    Long memory = stats == null ? null : stats.memoryBytes();
    Long limit = stats == null ? null : stats.memoryLimitBytes();
    Instant statsAt = cpu == null && memory == null && limit == null ? null : stats.capturedAt();
    Long uptime = current.running() ? Duration.between(current.startedAt(), now).toSeconds() : null;
    forgetOtherVersion(current.target());
    VersionEvidence runningVersion = current.running() ? version : null;
    return new BedrockSnapshot(now, current.state(), current.health(), current.restartPolicy(), current.image(),
        current.createdAt(), current.startedAt(), uptime, cpu, memory, limit, statsAt,
        current.oomKilled(), current.restartCount(), runningVersion == null ? null : runningVersion.value(),
        runningVersion == null ? null : runningVersion.capturedAt());
  }

  synchronized List<BedrockLogLine> logs(Instant since, int limit) throws IOException {
    if (limit < 1 || limit > MAX_LOG_LINES || (since != null &&
        (since.isBefore(Instant.EPOCH) || since.isAfter(clock.instant())))) {
      throw new IOException("Invalid Bedrock log window");
    }
    Inspection initial = inspect();
    if (initial == null || initial.tty() == null || initial.state().equals("unknown")) {
      throw new IOException("Bedrock log target unavailable");
    }
    byte[] payload = readLogs(initial.target(), since, null, limit);
    Inspection current = inspect();
    requireSameLogTarget(initial, current);
    List<BedrockLogLine> lines = DockerLogDecoder.decode(payload, current.tty(), since,
        current.capturedAt(), limit);
    observeVersion(current.target(), lines);
    probePreviousStartup(current);
    return lines;
  }

  private void probePreviousStartup(Inspection current) throws IOException {
    if (!current.running() || version != null || current.target().equals(startupProbed)) return;
    Instant end = current.startedAt().plus(STARTUP_WINDOW);
    if (current.capturedAt().isBefore(end)) return;
    startupProbed = current.target();

    // An already-running container can have startup outside the live console window.
    // Recover bounded evidence only, independently of the logs returned to the Host.
    List<BedrockLogLine> startupLogs;
    try {
      byte[] payload = readLogs(current.target(), current.startedAt(), end, MAX_LOG_LINES);
      startupLogs = DockerLogDecoder.decode(payload, current.tty(), current.startedAt(), end, MAX_LOG_LINES);
    } catch (IOException unavailable) {
      return;
    }
    requireSameLogTarget(current, inspect());
    observeVersion(current.target(), startupLogs);
  }

  private byte[] readLogs(Target target, Instant since, Instant until, int limit) throws IOException {
    // Docker applies tail before until. For the fixed 30-second startup window,
    // rely on the time filter, HTTP caps, and parser result cap instead of tail.
    String path = PREFIX + target.id() + LOGS + (until == null ? Integer.toString(limit) : "all");
    if (since != null) path += "&since=" + epochSeconds(since);
    if (until != null) path += "&until=" + epochSeconds(until);
    DockerUnixHttp.Response response = http.get(path, MAX_LOG_BYTES);
    requireOk(response, "logs");
    return response.body();
  }

  private static String epochSeconds(Instant time) {
    return time.getEpochSecond() + "." + String.format(Locale.ROOT, "%09d", time.getNano());
  }

  private static void requireSameLogTarget(Inspection expected, Inspection actual) throws IOException {
    if (actual == null || actual.state().equals("unknown") || !expected.target().equals(actual.target())
        || !Objects.equals(expected.tty(), actual.tty())) {
      throw new IOException("Bedrock container changed during log collection");
    }
  }

  private Inspection inspect() throws IOException {
    DockerUnixHttp.Response response = http.get(INSPECT, MAX_INSPECT_BYTES);
    if (response.status() == 404) return null;
    requireOk(response, "inspect");
    JsonObject result = parseObject(response.body());
    String id = text(result, "Id");
    if (!("/" + CONTAINER_NAME).equals(text(result, "Name")) || id == null || !id.matches("[0-9a-f]{64}")) {
      throw new IOException("Docker inspect did not identify the pinned Bedrock container");
    }
    JsonObject state = object(result, "State");
    if (state == null) throw new IOException("Docker inspect did not include container state");
    Instant now = clock.instant();
    Instant created = timestamp(result, "Created", now);
    Instant started = timestamp(state, "StartedAt", now);
    if (created == null || (started != null && started.isBefore(created))) started = null;
    String status = text(state, "Status");
    Boolean running = bool(state, "Running");
    Boolean paused = bool(state, "Paused");
    Boolean restarting = bool(state, "Restarting");
    if (status == null || !STATES.contains(status) || running == null || paused == null || restarting == null
        || (status.equals("running") && (!running || paused || restarting || started == null))
        || (status.equals("paused") && (!running || !paused || restarting || started == null))
        || (status.equals("restarting") && (!restarting || paused))
        || (!ACTIVE_STATES.contains(status) && (running || paused || restarting))) {
      status = "unknown";
    }
    JsonObject config = object(result, "Config");
    String image = text(config, "Image");
    if (image != null && !image.matches("[A-Za-z0-9_.:/@-]{1,256}")) image = null;
    String policy = text(object(object(result, "HostConfig"), "RestartPolicy"), "Name");
    if (policy != null && !RESTART_POLICIES.contains(policy)) policy = null;
    String health = text(object(state, "Health"), "Status");
    if (health != null && (!status.equals("running") || !HEALTH_STATES.contains(health))) health = null;
    return new Inspection(new Target(id, started), now, created, started, status, health, policy, image,
        bool(config, "Tty"), bool(state, "OOMKilled"), number(result, "RestartCount", Integer.MAX_VALUE));
  }

  private ResourceStats readStats(Target target) throws IOException {
    JsonObject stats;
    try {
      DockerUnixHttp.Response response = http.get(PREFIX + target.id() + STATS, MAX_STATS_BYTES);
      if (response.status() != 200) return null;
      stats = parseObject(response.body());
    } catch (IOException unavailable) {
      return null;
    }
    // Docker 28 omits id in its one-shot response. The request uses the full container ID,
    // and snapshot revalidates the named container and its launch before accepting the sample.
    if (stats.has("id") && !target.id().equals(text(stats, "id"))) {
      throw new IOException("Docker stats did not identify the pinned Bedrock container");
    }
    Instant now = clock.instant();
    Instant capturedAt = timestamp(stats, "read", now);
    if (!freshStats(capturedAt, now, target.startedAt())) return null;

    JsonObject cpu = object(object(stats, "cpu_stats"), "cpu_usage");
    JsonObject memory = object(stats, "memory_stats");
    return new ResourceStats(capturedAt, monotonicNanos.getAsLong(), number(cpu, "total_usage", Long.MAX_VALUE),
        number(memory, "usage", MAX_MEMORY_BYTES), number(memory, "limit", MAX_MEMORY_BYTES));
  }

  private static boolean freshStats(Instant capturedAt, Instant now, Instant startedAt) {
    return capturedAt != null && !capturedAt.isAfter(now) && !capturedAt.isBefore(startedAt)
        && !capturedAt.isBefore(now.minus(STATS_MAX_AGE));
  }

  private Double cpuPercent(Target target, ResourceStats stats) {
    CpuSample prior = previousCpu;
    previousCpu = stats == null || stats.cpuUsedNanos() == null
        ? null : new CpuSample(target, stats.capturedAt(), stats.monotonicNanos(), stats.cpuUsedNanos());
    if (prior == null || previousCpu == null || !target.equals(prior.target())
        || !previousCpu.capturedAt().isAfter(prior.capturedAt()) || previousCpu.usedNanos() < prior.usedNanos()) {
      return null;
    }
    long elapsed = previousCpu.monotonicNanos() - prior.monotonicNanos();
    if (elapsed < Duration.ofMillis(50).toNanos() || elapsed > Duration.ofSeconds(45).toNanos()) return null;
    double percent = 100.0 * (previousCpu.usedNanos() - prior.usedNanos()) / elapsed;
    return percent <= MAX_CPU_PERCENT ? percent : null;
  }

  private void observeVersion(Target target, List<BedrockLogLine> lines) {
    forgetOtherVersion(target);
    if (target.startedAt() == null) return;
    for (BedrockLogLine line : lines) {
      if (line.truncated() || line.stream().equals("stderr") || line.capturedAt().isBefore(target.startedAt())) continue;
      if (DockerLogDecoder.BDS_START.matcher(line.content()).matches()
          && (startup == null || line.capturedAt().isAfter(startup.capturedAt()))) {
        startup = new Startup(target, line.capturedAt());
        version = null;
      }
      Matcher match = DockerLogDecoder.BDS_VERSION.matcher(line.content());
      if (startup != null && match.matches() && !line.capturedAt().isBefore(startup.capturedAt())
          && !line.capturedAt().isAfter(startup.capturedAt().plusSeconds(10))) {
        version = new VersionEvidence(target, match.group(1), line.capturedAt());
      }
    }
  }

  private void forgetOtherVersion(Target target) {
    if (startup != null && !startup.target().equals(target)) startup = null;
    if (version != null && !version.target().equals(target)) version = null;
  }

  private static void requireOk(DockerUnixHttp.Response response, String operation) throws IOException {
    if (response.status() != 200) throw new IOException("Docker " + operation + " unavailable");
  }

  static JsonObject parseObject(byte[] response) throws IOException {
    try {
      JsonObject result = JSON.fromJson(new String(response, StandardCharsets.UTF_8), JsonObject.class);
      if (result != null) return result;
    } catch (JsonParseException invalid) {
      // Do not propagate parser errors containing Docker response material.
    }
    throw new IOException("Invalid Docker JSON response");
  }

  static JsonObject object(JsonObject value, String key) {
    JsonElement field = value == null ? null : value.get(key);
    return field != null && field.isJsonObject() ? field.getAsJsonObject() : null;
  }

  static String text(JsonObject value, String key) {
    JsonElement field = value == null ? null : value.get(key);
    return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isString()
        ? field.getAsString() : null;
  }

  static Boolean bool(JsonObject value, String key) {
    JsonElement field = value == null ? null : value.get(key);
    return field != null && field.isJsonPrimitive() && field.getAsJsonPrimitive().isBoolean()
        ? field.getAsBoolean() : null;
  }

  static Long number(JsonObject value, String key, long maximum) {
    JsonElement field = value == null ? null : value.get(key);
    if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) return null;
    try {
      long number = field.getAsBigDecimal().longValueExact();
      return number >= 0 && number <= maximum ? number : null;
    } catch (NumberFormatException | ArithmeticException invalid) {
      return null;
    }
  }

  static Instant timestamp(JsonObject value, String key, Instant now) {
    String text = text(value, key);
    if (text == null || text.length() > 40) return null;
    try {
      Instant time = Instant.parse(text);
      return time.isBefore(Instant.EPOCH) || time.isAfter(now) ? null : time;
    } catch (DateTimeParseException invalid) {
      return null;
    }
  }

  private record Target(String id, Instant startedAt) {}

  private record Inspection(Target target, Instant capturedAt, Instant createdAt, Instant startedAt,
                            String state, String health, String restartPolicy, String image, Boolean tty,
                            Boolean oomKilled, Long restartCount) {
    boolean running() { return state.equals("running"); }
  }

  private record ResourceStats(Instant capturedAt, long monotonicNanos, Long cpuUsedNanos,
                               Long memoryBytes, Long memoryLimitBytes) {}

  private record CpuSample(Target target, Instant capturedAt, long monotonicNanos, long usedNanos) {}

  private record Startup(Target target, Instant capturedAt) {}

  private record VersionEvidence(Target target, String value, Instant capturedAt) {}
}
