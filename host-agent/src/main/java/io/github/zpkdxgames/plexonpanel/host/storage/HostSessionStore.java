package io.github.zpkdxgames.plexonpanel.host.storage;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.console.*;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Single-writer, current Minecraft invocation storage. SQLite is authoritative; JSON is
 * rebuildable.
 */
public final class HostSessionStore implements AutoCloseable {
  private final Connection db;
  private final Path directory, projection;
  private final String serverId;
  private final Gson gson = new Gson();
  private final ConsoleRedactor redactor;
  private final ConsoleClassifier classifier = new ConsoleClassifier();
  private final int consoleRows, eventRows;
  private String epoch = "", startedAt = "";
  private boolean running;

  public HostSessionStore(Path data, String serverId, List<String> patterns) throws Exception {
    this.serverId = serverId;
    redactor = new ConsoleRedactor(patterns);
    directory = data.resolve("telemetry");
    projection = directory.resolve("player-events-current.json");
    rejectLink(directory);
    Files.createDirectories(directory);
    privateMode(directory, "rwx------");
    for (String name :
        List.of(
            "session-telemetry.sqlite",
            "session-telemetry.sqlite-wal",
            "session-telemetry.sqlite-shm",
            "player-events-current.json")) rejectLink(directory.resolve(name));
    int c = 50_000, e = 10_000;
    Path config = data.resolve("session-storage.json");
    rejectLink(config);
    if (Files.exists(config)) {
      if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS) || Files.size(config) > 4096)
        throw new IllegalArgumentException("SESSION_STORAGE_INVALID");
      JsonObject value = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
      if (value.has("consoleRows")) c = value.get("consoleRows").getAsInt();
      if (value.has("eventRows")) e = value.get("eventRows").getAsInt();
    }
    if (c < 100 || c > 1_000_000 || e < 100 || e > 100_000)
      throw new IllegalArgumentException("SESSION_STORAGE_CAP_INVALID");
    consoleRows = c;
    eventRows = e;
    Path database = directory.resolve("session-telemetry.sqlite");
    if (!Files.exists(database))
      Files.createFile(
          database,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    db =
        DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("session-telemetry.sqlite"));
    try (Statement s = db.createStatement()) {
      s.execute("PRAGMA journal_mode=WAL");
      s.execute("PRAGMA synchronous=NORMAL");
      s.execute("PRAGMA busy_timeout=5000");
      s.execute(
          "CREATE TABLE IF NOT EXISTS boot_epoch(id INTEGER PRIMARY KEY CHECK(id=1),boot_epoch_id"
              + " TEXT NOT NULL,started_at TEXT NOT NULL,previous_stopped_at TEXT)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS console_logs(seq INTEGER PRIMARY KEY"
              + " AUTOINCREMENT,boot_epoch_id TEXT NOT NULL,captured_at TEXT NOT NULL,level TEXT"
              + " NOT NULL CHECK(level IN ('DEBUG','INFO','WARN','ERROR')),source TEXT NOT"
              + " NULL,logger TEXT,message TEXT NOT NULL,line_json TEXT NOT NULL,journal_cursor"
              + " TEXT UNIQUE)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS idx_console_epoch_seq ON console_logs(boot_epoch_id,seq"
              + " DESC)");
      s.execute("CREATE INDEX IF NOT EXISTS idx_console_level_seq ON console_logs(level,seq DESC)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS player_presence_events(event_id TEXT PRIMARY"
              + " KEY,boot_epoch_id TEXT NOT NULL,session_id TEXT NOT NULL,player_uuid TEXT NOT"
              + " NULL,player_name TEXT NOT NULL,action TEXT NOT NULL CHECK(action IN"
              + " ('JOINED','LEFT')),observed_at TEXT NOT NULL,session_started_at TEXT NOT"
              + " NULL,session_ended_at TEXT,session_duration_ms INTEGER,termination TEXT NOT NULL"
              + " CHECK(termination IN ('OPEN','QUIT','KICK','UNKNOWN_DISCONNECT')))");
      s.execute(
          "CREATE INDEX IF NOT EXISTS idx_presence_epoch_time ON"
              + " player_presence_events(boot_epoch_id,observed_at DESC)");
      s.execute(
          "CREATE INDEX IF NOT EXISTS idx_presence_uuid ON"
              + " player_presence_events(player_uuid,observed_at DESC)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS server_events(event_id TEXT PRIMARY KEY,boot_epoch_id TEXT"
              + " NOT NULL,captured_at TEXT NOT NULL,category TEXT NOT NULL CHECK(category IN"
              + " ('LIFECYCLE','MAINTENANCE','BACKUP','MODERATION','PLUGIN','ALERT')),severity TEXT"
              + " NOT NULL CHECK(severity IN ('INFO','WARN','ERROR')),actor TEXT,summary TEXT NOT"
              + " NULL,payload_json TEXT)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS chat_messages(seq INTEGER PRIMARY KEY"
              + " AUTOINCREMENT,boot_epoch_id TEXT NOT NULL,payload_json TEXT NOT NULL)");
      boolean messageId = false;
      try (ResultSet columns = s.executeQuery("PRAGMA table_info(chat_messages)")) {
        while (columns.next()) if (columns.getString("name").equals("message_id")) messageId = true;
      }
      if (!messageId) s.execute("ALTER TABLE chat_messages ADD COLUMN message_id TEXT");
      s.execute(
          "CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_message_id ON chat_messages(message_id)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS session_flags(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
      s.execute(
          "CREATE TABLE IF NOT EXISTS activity(id INTEGER PRIMARY KEY CHECK(id=1),last_join TEXT)");
      try (ResultSet rs = s.executeQuery("SELECT * FROM boot_epoch WHERE id=1")) {
        if (rs.next()) {
          epoch = rs.getString("boot_epoch_id");
          startedAt = rs.getString("started_at");
          running = rs.getString("previous_stopped_at") == null;
        }
      }
    }
    writeProjection();
    secureFiles();
  }

  private static void rejectLink(Path p) throws Exception {
    if (Files.isSymbolicLink(p)) throw new IllegalArgumentException("SESSION_STORAGE_SYMLINK");
  }

  private static void privateMode(Path p, String mode) throws Exception {
    Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode));
  }

  private void secureFiles() throws Exception {
    try (var files = Files.list(directory)) {
      for (Path p : files.toList()) {
        rejectLink(p);
        if (Files.isRegularFile(p)) privateMode(p, "rw-------");
      }
    }
  }

  private int update(String sql, Object... args) throws SQLException {
    try (PreparedStatement p = db.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) p.setObject(i + 1, args[i]);
      return p.executeUpdate();
    }
  }

  private void transaction(Checked work) throws Exception {
    db.setAutoCommit(false);
    try {
      work.run();
      db.commit();
    } catch (Exception ex) {
      db.rollback();
      throw ex;
    } finally {
      db.setAutoCommit(true);
    }
  }

  @FunctionalInterface
  private interface Checked {
    void run() throws Exception;
  }

  public synchronized String bootEpochId() {
    return epoch;
  }

  public synchronized String bootStartedAt() {
    return startedAt;
  }

  public synchronized String lastJoin() throws Exception {
    try (Statement s = db.createStatement();
        ResultSet r = s.executeQuery("SELECT last_join FROM activity WHERE id=1")) {
      return r.next() ? r.getString(1) : null;
    }
  }

  public synchronized boolean observeService(Map<String, Object> status, Instant now)
      throws Exception {
    String state = String.valueOf(status.getOrDefault("state", "unknown"));
    boolean active = state.equals("active") || state.equals("activating");
    String invocation = String.valueOf(status.getOrDefault("invocationId", ""));
    if (active && invocation.matches("(?i)[0-9a-f]{32}")) {
      invocation = invocation.toLowerCase(Locale.ROOT);
      if (!invocation.equals(epoch)) {
        final String next = invocation;
        String start = String.valueOf(status.getOrDefault("bootStartedAt", now.toString()));
        try {
          Instant.parse(start);
        } catch (RuntimeException invalid) {
          start = now.toString();
        }
        final String nextStart = start;
        transaction(
            () -> {
              for (String table :
                  List.of(
                      "console_logs",
                      "player_presence_events",
                      "server_events",
                      "chat_messages",
                      "session_flags")) update("DELETE FROM " + table);
              update("INSERT OR REPLACE INTO boot_epoch VALUES(1,?,?,NULL)", next, nextStart);
            });
        epoch = next;
        startedAt = nextStart;
        running = true;
        serverEvent("LIFECYCLE", "INFO", "Minecraft boot epoch started");
        writeProjection();
        return true;
      }
      running = true;
    } else if ((state.equals("inactive") || state.equals("failed"))
        && running
        && !epoch.isEmpty()) {
      final String ended = now.toString();
      transaction(
          () -> {
            for (Map<String, Object> row : activePlayers()) {
              String joined = String.valueOf(row.get("sessionStartedAt"));
              update(
                  "INSERT OR IGNORE INTO player_presence_events VALUES(?,?,?,?,?,'LEFT',?,?,?,?,"
                      + " 'UNKNOWN_DISCONNECT')",
                  UUID.randomUUID().toString(),
                  epoch,
                  row.get("sessionId"),
                  row.get("uuid"),
                  row.get("name"),
                  ended,
                  joined,
                  ended,
                  Math.max(0, Duration.between(Instant.parse(joined), now).toMillis()));
            }
            update(
                "DELETE FROM player_presence_events WHERE event_id IN (SELECT event_id FROM"
                    + " player_presence_events ORDER BY observed_at DESC,rowid DESC LIMIT -1 OFFSET"
                    + " ?)",
                eventRows);
            update("UPDATE boot_epoch SET previous_stopped_at=? WHERE id=1", ended);
          });
      running = false;
      serverEvent("LIFECYCLE", "INFO", "Minecraft service stopped");
      writeProjection();
    }
    return false;
  }

  public synchronized boolean appendConsole(ConsoleLine input) throws Exception {
    if (epoch.isEmpty()
        || input.invocationId() != null && !epoch.equalsIgnoreCase(input.invocationId()))
      return false;
    structuredChat(input.content());
    String message = bounded(redactor.redact(input.content()), 16_384);
    var classified = classifier.classify(message, null);
    ConsoleLine line =
        new ConsoleLine(
            input.capturedAt(),
            classified.level(),
            message,
            classified.fingerprint(),
            input.truncated() || !message.equals(input.content()),
            input.source(),
            input.journalCursor(),
            epoch,
            input.service(),
            input.pid(),
            input.streamSession(),
            input.sourceSequence());
    update(
        "INSERT OR IGNORE INTO"
            + " console_logs(boot_epoch_id,captured_at,level,source,message,line_json,journal_cursor)"
            + " VALUES(?,?,?,?,?,?,?)",
        epoch,
        line.capturedAt(),
        line.level(),
        line.source(),
        message,
        gson.toJson(line),
        line.journalCursor());
    structured(message, "PLEXONPANEL_PRESENCE");
    update(
        "DELETE FROM console_logs WHERE seq <= (SELECT MAX(seq)-? FROM console_logs)", consoleRows);
    secureFiles();
    return true;
  }

  private static String bounded(String value, int bytes) {
    if (value == null) return "";
    if (value.getBytes(StandardCharsets.UTF_8).length <= bytes) return value;
    int hi = value.length(), lo = 0;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (value.substring(0, mid).getBytes(StandardCharsets.UTF_8).length <= bytes - 24) lo = mid;
      else hi = mid - 1;
    }
    if (lo > 0 && Character.isHighSurrogate(value.charAt(lo - 1))) lo--;
    return value.substring(0, lo) + " …[truncated]";
  }

  private void structured(String message, String token) throws Exception {
    String prefix = "[PlexonPanel] " + token + " ";
    int i = message.indexOf(prefix);
    if (i < 0 || !message.substring(0, i).matches("(?:\\[[^\\]]{1,120}\\]\\s*:?\\s*)*")) return;
    try {
      appendPresence(
          JsonParser.parseString(message.substring(i + prefix.length())).getAsJsonObject());
    } catch (RuntimeException ignored) {
    }
  }

  public synchronized void appendPresence(JsonObject v) throws Exception {
    if (epoch.isEmpty() || !running) return;
    String id = text(v, "eventId", 36),
        session = text(v, "sessionId", 36),
        uuid = text(v, "uuid", 36),
        name = text(v, "name", 64),
        action = text(v, "state", 6),
        at = text(v, "observedAt", 64),
        start = text(v, "sessionStartedAt", 64),
        termination = text(v, "termination", 32);
    canonicalUuid(id);
    canonicalUuid(session);
    canonicalUuid(uuid);
    Instant.parse(at);
    Instant.parse(start);
    if (!Set.of("JOINED", "LEFT").contains(action)
        || !Set.of("OPEN", "QUIT", "KICK", "UNKNOWN_DISCONNECT").contains(termination))
      throw new IllegalArgumentException("PRESENCE_INVALID");
    String ended = action.equals("LEFT") ? text(v, "sessionEndedAt", 64) : null;
    Long duration =
        ended == null
            ? null
            : Math.max(0, Duration.between(Instant.parse(start), Instant.parse(ended)).toMillis());
    if (action.equals("JOINED")) {
      try (PreparedStatement duplicate =
          db.prepareStatement(
              "SELECT 1 FROM player_presence_events WHERE session_id=? AND action='JOINED'"
                  + " LIMIT 1")) {
        duplicate.setString(1, session);
        try (ResultSet row = duplicate.executeQuery()) {
          if (row.next()) return;
        }
      }
    }
    transaction(
        () -> {
          update(
              "INSERT OR IGNORE INTO player_presence_events VALUES(?,?,?,?,?,?,?,?,?,?,?)",
              id,
              epoch,
              session,
              uuid,
              redactor.redact(name),
              action,
              at,
              start,
              ended,
              duration,
              termination);
          if (action.equals("JOINED"))
            update(
                "INSERT INTO activity VALUES(1,?) ON CONFLICT(id) DO UPDATE SET"
                    + " last_join=MAX(last_join,excluded.last_join)",
                at);
          // A hard cap also bounds incomplete journal streams. Never claim a complete roster
          // after an open join is evicted; the completeness flag survives a Host restart.
          String pruned =
              "SELECT event_id FROM player_presence_events ORDER BY observed_at DESC,rowid DESC"
                  + " LIMIT -1 OFFSET ?";
          try (PreparedStatement check =
              db.prepareStatement(
                  "SELECT COUNT(*) FROM player_presence_events j WHERE j.event_id IN ("
                      + pruned
                      + ") AND action='JOINED' AND NOT EXISTS(SELECT 1 FROM player_presence_events"
                      + " l WHERE l.session_id=j.session_id AND l.action='LEFT')")) {
            check.setInt(1, eventRows);
            try (ResultSet r = check.executeQuery()) {
              if (r.next() && r.getLong(1) > 0)
                update("INSERT OR REPLACE INTO session_flags VALUES('presenceIncomplete','true')");
            }
          }
          update(
              "DELETE FROM player_presence_events WHERE event_id IN (" + pruned + ")", eventRows);
        });
    writeProjection();
  }

  private void structuredChat(String message) throws Exception {
    String prefix = "[PlexonPanel] PLEXONPANEL_CHAT ";
    int i = message.indexOf(prefix);
    if (i < 0 || !message.substring(0, i).matches("(?:\\[[^\\]]{1,120}\\]\\s*:?\\s*)*")) return;
    try {
      JsonObject v =
          JsonParser.parseString(message.substring(i + prefix.length())).getAsJsonObject();
      UUID.fromString(text(v, "messageId", 36));
      String uuid = text(v, "senderUuid", 36);
      if (!uuid.equals("CONSOLE")) UUID.fromString(uuid);
      Instant.parse(text(v, "capturedAt", 64));
      if (!Set.of("GLOBAL", "VANILLA", "DASHBOARD").contains(text(v, "channel", 32))) return;
      JsonObject safe = new JsonObject();
      for (String field :
          List.of(
              "capturedAt",
              "messageId",
              "channel",
              "senderUuid",
              "senderName",
              "content",
              "source"))
        safe.addProperty(field, bounded(redactor.redact(text(v, field, 8192)), 4096));
      update(
          "INSERT OR IGNORE INTO chat_messages(boot_epoch_id,payload_json,message_id)"
              + " VALUES(?,?,?)",
          epoch,
          gson.toJson(safe),
          v.get("messageId").getAsString());
      update(
          "DELETE FROM chat_messages WHERE seq <= (SELECT MAX(seq)-? FROM chat_messages)",
          eventRows);
    } catch (RuntimeException invalid) {
    }
  }

  public synchronized Map<String, Object> chatHistory() throws Exception {
    List<JsonObject> rows = new ArrayList<>();
    int bytes = 1024;
    try (Statement s = db.createStatement();
        ResultSet r =
            s.executeQuery("SELECT payload_json FROM chat_messages ORDER BY seq DESC LIMIT 100")) {
      while (r.next()) {
        String json = r.getString(1);
        int n = json.getBytes(StandardCharsets.UTF_8).length;
        if (bytes + n > 43000) break;
        rows.add(JsonParser.parseString(json).getAsJsonObject());
        bytes += n;
      }
    }
    Collections.reverse(rows);
    return Map.of("bootEpochId", epoch, "messages", rows);
  }

  private static void canonicalUuid(String value) {
    if (!UUID.fromString(value).toString().equalsIgnoreCase(value))
      throw new IllegalArgumentException("UUID_INVALID");
  }

  private static String text(JsonObject v, String key, int max) {
    JsonElement e = v.get(key);
    if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString())
      throw new IllegalArgumentException("SESSION_FIELD_INVALID");
    String s = e.getAsString();
    if (s.isEmpty() || s.length() > max)
      throw new IllegalArgumentException("SESSION_FIELD_INVALID");
    return s;
  }

  private List<Map<String, Object>> presenceRows(String suffix, int limit) throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    try (PreparedStatement p =
        db.prepareStatement("SELECT * FROM player_presence_events " + suffix + " LIMIT ?")) {
      p.setInt(1, limit);
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          for (String[] pair :
              new String[][] {
                {"eventId", "event_id"},
                {"sessionId", "session_id"},
                {"uuid", "player_uuid"},
                {"name", "player_name"},
                {"state", "action"},
                {"observedAt", "observed_at"},
                {"sessionStartedAt", "session_started_at"},
                {"sessionEndedAt", "session_ended_at"},
                {"termination", "termination"}
              }) row.put(pair[0], r.getString(pair[1]));
          row.put("sessionDurationMillis", r.getObject("session_duration_ms"));
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private List<Map<String, Object>> activePlayers() throws Exception {
    return presenceRows(
        "j WHERE action='JOINED' AND NOT EXISTS(SELECT 1 FROM player_presence_events l WHERE"
            + " l.session_id=j.session_id AND l.action='LEFT') ORDER BY observed_at",
        eventRows);
  }

  private void writeProjection() throws Exception {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("schemaVersion", 5);
    view.put("serverId", serverId);
    view.put("bootEpochId", epoch);
    view.put("bootStartedAt", startedAt);
    var active = activePlayers();
    boolean complete = true;
    try (Statement statement = db.createStatement();
        ResultSet flags =
            statement.executeQuery("SELECT 1 FROM session_flags WHERE name='presenceIncomplete'")) {
      complete = !flags.next();
    }
    view.put("presenceComplete", complete);
    view.put("onlineCount", running && !complete ? null : active.size());
    view.put("recordedOnlineCount", active.size());
    view.put("activePlayers", active);
    view.put("events", presenceRows("ORDER BY observed_at", eventRows));
    rejectLink(projection);
    AtomicFiles.writeUtf8(projection, gson.toJson(view));
    secureFiles();
  }

  public synchronized Map<String, Object> sessionView() throws Exception {
    JsonObject view = JsonParser.parseString(Files.readString(projection)).getAsJsonObject();
    if (!epoch.equals(view.get("bootEpochId").getAsString())) {
      writeProjection();
      view = JsonParser.parseString(Files.readString(projection)).getAsJsonObject();
    }
    view.add("events", gson.toJsonTree(presenceRows("ORDER BY observed_at DESC", 100)));
    JsonArray active = view.getAsJsonArray("activePlayers");
    if (active.size() > 200) {
      JsonArray bounded = new JsonArray();
      for (int i = 0; i < 200; i++) bounded.add(active.get(i));
      view.add("activePlayers", bounded);
      view.addProperty("rosterTruncated", true);
    }
    List<Map<String, Object>> events = new ArrayList<>();
    try (Statement s = db.createStatement();
        ResultSet r =
            s.executeQuery("SELECT * FROM server_events ORDER BY captured_at DESC LIMIT 30")) {
      while (r.next())
        events.add(
            Map.of(
                "eventId",
                r.getString("event_id"),
                "capturedAt",
                r.getString("captured_at"),
                "category",
                r.getString("category"),
                "severity",
                r.getString("severity"),
                "summary",
                r.getString("summary")));
    }
    view.add("serverEvents", gson.toJsonTree(events));
    while (gson.toJson(view).getBytes(StandardCharsets.UTF_8).length > 45_000) {
      JsonArray list = view.getAsJsonArray("events");
      if (!list.isEmpty()) {
        list.remove(list.size() - 1);
        continue;
      }
      list = view.getAsJsonArray("activePlayers");
      if (!list.isEmpty()) {
        list.remove(list.size() - 1);
        view.addProperty("rosterTruncated", true);
        continue;
      }
      break;
    }
    return gson.fromJson(
        view, new com.google.gson.reflect.TypeToken<Map<String, Object>>() {}.getType());
  }

  public synchronized void serverEvent(String category, String severity, String summary)
      throws Exception {
    if (epoch.isEmpty()) return;
    update(
        "INSERT INTO server_events VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        epoch,
        Instant.now().toString(),
        category,
        severity,
        null,
        bounded(redactor.redact(summary), 1024),
        null);
    update(
        "DELETE FROM server_events WHERE event_id IN (SELECT event_id FROM server_events ORDER BY"
            + " captured_at DESC LIMIT -1 OFFSET ?)",
        eventRows);
  }

  public synchronized Map<String, Object> consoleHistory(JsonObject params, boolean errors)
      throws Exception {
    if (!Set.of("cursor", "before", "after", "limit", "levels", "query", "invocationId")
        .containsAll(params.keySet())) throw new IllegalArgumentException("CONSOLE_QUERY_INVALID");
    int limit = params.has("limit") ? params.get("limit").getAsInt() : 100;
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("CONSOLE_LIMIT_INVALID");
    long anchor = Long.MAX_VALUE;
    if (params.has("cursor")) {
      String[] parts = text(params, "cursor", 100).split(":");
      if (parts.length != 3 || !parts[0].equals("sqlite") || !parts[1].matches("[0-9a-f]{32}"))
        throw new IllegalArgumentException("CONSOLE_CURSOR_INVALID");
      if (!parts[1].equals(epoch))
        return Map.of(
            "lines", List.of(), "hasMore", false, "bootEpochId", epoch, "epochChanged", true);
      anchor = Long.parseLong(parts[2]);
      if (anchor < 1) throw new IllegalArgumentException("CONSOLE_CURSOR_INVALID");
    }
    if (params.has("invocationId") && !text(params, "invocationId", 32).equalsIgnoreCase(epoch))
      return Map.of("lines", List.of(), "hasMore", false, "bootEpochId", epoch);
    StringBuilder sql =
        new StringBuilder("SELECT seq,line_json FROM console_logs WHERE boot_epoch_id=? AND seq<?");
    List<Object> args = new ArrayList<>(List.of(epoch, anchor));
    for (String key : List.of("before", "after"))
      if (params.has(key)) {
        String at = text(params, key, 64);
        Instant.parse(at);
        sql.append(" AND captured_at").append(key.equals("before") ? "<?" : ">?");
        args.add(at);
      }
    if (errors) sql.append(" AND level IN ('WARN','ERROR')");
    if (params.has("levels")) {
      JsonArray levels = params.getAsJsonArray("levels");
      if (levels.isEmpty() || levels.size() > 4)
        throw new IllegalArgumentException("CONSOLE_LEVELS_INVALID");
      sql.append(" AND level IN (");
      for (JsonElement level : levels) {
        String v = level.getAsString();
        if (!Set.of("DEBUG", "INFO", "WARN", "ERROR").contains(v))
          throw new IllegalArgumentException("CONSOLE_LEVELS_INVALID");
        sql.append("?,");
        args.add(v);
      }
      sql.setLength(sql.length() - 1);
      sql.append(')');
    }
    if (params.has("query")) {
      sql.append(" AND instr(lower(message),lower(?))>0");
      args.add(text(params, "query", 256));
    }
    sql.append(" ORDER BY seq DESC LIMIT ?");
    args.add(limit + 1);
    List<JsonObject> rows = new ArrayList<>();
    long last = 0;
    boolean more = false;
    int size = 1024;
    try (PreparedStatement p = db.prepareStatement(sql.toString())) {
      for (int i = 0; i < args.size(); i++) p.setObject(i + 1, args.get(i));
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          JsonObject row = JsonParser.parseString(r.getString(2)).getAsJsonObject();
          while (gson.toJson(row).getBytes(StandardCharsets.UTF_8).length > 30000) {
            row.addProperty(
                "content",
                bounded(
                    row.get("content").getAsString(),
                    row.get("content").getAsString().getBytes(StandardCharsets.UTF_8).length / 2));
            row.addProperty("truncated", true);
          }
          int bytes = gson.toJson(row).getBytes(StandardCharsets.UTF_8).length;
          if (rows.size() >= limit || size + bytes > 43_000) {
            more = true;
            break;
          }
          rows.add(row);
          size += bytes;
          last = r.getLong(1);
        }
      }
    }
    Collections.reverse(rows);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("lines", rows);
    result.put("bootEpochId", epoch);
    result.put("bootStartedAt", startedAt);
    result.put("source", "HOST_SQLITE");
    result.put("hasMore", more);
    result.put("maxPageLines", 200);
    if (more && last > 0) result.put("nextCursor", "sqlite:" + epoch + ":" + last);
    return result;
  }

  @Override
  public synchronized void close() throws Exception {
    db.close();
  }
}
