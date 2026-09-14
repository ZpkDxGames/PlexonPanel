package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.console.ConsoleClassifier;
import io.github.zpkdxgames.plexonpanel.console.ConsoleLine;
import io.github.zpkdxgames.plexonpanel.console.ConsoleRedactor;
import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;

/**
 * Bounded, read-only journald history for the configured Minecraft systemd unit.
 *
 * <p>The browser supplies only typed filters. The executable, unit, output mode and journalctl
 * flags are fixed locally and never copied from request data.
 */
final class HostConsoleHistory {
  static final int DEFAULT_PAGE_LINES = 100;
  static final int MAX_PAGE_LINES = 100;
  static final int MAX_SCAN_LINES = 400;
  static final int MAX_RESPONSE_BYTES = 42 * 1024;
  static final int MAX_RAW_BYTES = 2 * 1024 * 1024;
  private static final int QUERY_TIMEOUT_SECONDS = 10;
  private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR");
  private static final Set<String> PARAMETERS =
      Set.of("before", "after", "limit", "invocationId", "levels");

  record Query(
      Instant before, Instant after, int limit, String invocationId, Set<String> levels) {
    static Query parse(JsonObject parameters) {
      if (parameters == null) parameters = new JsonObject();
      if (!PARAMETERS.containsAll(parameters.keySet()))
        throw new IllegalArgumentException("Unknown console history parameter");

      Instant before = instant(parameters, "before");
      Instant after = instant(parameters, "after");
      if (before != null && after != null && !after.isBefore(before))
        throw new IllegalArgumentException("Console history window is empty");

      int limit = DEFAULT_PAGE_LINES;
      if (parameters.has("limit")) {
        if (!parameters.get("limit").isJsonPrimitive()
            || !parameters.getAsJsonPrimitive("limit").isNumber())
          throw new IllegalArgumentException("Invalid console history limit");
        try {
          limit = parameters.get("limit").getAsInt();
        } catch (RuntimeException error) {
          throw new IllegalArgumentException("Invalid console history limit", error);
        }
      }
      if (limit < 1 || limit > MAX_PAGE_LINES)
        throw new IllegalArgumentException("Console history limit exceeds maximum page size");

      String invocationId = null;
      if (parameters.has("invocationId")) {
        if (!parameters.get("invocationId").isJsonPrimitive()
            || !parameters.getAsJsonPrimitive("invocationId").isString())
          throw new IllegalArgumentException("Invalid invocation id");
        invocationId = parameters.get("invocationId").getAsString();
        if (!invocationId.matches("(?i)[0-9a-f]{32}"))
          throw new IllegalArgumentException("Invalid invocation id");
        invocationId = invocationId.toLowerCase(Locale.ROOT);
      }

      Set<String> levels = new LinkedHashSet<>();
      if (parameters.has("levels")) {
        if (!parameters.get("levels").isJsonArray())
          throw new IllegalArgumentException("Invalid console severity filter");
        JsonArray values = parameters.getAsJsonArray("levels");
        if (values.isEmpty() || values.size() > LEVELS.size())
          throw new IllegalArgumentException("Invalid console severity filter");
        for (JsonElement value : values) {
          if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Invalid console severity filter");
          String level = value.getAsString().toUpperCase(Locale.ROOT);
          if (!LEVELS.contains(level))
            throw new IllegalArgumentException("Invalid console severity filter");
          levels.add(level);
        }
      }
      return new Query(before, after, limit, invocationId, Set.copyOf(levels));
    }

    private static Instant instant(JsonObject parameters, String name) {
      if (!parameters.has(name)) return null;
      if (!parameters.get(name).isJsonPrimitive()
          || !parameters.getAsJsonPrimitive(name).isString())
        throw new IllegalArgumentException("Invalid console history timestamp");
      String value = parameters.get(name).getAsString();
      if (value.length() > 64) throw new IllegalArgumentException("Invalid console history timestamp");
      try {
        return Instant.parse(value);
      } catch (DateTimeParseException error) {
        throw new IllegalArgumentException("Invalid console history timestamp", error);
      }
    }
  }

  private record ReadResult(
      List<ConsoleLine> lines, boolean hasMore, boolean stoppedEarly, int scanned, int rawBytes) {}

