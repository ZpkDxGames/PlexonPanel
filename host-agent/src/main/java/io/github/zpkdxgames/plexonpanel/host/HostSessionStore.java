package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.console.ConsoleLine;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Private, current-Minecraft-boot history. The browser receives bounded cursor pages only. */
public final class HostSessionStore implements AutoCloseable {
  private static final Gson JSON = new Gson();
  private static final int RESPONSE_BYTES = 40 * 1024;
  private static final Pattern LOG_PREFIX =
      Pattern.compile(
          "^(?:\\[\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:"
              + " (?:INFO|WARN|ERROR|FATAL|DEBUG|TRACE))?\\]:?\\s*|\\[[^\\]\\r"
              + "\\n"
              + "]+/(?:INFO|WARN|ERROR|FATAL|DEBUG|TRACE)\\]:?\\s*)");
  private static final Pattern IDENTITY =
      Pattern.compile("UUID of player ([A-Za-z0-9_]{1,16}) is ([0-9a-fA-F-]{36})");
  private static final Pattern PRESENCE =
      Pattern.compile("(?:^|\\s)([A-Za-z0-9_]{1,16}) (joined|left) the game(?:$|\\s)");
  private static final Pattern CHAT =
      Pattern.compile("(?:^|.*?\\]:\\s*)<([A-Za-z0-9_]{1,16})> (.*)$");
  private final Connection database;
  private final Path snapshot;
  private final Path serverRoot;
  private final Map<String, String> playerIds = new HashMap<>();
  private long userCacheReadAt;
  private String epoch;
  private String invocation;
  private String serviceState;
  private boolean stopped;

