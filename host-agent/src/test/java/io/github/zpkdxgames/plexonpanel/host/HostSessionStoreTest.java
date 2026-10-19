package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.console.ConsoleLine;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostSessionStoreTest {
  @TempDir Path directory;

  ConsoleLine line(String invocation, String text) {
    return new ConsoleLine(
        "2026-10-18T12:00:00Z",
        "INFO",
        text,
        "fingerprint",
        false,
        "HOST_JOURNAL",
        null,
        invocation,
        "minecraft@test.service",
        null,
        "stream",
        1);
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> rows(Map<String, Object> data) {
    return (List<Map<String, Object>>) data.get("lines");
  }

  @Test
  void invocationResetIsAtomicAndLateJournalRowsCannotReturn() throws Exception {
    try (var store = new HostSessionStore(directory, directory)) {
      assertTrue(
          store.observeService(Map.of("state", "active", "invocationId", "bootA")).isPresent());
      store.append(line("bootA", "old"));
      assertEquals(1, rows(store.query(new JsonObject(), false)).size());
      assertTrue(
          store.observeService(Map.of("state", "activating", "invocationId", "bootB")).isPresent());
      assertTrue(
          store.observeService(Map.of("state", "active", "invocationId", "bootB")).isEmpty());
      store.append(line("bootA", "late"));
      assertTrue(rows(store.query(new JsonObject(), false)).isEmpty());
      var snapshot =
          JsonParser.parseString(
                  Files.readString(directory.resolve("telemetry/player-events-current.json")))
              .getAsJsonObject();
      assertEquals("bootB", snapshot.get("epoch").getAsString());
      assertTrue(snapshot.getAsJsonArray("events").isEmpty());
    }
  }

  @Test
  void sameMinecraftInvocationSurvivesAHostRestart() throws Exception {
    try (var s = new HostSessionStore(directory, directory)) {
      s.observeService(Map.of("state", "active", "invocationId", "bootA"));
      s.append(line("bootA", "retained"));
    }
    try (var s = new HostSessionStore(directory, directory)) {
      assertTrue(s.observeService(Map.of("state", "active", "invocationId", "bootA")).isEmpty());
      assertEquals(1, rows(s.query(new JsonObject(), false)).size());
    }
  }

  @Test
  void stopThenStartWithoutInvocationCreatesOneNewEpoch() throws Exception {
    try (var s = new HostSessionStore(directory, directory)) {
      s.observeService(Map.of("state", "active"));
      String old = s.epoch();
      s.append(line(null, "old"));
      s.observeService(Map.of("state", "inactive"));
      s.observeService(Map.of("state", "activating"));
      assertTrue(s.observeService(Map.of("state", "active")).isPresent());
      assertNotEquals(old, s.epoch());
      assertTrue(s.observeService(Map.of("state", "active")).isEmpty());
      assertTrue(rows(s.query(new JsonObject(), false)).isEmpty());
    }
  }

  @Test
  void cursorPagesAreBoundedAndExpireAtEpochBoundary() throws Exception {
    try (var s = new HostSessionStore(directory, directory)) {
      s.observeService(Map.of("state", "active", "invocationId", "a"));
      for (int i = 0; i < 140; i++) s.append(line("a", "line" + i));
      Map<String, Object> first = s.query(new JsonObject(), false);
      assertEquals(100, rows(first).size());
      assertEquals(true, first.get("hasMore"));
      JsonObject q = new JsonObject();
      q.addProperty("cursor", String.valueOf(first.get("nextCursor")));
      assertEquals(40, rows(s.query(q, false)).size());
      s.observeService(Map.of("state", "active", "invocationId", "b"));
      assertThrows(IllegalArgumentException.class, () -> s.query(q, false));
    }
  }

  @Test
  void escapedLargeLinesStayInsideWireBudgetAndErrorsAreScoped() throws Exception {
    try (var s = new HostSessionStore(directory, directory)) {
      s.append(line(null, "\u0000".repeat(10000)));
      String json = new Gson().toJson(s.query(new JsonObject(), false));
      assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 42 * 1024);
      assertEquals(true, rows(s.query(new JsonObject(), false)).getFirst().get("truncated"));
      assertTrue(rows(s.query(new JsonObject(), true)).isEmpty());
    }
  }

  @Test
  void UUIDResolvedPresenceUsesAtomicCurrentSessionSnapshot() throws Exception {
    String uuid = "19f50206-26de-410c-a1bc-000000000000";
    try (var s = new HostSessionStore(directory, directory)) {
      s.observeService(Map.of("state", "active", "invocationId", "a"));
      s.append(line("a", "UUID of player Tonim is " + uuid));
      s.append(line("a", "Tonim joined the game"));
      s.append(line("a", "Tonim left the game"));
      JsonObject q = new JsonObject();
      q.addProperty("query", uuid);
      var page = s.history(q);
      assertEquals(2, ((List<?>) page.get("entries")).size());
      assertTrue(
          Files.readString(directory.resolve("telemetry/player-events-current.json"))
              .contains(uuid));
      assertThrows(
          IllegalArgumentException.class,
          () -> {
            JsonObject bad = new JsonObject();
            bad.addProperty("query", "Tonim");
            s.history(bad);
          });
    }
  }

  @Test
  void unknownPlayerNeverGetsAFabricatedUuid() throws Exception {
    try (var s = new HostSessionStore(directory, directory)) {
      s.append(line(null, "Unknown joined the game"));
      assertTrue(((List<?>) s.history(new JsonObject()).get("entries")).isEmpty());
    }
  }

  @Test
  void sqliteRunsInWalModeAndFilesArePrivate() throws Exception {
    try (var s = new HostSessionStore(directory, directory);
        var db =
            DriverManager.getConnection(
                "jdbc:sqlite:" + directory.resolve("telemetry/session-telemetry.sqlite"));
        var stmt = db.createStatement();
        var rs = stmt.executeQuery("PRAGMA journal_mode")) {
      assertTrue(rs.next());
      assertEquals("wal", rs.getString(1));
      assertEquals(
          "rwx------",
          java.nio.file.attribute.PosixFilePermissions.toString(
              Files.getPosixFilePermissions(directory.resolve("telemetry"))));
      assertEquals(
          "rw-------",
          java.nio.file.attribute.PosixFilePermissions.toString(
              Files.getPosixFilePermissions(
                  directory.resolve("telemetry/session-telemetry.sqlite"))));
    }
  }

  @Test
  void chatBackfillIsCurrentBootBoundedAndCursorPaginated() throws Exception {
    try (var store = new HostSessionStore(directory, directory)) {
      store.observeService(Map.of("state", "active", "invocationId", "a"));
      for (int i = 0; i < 40; i++) store.append(line("a", "<Tonim> Message " + i));
      JsonObject q = new JsonObject();
      q.addProperty("stream", "chat");
      var first = store.query(q, false);
      assertEquals(25, ((List<?>) first.get("chatMessages")).size());
      q.addProperty("cursor", String.valueOf(first.get("nextCursor")));
      assertEquals(15, ((List<?>) store.query(q, false).get("chatMessages")).size());
      assertThrows(IllegalArgumentException.class, () -> store.query(q, true));
      store.observeService(Map.of("state", "active", "invocationId", "b"));
      assertThrows(IllegalArgumentException.class, () -> store.query(q, false));
    }
  }

  @Test
  void backfilledLogsKeepTheirJournalAndStreamIdentity() throws Exception {
    try (var store = new HostSessionStore(directory, directory)) {
      store.observeService(Map.of("state", "active", "invocationId", "boot"));
      store.append(
          new ConsoleLine(
              "2026-10-18T12:00:00Z",
              "INFO",
              "line",
              "f",
              false,
              "HOST_JOURNAL",
              "cursor",
              "boot",
              "minecraft@test.service",
              null,
              "stream",
              42));
      var row = rows(store.query(new JsonObject(), false)).getFirst();
      assertEquals("cursor", row.get("journalCursor"));
      assertEquals("stream", row.get("streamSession"));
      assertEquals(42.0, row.get("sourceSequence"));
    }
  }

  @Test
  void completedSessionsReportFirstLastSeenAndMeanWithoutInventingUnknownJoins() throws Exception {
    String id = "19f50206-26de-410c-a1bc-000000000000";
    try (var store = new HostSessionStore(directory, directory)) {
      store.observeService(Map.of("state", "active", "invocationId", "boot"));
      store.append(line("boot", "UUID of player Tonim is " + id));
      store.append(
          new ConsoleLine("2026-10-18T12:00:00Z", "INFO", "Tonim joined the game", "a", false));
      store.append(
          new ConsoleLine("2026-10-18T12:05:00Z", "INFO", "Tonim left the game", "b", false));
      store.append(
          new ConsoleLine("2026-10-18T12:06:00Z", "INFO", "Tonim left the game", "c", false));
      var summary = store.history(new JsonObject());
      assertEquals(1, summary.get("uniqueJoins"));
      assertEquals(300000.0, summary.get("meanClosedSessionMillis"));
      assertEquals("2026-10-18T12:00:00Z", summary.get("firstSeen"));
      assertEquals("2026-10-18T12:06:00Z", summary.get("lastSeen"));
    }
  }

  @Test
  void journalReplayAfterHostRestartDoesNotDuplicateLogsOrPresence() throws Exception {
    String id = "19f50206-26de-410c-a1bc-000000000000";
    Files.writeString(
        directory.resolve("usercache.json"), "[{\"name\":\"Tonim\",\"uuid\":\"" + id + "\"}]");
    ConsoleLine joined =
        new ConsoleLine(
            "2026-10-19T12:00:00Z",
            "INFO",
            "[12:00:00 INFO]: Tonim joined the game",
            "f",
            false,
            "HOST_JOURNAL",
            "cursor-joined",
            "boot",
            "minecraft@test.service",
            null,
            "stream",
            1);
    try (var store = new HostSessionStore(directory, directory)) {
      store.observeService(Map.of("state", "active", "invocationId", "boot"));
      store.append(joined);
    }
    try (var store = new HostSessionStore(directory, directory)) {
      store.observeService(Map.of("state", "active", "invocationId", "boot"));
      store.append(joined);
      assertEquals(1, rows(store.query(new JsonObject(), false)).size());
      assertEquals(1, ((List<?>) store.history(new JsonObject()).get("entries")).size());
    }
  }

  @Test
  void chatCannotImpersonatePresenceOrUuidAnnouncements() throws Exception {
    String id = "19f50206-26de-410c-a1bc-000000000000";
    Files.writeString(
        directory.resolve("usercache.json"), "[{\"name\":\"Victim\",\"uuid\":\"" + id + "\"}]");
    try (var store = new HostSessionStore(directory, directory)) {
      store.append(line(null, "[12:00:00 INFO]: <Attacker> Victim joined the game"));
      store.append(
          line(
              null,
              "[12:00:00 INFO]: <Attacker> UUID of player Victim is"
                  + " 19f50206-26de-410c-a1bc-111111111111"));
      assertTrue(((List<?>) store.history(new JsonObject()).get("entries")).isEmpty());
      store.append(line(null, "[12:00:01] [Server thread/INFO]: Victim joined the game"));
      var events = (List<?>) store.history(new JsonObject()).get("entries");
      assertEquals(1, events.size());
      assertEquals(id, ((Map<?, ?>) events.getFirst()).get("uuid"));
    }
  }
}