  private final String serviceName;
  private final HostConfig.ConsoleConfig settings;
  private final ConsoleClassifier classifier = new ConsoleClassifier();
  private final ConsoleRedactor redactor;
  private final Gson gson = new Gson();

  HostConsoleHistory(String serviceName, HostConfig.ConsoleConfig settings) {
    this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.redactor = new ConsoleRedactor(settings.redactPatterns());
  }

  Map<String, Object> query(JsonObject parameters, boolean problemsOnly) {
    if (!settings.enabled()) throw new SecurityException("CAPABILITY_DISABLED");
    Query query = Query.parse(parameters);
    int scanLines = scanLines(query.limit());
    List<String> command = command(query, scanLines);
    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      Process running = process;
      ReadResult read;
      try (ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<ReadResult> future = reader.submit(() -> read(running, query, problemsOnly, scanLines));
        try {
          read = future.get(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
          future.cancel(true);
          running.destroyForcibly();
          throw new OperationFailure(
              "CONSOLE_HISTORY_TIMEOUT",
              "CONSOLE_HISTORY",
              "Host journal history query timed out.",
              true,
              null,
              timeout);
        }
      }

      if (read.stoppedEarly() && process.isAlive()) process.destroyForcibly();
      if (!process.waitFor(2, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new OperationFailure(
            "CONSOLE_HISTORY_TIMEOUT",
            "CONSOLE_HISTORY",
            "Host journal history query timed out.",
            true);
      }
      if (!read.stoppedEarly() && process.exitValue() != 0)
        throw new OperationFailure(
            "CONSOLE_HISTORY_UNAVAILABLE",
            "CONSOLE_HISTORY",
            "Host journal history is unavailable under the current local journal permissions.",
            true);

      List<ConsoleLine> lines = read.lines();
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("lines", lines);
      result.put("hasMore", read.hasMore());
      result.put("source", "HOST_JOURNAL");
      result.put("service", serviceName);
      result.put("retentionBounded", true);
      result.put(
          "retentionNotice",
          "History is limited to entries currently retained by systemd-journald on the Host.");
      result.put("maxPageLines", MAX_PAGE_LINES);
      result.put("requestedLimit", query.limit());
      result.put("problemsOnly", problemsOnly);
      if (!lines.isEmpty()) {
        result.put("oldestCapturedAt", lines.getFirst().capturedAt());
        result.put("newestCapturedAt", lines.getLast().capturedAt());
      }
      return Map.copyOf(result);
    } catch (OperationFailure | SecurityException | IllegalArgumentException error) {
      throw error;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new OperationFailure(
          "CONSOLE_HISTORY_INTERRUPTED",
          "CONSOLE_HISTORY",
          "Host journal history query was interrupted.",
          true,
          null,
          interrupted);
    } catch (Exception error) {
      throw new OperationFailure(
          "CONSOLE_HISTORY_UNAVAILABLE",
          "CONSOLE_HISTORY",
          "Host journal history is unavailable.",
          true,
          null,
          error);
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }

  List<String> command(Query query, int scanLines) {
    List<String> command = new ArrayList<>();
    command.add(settings.journalExecutable());
    command.add("--unit");
    command.add(serviceName);
    command.add("--output=json");
    command.add(
        "--output-fields=MESSAGE,__CURSOR,__REALTIME_TIMESTAMP,_SYSTEMD_UNIT,_SYSTEMD_INVOCATION_ID,_PID,PRIORITY");
    command.add("--no-pager");
    command.add("--quiet");
    command.add("--lines=" + Math.min(MAX_SCAN_LINES + 1, Math.max(2, scanLines)));
    if (query.after() != null)
      command.add("--since=" + journalTime(plusMicros(query.after(), 1)));
    if (query.before() != null)
      command.add("--until=" + journalTime(plusMicros(query.before(), -1)));
    if (query.invocationId() != null)
      command.add("_SYSTEMD_INVOCATION_ID=" + query.invocationId());
    return List.copyOf(command);
  }

  private ReadResult read(Process process, Query query, boolean problemsOnly, int scanLines)
      throws IOException {
    List<ConsoleLine> accepted = new ArrayList<>();
    int scanned = 0;
    int rawBytes = 0;
    int responseBytes = 0;
    boolean hasMore = false;
    boolean stoppedEarly = false;

    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String raw;
      while ((raw = reader.readLine()) != null) {
        rawBytes += raw.getBytes(StandardCharsets.UTF_8).length + 1;
        if (rawBytes > MAX_RAW_BYTES) {
          hasMore = true;
          stoppedEarly = true;
          break;
        }
        if (!raw.startsWith("{")) continue;
        scanned++;
        if (scanned > scanLines) {
          hasMore = true;
          stoppedEarly = true;
          break;
        }
        ConsoleLine line = parseJournalLine(raw);
        if (line == null) continue;
        if (problemsOnly && !Set.of("WARN", "ERROR").contains(line.level())) continue;
        if (!query.levels().isEmpty() && !query.levels().contains(line.level())) continue;

        int encodedBytes = gson.toJson(line).getBytes(StandardCharsets.UTF_8).length;
        if (accepted.size() >= query.limit() || responseBytes + encodedBytes > MAX_RESPONSE_BYTES) {
          hasMore = true;
          stoppedEarly = true;
          break;
        }
        accepted.add(line);
        responseBytes += encodedBytes;
      }
    }
    return new ReadResult(List.copyOf(accepted), hasMore, stoppedEarly, scanned, rawBytes);
  }

  ConsoleLine parseJournalLine(String json) {
    try {
      JsonObject value = JsonParser.parseString(json).getAsJsonObject();
      String unit = string(value, "_SYSTEMD_UNIT");
      if (unit != null && !unit.equals(serviceName)) return null;
      String raw = string(value, "MESSAGE");
      if (raw == null || raw.isBlank()) return null;
      byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
      boolean truncated = bytes.length > settings.maximumLineBytes();
      String content = truncated ? truncate(bytes, settings.maximumLineBytes()) : raw;
      content = redactor.redact(content);
      Integer priority = integer(value, "PRIORITY");
      ConsoleClassifier.Classification classification = classifier.classify(content, priority);
      String captured = timestamp(value);
      return new ConsoleLine(
          captured,
          classification.level(),
          content,
          classification.fingerprint(),
          truncated,
          "HOST_JOURNAL",
          bounded(string(value, "__CURSOR"), 4096),
          bounded(string(value, "_SYSTEMD_INVOCATION_ID"), 256),
          serviceName,
          bounded(string(value, "_PID"), 32),
          null,
          0L);
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  private static int scanLines(int limit) {
    return Math.min(MAX_SCAN_LINES, Math.max(limit + 1, limit * 4));
  }

  private static String journalTime(Instant instant) {
    long seconds = instant.getEpochSecond();
    int micros = instant.getNano() / 1000;
    return String.format(Locale.ROOT, "@%d.%06d", seconds, micros);
  }

  private static Instant plusMicros(Instant instant, long micros) {
    try {
      return instant.plusNanos(Math.multiplyExact(micros, 1000L));
    } catch (RuntimeException boundary) {
      throw new IllegalArgumentException("Console history timestamp is out of range", boundary);
    }
  }

  private static String timestamp(JsonObject value) {
    String micros = string(value, "__REALTIME_TIMESTAMP");
    if (micros != null) {
      try {
        long valueMicros = Long.parseLong(micros);
        return Instant.ofEpochSecond(valueMicros / 1_000_000L, (valueMicros % 1_000_000L) * 1000L)
            .toString();
      } catch (RuntimeException ignored) {
      }
    }
    return Instant.now().toString();
  }

  private static String string(JsonObject value, String key) {
    JsonElement element = value.get(key);
    if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
    try {
      return element.getAsString();
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private static Integer integer(JsonObject value, String key) {
    String raw = string(value, key);
    if (raw == null) return null;
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private static String bounded(String value, int maximum) {
    if (value == null) return null;
    return value.length() <= maximum ? value : value.substring(0, maximum);
  }

  private static String truncate(byte[] bytes, int maximum) {
    int length = Math.min(maximum, bytes.length);
    while (length > 0 && (bytes[length - 1] & 0xC0) == 0x80) length--;
    return new String(bytes, 0, length, StandardCharsets.UTF_8) + " …[truncated]";
  }
}