  public HostSessionStore(Path dataDirectory, Path serverRoot) throws IOException {
    this.serverRoot = serverRoot;
    Path directory = dataDirectory.toAbsolutePath().normalize().resolve("telemetry");
    if (Files.isSymbolicLink(directory)) throw new IOException("SESSION_DIRECTORY_SYMLINK");
    Files.createDirectories(directory);
    privateMode(directory, "rwx------");
    Path path = directory.resolve("session-telemetry.sqlite");
    snapshot = directory.resolve("player-events-current.json");
    if (Files.isSymbolicLink(path) || Files.isSymbolicLink(snapshot))
      throw new IOException("SESSION_FILE_SYMLINK");
    try {
      if (!Files.exists(path))
        Files.createFile(
            path,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      privateMode(path, "rw-------");
      database = DriverManager.getConnection("jdbc:sqlite:" + path);
      try (Statement s = database.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        s.execute("PRAGMA synchronous=FULL");
        s.execute("PRAGMA busy_timeout=5000");
        s.execute(
            "CREATE TABLE IF NOT EXISTS session_meta (key TEXT PRIMARY KEY,value TEXT NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS console_logs (id INTEGER PRIMARY KEY,epoch TEXT NOT"
                + " NULL,captured_at TEXT NOT NULL,level TEXT NOT NULL,payload TEXT NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS server_events (id INTEGER PRIMARY KEY,epoch TEXT NOT"
                + " NULL,observed_at TEXT NOT NULL,event_type TEXT NOT NULL,payload TEXT NOT"
                + " NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS player_presence_events (id INTEGER PRIMARY KEY,epoch TEXT"
                + " NOT NULL,uuid TEXT NOT NULL,name TEXT NOT NULL,state TEXT NOT NULL,observed_at"
                + " TEXT NOT NULL,payload TEXT NOT NULL)");
        s.execute("CREATE INDEX IF NOT EXISTS presence_uuid ON player_presence_events(uuid,id)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS chat_messages (id INTEGER PRIMARY KEY,epoch TEXT NOT"
                + " NULL,observed_at TEXT NOT NULL,payload TEXT NOT NULL)");
      }
      try (Statement index = database.createStatement()) {
        index.execute(
            "CREATE INDEX IF NOT EXISTS console_journal_identity ON"
                + " console_logs(epoch,json_extract(payload,'$.journalCursor'))");
      }
      epoch = meta("epoch", UUID.randomUUID().toString());
      invocation = meta("invocation", "");
      serviceState = meta("serviceState", "unknown");
      stopped = Boolean.parseBoolean(meta("stopped", "false"));
      putMeta("epoch", epoch);
      writePlayerSnapshot();
    } catch (SQLException error) {
      throw new IOException("SESSION_STORE_OPEN_FAILED", error);
    }
  }

  public synchronized String epoch() {
    return epoch;
  }

  /** Returns reset proof only after a new invocation or a complete stop/start transition. */
  public synchronized Optional<Map<String, Object>> observeService(Map<String, Object> status)
      throws IOException {
    String state = String.valueOf(status.getOrDefault("state", "unknown"));
    String id = String.valueOf(status.getOrDefault("invocationId", ""));
    if (!id.matches("[A-Za-z0-9_-]{0,96}")) throw new IOException("INVALID_SERVICE_INVOCATION");
    boolean active = Set.of("active", "activating").contains(state);
    boolean reset =
        active && (!id.isBlank() && !id.equals(invocation) || stopped && state.equals("active"));
    String previous = epoch;
    try {
      database.setAutoCommit(false);
      if (reset) {
        epoch = !id.isBlank() ? id : UUID.randomUUID().toString();
        try (Statement s = database.createStatement()) {
          for (String table :
              List.of("console_logs", "server_events", "player_presence_events", "chat_messages"))
            s.executeUpdate("DELETE FROM " + table);
        }
        playerIds.clear();
        userCacheReadAt = 0;
        putMeta("epoch", epoch);
      }
      if (!id.isBlank() && active) {
        invocation = id;
        putMeta("invocation", id);
      }
      if (reset) stopped = false;
      if (Set.of("inactive", "failed").contains(state)) stopped = true;
      else if (state.equals("active")) stopped = false;
      putMeta("stopped", Boolean.toString(stopped));
      if (reset || !state.equals(serviceState))
        insertEvent(
            reset ? "session.epoch_reset" : "service.state",
            Map.of(
                "previousEpoch",
                previous,
                "epoch",
                epoch,
                "state",
                state,
                "capturedAt",
                Instant.now().toString()));
      serviceState = state;
      putMeta("serviceState", state);
      database.commit();
    } catch (SQLException error) {
      rollback();
      throw new IOException("SESSION_EPOCH_WRITE_FAILED", error);
    } finally {
      autoCommit();
    }
    if (reset) writePlayerSnapshot();
    return reset
        ? Optional.of(
            Map.of(
                "previousEpoch",
                previous,
                "epoch",
                epoch,
                "invocationId",
                invocation,
                "capturedAt",
                Instant.now().toString()))
        : Optional.empty();
  }

  /** Called by the always-on journal producer, independently of relay connectivity. */
  public synchronized void append(ConsoleLine line) throws IOException {
    if (line == null) return;
    if (line.invocationId() != null
        && !invocation.isBlank()
        && !line.invocationId().equals(invocation)) return;
    if (line.journalCursor() != null && line.journalCursor().length() <= 4096) {
      try (PreparedStatement check =
          database.prepareStatement(
              "SELECT 1 FROM console_logs WHERE epoch=? AND"
                  + " json_extract(payload,'$.journalCursor')=? LIMIT 1")) {
        check.setString(1, epoch);
        check.setString(2, line.journalCursor());
        try (ResultSet found = check.executeQuery()) {
          if (found.next()) return;
        }
      } catch (SQLException error) {
        throw new IOException("SESSION_IDENTITY_QUERY_FAILED", error);
      }
    }
    String at = line.capturedAt() == null ? Instant.now().toString() : line.capturedAt();
    String content = line.content() == null ? "" : line.content();
    boolean cut = content.getBytes(StandardCharsets.UTF_8).length > 8192;
    while (content.getBytes(StandardCharsets.UTF_8).length > 8192)
      content = content.substring(0, Math.max(0, content.length() - 256));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("capturedAt", at);
    payload.put("level", line.level() == null ? "INFO" : line.level());
    payload.put("content", content);
    payload.put("source", "HOST_JOURNAL");
    payload.put("truncated", line.truncated() || cut);
    payload.put("invocationId", invocation);
    payload.put("epoch", epoch);
    if (line.journalCursor() != null && line.journalCursor().length() <= 4096)
      payload.put("journalCursor", line.journalCursor());
    if (line.streamSession() != null && line.streamSession().length() <= 128) {
      payload.put("streamSession", line.streamSession());
      payload.put("sourceSequence", line.sourceSequence());
    }
    while (JSON.toJson(payload).getBytes(StandardCharsets.UTF_8).length > 8192) {
      if (content.isEmpty()) throw new IOException("SESSION_LINE_METADATA_LIMIT");
      content = content.substring(0, Math.max(0, content.length() - 256));
      payload.put("content", content);
      payload.put("truncated", true);
    }
    try {
      try (PreparedStatement p =
          database.prepareStatement(
              "INSERT INTO console_logs(epoch,captured_at,level,payload) VALUES(?,?,?,?)")) {
        p.setString(1, epoch);
        p.setString(2, at);
        p.setString(3, String.valueOf(payload.get("level")));
        p.setString(4, JSON.toJson(payload));
        p.executeUpdate();
      }
      String eventBody = eventBody(content);
      var identity = IDENTITY.matcher(eventBody);
      if (identity.matches()) {
        try {
          playerIds.put(
              identity.group(1).toLowerCase(Locale.ROOT),
              UUID.fromString(identity.group(2)).toString());
        } catch (IllegalArgumentException ignored) {
        }
      }
      var presence = PRESENCE.matcher(eventBody);
      if (presence.matches()) {
        String name = presence.group(1), uuid = resolveUuid(name);
        if (uuid != null) {
          String state = presence.group(2).equals("joined") ? "JOINED" : "LEFT";
          Map<String, Object> event =
              new LinkedHashMap<>(
                  Map.of(
                      "eventId",
                      UUID.randomUUID().toString(),
                      "uuid",
                      uuid,
                      "name",
                      name,
                      "state",
                      state,
                      "observedAt",
                      at,
                      "epoch",
                      epoch,
                      "termination",
                      state.equals("JOINED") ? "OPEN" : "OBSERVED_LEAVE"));
          if (state.equals("LEFT")) {
            try (PreparedStatement previous =
                database.prepareStatement(
                    "SELECT state,observed_at FROM player_presence_events WHERE epoch=? AND uuid=?"
                        + " ORDER BY id DESC LIMIT 1")) {
              previous.setString(1, epoch);
              previous.setString(2, uuid);
              try (ResultSet row = previous.executeQuery()) {
                if (row.next() && row.getString(1).equals("JOINED")) {
                  try {
                    long duration =
                        java.time.Duration.between(
                                Instant.parse(row.getString(2)), Instant.parse(at))
                            .toMillis();
                    if (duration >= 0) event.put("durationMillis", duration);
                  } catch (java.time.DateTimeException | ArithmeticException ignored) {
                  }
                }
              }
            }
          }
          try (PreparedStatement p =
              database.prepareStatement(
                  "INSERT INTO player_presence_events(epoch,uuid,name,state,observed_at,payload)"
                      + " VALUES(?,?,?,?,?,?)")) {
            p.setString(1, epoch);
            p.setString(2, uuid);
            p.setString(3, name);
            p.setString(4, state);
            p.setString(5, at);
            p.setString(6, JSON.toJson(event));
            p.executeUpdate();
          }
          writePlayerSnapshot();
        }
      }
      var chat = CHAT.matcher(content);
      if (chat.matches()) {
        Map<String, Object> message =
            Map.of(
                "playerName",
                chat.group(1),
                "message",
                chat.group(2),
                "capturedAt",
                at,
                "epoch",
                epoch);
        try (PreparedStatement p =
            database.prepareStatement(
                "INSERT INTO chat_messages(epoch,observed_at,payload) VALUES(?,?,?)")) {
          p.setString(1, epoch);
          p.setString(2, at);
          p.setString(3, JSON.toJson(message));
          p.executeUpdate();
        }
      }
    } catch (SQLException error) {
      throw new IOException("SESSION_LOG_WRITE_FAILED", error);
    }
  }

  public synchronized Map<String, Object> query(JsonObject input, boolean errorsOnly)
      throws IOException {
    if (input != null && input.has("stream")) {
      if (errorsOnly || !input.get("stream").getAsString().equals("chat"))
        throw new IllegalArgumentException("INVALID_HISTORY_STREAM");
      return queryChat(input);
    }
    HostConsoleHistory.Query q = HostConsoleHistory.Query.parse(input);
    long before = cursor(q.cursor());
    if (q.invocationId() != null && !q.invocationId().equals(invocation))
      throw new IllegalArgumentException("CURSOR_EPOCH_EXPIRED");
    String where = "epoch=? AND id<?";
    if (errorsOnly) where += " AND level IN ('WARN','ERROR','FATAL')";
    if (q.before() != null) where += " AND captured_at<?";
    if (q.after() != null) where += " AND captured_at>?";
    if (!q.levels().isEmpty())
      where +=
          " AND level IN (" + String.join(",", Collections.nCopies(q.levels().size(), "?")) + ")";
    List<Map<String, Object>> rows = new ArrayList<>();
    boolean more = false;
    long last = 0;
    int bytes = 512;
    try (PreparedStatement p =
        database.prepareStatement(
            "SELECT id,payload FROM console_logs WHERE " + where + " ORDER BY id DESC LIMIT ?")) {
      int i = 1;
      p.setString(i++, epoch);
      p.setLong(i++, before);
      if (q.before() != null) p.setString(i++, q.before().toString());
      if (q.after() != null) p.setString(i++, q.after().toString());
      for (String level : q.levels()) p.setString(i++, level);
      p.setInt(i, q.limit() + 1);
      try (ResultSet result = p.executeQuery()) {
        while (result.next()) {
          String json = result.getString(2);
          int size = json.getBytes(StandardCharsets.UTF_8).length + 100;
          if (rows.size() >= q.limit() || bytes + size > RESPONSE_BYTES) {
            more = true;
            break;
          }
          Map<String, Object> row = map(json);
          last = result.getLong(1);
          row.put("id", epoch + ":" + last);
          rows.add(row);
          bytes += size;
        }
      }
    } catch (SQLException error) {
      throw new IOException("SESSION_QUERY_FAILED", error);
    }
    Collections.reverse(rows);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("lines", rows);
    response.put("hasMore", more);
    response.put("nextCursor", more && last > 0 ? encode(last) : null);
    response.put("epoch", epoch);
    response.put("capturedAt", Instant.now().toString());
    response.put("boundedWindow", true);
    response.put("source", "HOST");
    response.put("historyEnabled", true);
    return response;
  }

  private Map<String, Object> queryChat(JsonObject p) throws IOException {
    if (!Set.of("stream", "cursor", "limit").containsAll(p.keySet()))
      throw new IllegalArgumentException("UNKNOWN_CHAT_HISTORY_PARAMETER");
    int limit = p.has("limit") ? p.get("limit").getAsInt() : 25;
    if (limit < 1 || limit > 25) throw new IllegalArgumentException("CHAT_PAGE_LIMIT");
    long before = cursor(p.has("cursor") ? p.get("cursor").getAsString() : null), last = 0;
    int bytes = 512;
    boolean more = false;
    List<Map<String, Object>> messages = new ArrayList<>();
    try (PreparedStatement q =
        database.prepareStatement(
            "SELECT id,payload FROM chat_messages WHERE epoch=? AND id<? ORDER BY id DESC LIMIT"
                + " ?")) {
      q.setString(1, epoch);
      q.setLong(2, before);
      q.setInt(3, limit + 1);
      try (ResultSet result = q.executeQuery()) {
        while (result.next()) {
          String json = result.getString(2);
          int size = json.getBytes(StandardCharsets.UTF_8).length + 80;
          if (messages.size() >= limit || bytes + size > RESPONSE_BYTES) {
            more = true;
            break;
          }
          last = result.getLong(1);
          var row = map(json);
          row.put("id", epoch + ":chat:" + last);
          messages.add(row);
          bytes += size;
        }
      }
    } catch (SQLException error) {
      throw new IOException("CHAT_QUERY_FAILED", error);
    }
    Collections.reverse(messages);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("chatMessages", messages);
    response.put("nextCursor", more && last > 0 ? encode(last) : null);
    response.put("hasMore", more);
    response.put("epoch", epoch);
    response.put("source", "HOST");
    response.put("capturedAt", Instant.now().toString());
    return response;
  }

  public synchronized Map<String, Object> history(JsonObject p) throws IOException {
    if (!Set.of("query", "cursor", "limit").containsAll(p.keySet()))
      throw new IllegalArgumentException("UNKNOWN_HISTORY_PARAMETER");
    String uuid = p.has("query") ? p.get("query").getAsString() : "";
    if (!uuid.isBlank()) {
      try {
        uuid = UUID.fromString(uuid).toString();
      } catch (IllegalArgumentException error) {
        throw new IllegalArgumentException("EXACT_UUID_REQUIRED");
      }
    }
    int limit = p.has("limit") ? p.get("limit").getAsInt() : 25;
    if (limit < 1 || limit > 25) throw new IllegalArgumentException("HISTORY_PAGE_LIMIT");
    long before = cursor(p.has("cursor") ? p.get("cursor").getAsString() : null), last = 0;
    boolean more = false;
    List<Map<String, Object>> entries = new ArrayList<>();
    try (PreparedStatement s =
        database.prepareStatement(
            "SELECT id,payload FROM player_presence_events WHERE epoch=? AND id<?"
                + (uuid.isBlank() ? "" : " AND uuid=?")
                + " ORDER BY id DESC LIMIT ?")) {
      s.setString(1, epoch);
      s.setLong(2, before);
      int i = 3;
      if (!uuid.isBlank()) s.setString(i++, uuid);
      s.setInt(i, limit + 1);
      try (ResultSet result = s.executeQuery()) {
        while (result.next()) {
          if (entries.size() == limit) {
            more = true;
            break;
          }
          last = result.getLong(1);
          entries.add(map(result.getString(2)));
        }
      }
    } catch (SQLException error) {
      throw new IOException("PRESENCE_QUERY_FAILED", error);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("entries", entries);
    response.put("hasMore", more);
    response.put("nextCursor", more ? encode(last) : null);
    response.put("historyEnabled", true);
    response.put("boundedWindow", true);
    response.put("epoch", epoch);
    response.put("capturedAt", Instant.now().toString());
    response.putAll(presenceSummary(uuid));
    return response;
  }

  private Map<String, Object> presenceSummary(String uuid) throws IOException {
    Map<String, Object> result = new LinkedHashMap<>();
    String where = " WHERE epoch=?" + (uuid.isBlank() ? "" : " AND uuid=?");
    try (PreparedStatement q =
        database.prepareStatement(
            "SELECT COUNT(DISTINCT CASE WHEN state='JOINED' THEN uuid END),AVG(CASE WHEN"
                + " state='LEFT' THEN json_extract(payload,'$.durationMillis') END) FROM"
                + " player_presence_events"
                + where)) {
      q.setString(1, epoch);
      if (!uuid.isBlank()) q.setString(2, uuid);
      try (ResultSet row = q.executeQuery()) {
        if (row.next()) {
          result.put("uniqueJoins", row.getInt(1));
          double mean = row.getDouble(2);
          if (!row.wasNull()) result.put("meanClosedSessionMillis", mean);
        }
      }
    } catch (SQLException error) {
      throw new IOException("PRESENCE_SUMMARY_FAILED", error);
    }
    for (String direction : List.of("ASC", "DESC")) {
      try (PreparedStatement q =
          database.prepareStatement(
              "SELECT observed_at FROM player_presence_events"
                  + where
                  + " ORDER BY id "
                  + direction
                  + " LIMIT 1")) {
        q.setString(1, epoch);
        if (!uuid.isBlank()) q.setString(2, uuid);
        try (ResultSet row = q.executeQuery()) {
          if (row.next())
            result.put(direction.equals("ASC") ? "firstSeen" : "lastSeen", row.getString(1));
        }
      } catch (SQLException error) {
        throw new IOException("PRESENCE_SEEN_QUERY_FAILED", error);
      }
    }
    return result;
  }

  private static String eventBody(String content) {
    String body = content;
    for (int i = 0; i < 4; i++) {
      var prefix = LOG_PREFIX.matcher(body);
      if (!prefix.find()) break;
      body = body.substring(prefix.end());
    }
    return body.strip();
  }

  private String resolveUuid(String name) throws IOException {
    String key = name.toLowerCase(Locale.ROOT);
    if (playerIds.containsKey(key)) return playerIds.get(key);
    long now = System.currentTimeMillis();
    if (now - userCacheReadAt < 30000) return null;
    userCacheReadAt = now;
    Path cache = serverRoot.resolve("usercache.json");
    if (Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)
        && !Files.isSymbolicLink(cache)
        && Files.size(cache) <= 2 * 1024 * 1024) {
      try {
        for (JsonElement element :
            JsonParser.parseString(Files.readString(cache)).getAsJsonArray()) {
          var v = element.getAsJsonObject();
          if (v.has("name") && v.has("uuid"))
            playerIds.put(
                v.get("name").getAsString().toLowerCase(Locale.ROOT),
                UUID.fromString(v.get("uuid").getAsString()).toString());
        }
      } catch (JsonParseException | IllegalArgumentException ignored) {
      }
    }
    return playerIds.get(key);
  }

  private void writePlayerSnapshot() throws IOException {
    List<Map<String, Object>> events = new ArrayList<>();
    try (Statement s = database.createStatement();
        ResultSet result =
            s.executeQuery(
                "SELECT payload FROM player_presence_events ORDER BY id DESC LIMIT 4096")) {
      while (result.next()) events.add(map(result.getString(1)));
    } catch (SQLException error) {
      throw new IOException("PRESENCE_SNAPSHOT_FAILED", error);
    }
    Collections.reverse(events);
    AtomicFiles.writeUtf8(
        snapshot,
        JSON.toJson(
            Map.of(
                "epoch",
                epoch,
                "capturedAt",
                Instant.now().toString(),
                "boundedWindow",
                true,
                "events",
                events)));
  }

  private void insertEvent(String type, Map<String, Object> payload) throws SQLException {
    try (PreparedStatement p =
        database.prepareStatement(
            "INSERT INTO server_events(epoch,observed_at,event_type,payload) VALUES(?,?,?,?)")) {
      p.setString(1, epoch);
      p.setString(2, Instant.now().toString());
      p.setString(3, type);
      p.setString(4, JSON.toJson(payload));
      p.executeUpdate();
    }
  }

  private String meta(String key, String fallback) throws SQLException {
    try (PreparedStatement p =
        database.prepareStatement("SELECT value FROM session_meta WHERE key=?")) {
      p.setString(1, key);
      try (ResultSet result = p.executeQuery()) {
        return result.next() ? result.getString(1) : fallback;
      }
    }
  }

  private void putMeta(String key, String value) throws SQLException {
    try (PreparedStatement p =
        database.prepareStatement(
            "INSERT INTO session_meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET"
                + " value=excluded.value")) {
      p.setString(1, key);
      p.setString(2, value);
      p.executeUpdate();
    }
  }

  private String encode(long id) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString((epoch + "|" + id).getBytes(StandardCharsets.UTF_8));
  }

  private long cursor(String cursor) {
    if (cursor == null || cursor.isEmpty()) return Long.MAX_VALUE;
    if (cursor.length() > 512) throw new IllegalArgumentException("INVALID_CURSOR");
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int split = decoded.lastIndexOf('|');
      if (split < 1 || !decoded.substring(0, split).equals(epoch))
        throw new IllegalArgumentException("CURSOR_EPOCH_EXPIRED");
      long id = Long.parseLong(decoded.substring(split + 1));
      if (id < 1) throw new IllegalArgumentException("INVALID_CURSOR");
      return id;
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("INVALID_OR_EXPIRED_SESSION_CURSOR", error);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(String json) {
    return new LinkedHashMap<>(JSON.fromJson(json, Map.class));
  }

  private static void privateMode(Path path, String mode) throws IOException {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode));
    } catch (UnsupportedOperationException ignored) {
    }
  }

  private void rollback() {
    try {
      database.rollback();
    } catch (SQLException ignored) {
    }
  }

  private void autoCommit() throws IOException {
    try {
      database.setAutoCommit(true);
    } catch (SQLException error) {
      throw new IOException("SESSION_TRANSACTION_FAILED", error);
    }
  }

  @Override
  public synchronized void close() throws IOException {
    try {
      database.close();
    } catch (SQLException error) {
      throw new IOException("SESSION_STORE_CLOSE_FAILED", error);
    }
  }
}
