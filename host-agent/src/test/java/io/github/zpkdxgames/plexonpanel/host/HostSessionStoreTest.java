package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.console.ConsoleLine;
import io.github.zpkdxgames.plexonpanel.host.storage.HostSessionStore;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostSessionStoreTest {
  @TempDir Path data;
  static final String A = "a".repeat(32), B = "b".repeat(32);

  HostSessionStore open() throws Exception {
    return new HostSessionStore(data, "server", List.of("secret=[^ ]+"));
  }

  static void boot(HostSessionStore s, String epoch) throws Exception {
    s.observeService(
        Map.of("state", "active", "invocationId", epoch), Instant.parse("2026-10-17T10:00:00Z"));
  }

  static ConsoleLine line(String epoch, String content, int seq) {
    return new ConsoleLine(
        "2026-10-17T10:00:01Z",
        "INFO",
        content,
        null,
        false,
        "HOST_JOURNAL",
        "cursor" + seq,
        epoch,
        "minecraft.service",
        "1",
        "stream",
        seq);
  }

  static JsonObject join() {
    JsonObject v = new JsonObject();
    v.addProperty("eventId", UUID.randomUUID().toString());
    v.addProperty("sessionId", UUID.randomUUID().toString());
    v.addProperty("uuid", UUID.randomUUID().toString());
    v.addProperty("name", "Player");
    v.addProperty("state", "JOINED");
    v.addProperty("observedAt", "2026-10-17T10:00:01Z");
    v.addProperty("sessionStartedAt", "2026-10-17T10:00:01Z");
    v.addProperty("termination", "OPEN");
    return v;
  }

  @Test
  void sameInvocationSurvivesHostRestart() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendConsole(line(A, "hello", 1));
    }
    try (var s = open()) {
      boot(s, A);
      assertEquals(1, ((List<?>) s.consoleHistory(new JsonObject(), false).get("lines")).size());
    }
  }

  @Test
  void newInvocationResetsDatabaseAndJson() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendConsole(line(A, "hello", 1));
      s.appendPresence(join());
      boot(s, B);
      assertEquals(0, ((List<?>) s.consoleHistory(new JsonObject(), false).get("lines")).size());
      assertEquals(0.0, s.sessionView().get("onlineCount"));
      assertFalse(s.appendConsole(line(A, "old", 2)));
    }
  }

  @Test
  void shutdownFinalizesOpenPresence() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendPresence(join());
      s.observeService(Map.of("state", "inactive"), Instant.parse("2026-10-17T10:10:00Z"));
      assertEquals(0.0, s.sessionView().get("onlineCount"));
      assertTrue(
          Files.readString(data.resolve("telemetry/player-events-current.json"))
              .contains("UNKNOWN_DISCONNECT"));
    }
  }

  @Test
  void unknownServiceStateDoesNotInventShutdown() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendPresence(join());
      s.observeService(Map.of("state", "unknown"), Instant.now());
      assertEquals(1.0, s.sessionView().get("onlineCount"));
    }
  }

  @Test
  void redactedLinesAndParameterizedSubstringQueries() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendConsole(line(A, "hello secret=hunter2", 1));
      JsonObject q = new JsonObject();
      q.addProperty("query", "hello");
      var result = s.consoleHistory(q, false);
      assertEquals(1, ((List<?>) result.get("lines")).size());
      assertFalse(new Gson().toJson(result).contains("hunter2"));
      q.addProperty("query", "' OR 1=1 --");
      assertTrue(((List<?>) s.consoleHistory(q, false).get("lines")).isEmpty());
    }
  }

  @Test
  void cursorIsBoundToEpoch() throws Exception {
    try (var s = open()) {
      boot(s, A);
      for (int i = 0; i < 3; i++) s.appendConsole(line(A, "line" + i, i));
      JsonObject q = new JsonObject();
      q.addProperty("limit", 1);
      String cursor = (String) s.consoleHistory(q, false).get("nextCursor");
      boot(s, B);
      q.addProperty("cursor", cursor);
      assertEquals(true, s.consoleHistory(q, false).get("epochChanged"));
    }
  }

  @Test
  void configuredCapsPruneConsole() throws Exception {
    Files.writeString(
        data.resolve("session-storage.json"), "{\"consoleRows\":100,\"eventRows\":100}");
    try (var s = open()) {
      boot(s, A);
      for (int i = 0; i < 150; i++) s.appendConsole(line(A, "line" + i, i));
      JsonObject q = new JsonObject();
      q.addProperty("limit", 200);
      assertEquals(100, ((List<?>) s.consoleHistory(q, false).get("lines")).size());
    }
  }

  @Test
  void malformedIdentityRejected() throws Exception {
    try (var s = open()) {
      boot(s, A);
      JsonObject j = join();
      j.addProperty("uuid", "PlayerName");
      assertThrows(IllegalArgumentException.class, () -> s.appendPresence(j));
    }
  }

  @Test
  void ownerOnlyFilesAndNoSymlinkStore() throws Exception {
    try (var s = open()) {
      boot(s, A);
      assertEquals(
          PosixFilePermissions.fromString("rw-------"),
          Files.getPosixFilePermissions(data.resolve("telemetry/session-telemetry.sqlite")));
    }
    Path other = Files.createTempDirectory("plexon-store-target");
    Files.delete(data.resolve("telemetry/player-events-current.json"));
    Files.createSymbolicLink(
        data.resolve("telemetry/player-events-current.json"), other.resolve("target"));
    assertThrows(IllegalArgumentException.class, this::open);
  }

  @Test
  void projectionRebuiltAfterInterruptedJsonWrite() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendPresence(join());
    }
    Files.writeString(data.resolve("telemetry/player-events-current.json"), "broken");
    try (var s = open()) {
      assertEquals(1.0, s.sessionView().get("onlineCount"));
    }
  }

  @Test
  void intervalsRetainTheirPhaseAcrossHostRestart() {
    var b =
        new MaintenanceSettings.BackupSchedule(
            true, "INTERVAL_HOURS", List.of(), "04:00", 6, 15, false, 168);
    assertEquals(
        Instant.parse("2026-10-17T12:00:00Z"),
        MaintenanceSchedule.nextBackup(
            b, ZoneId.of("America/Sao_Paulo"), Instant.parse("2026-10-17T11:17:00Z")));
  }

  @Test
  void oldSettingsCannotReactivateLegacyBackupSchedule() {
    MaintenanceSettings s =
        MaintenanceSettings.fromJson(
            JsonParser.parseString(new Gson().toJson(MaintenanceSettings.migratedDefaults())));
    assertFalse(s.schedule().backups().enabled());
  }

  @Test
  void escapedOversizeRowStillProducesCursorProgress() throws Exception {
    try (var s = open()) {
      boot(s, A);
      s.appendConsole(line(A, "\\".repeat(16000), 1));
      s.appendConsole(line(A, "recent", 2));
      var page = s.consoleHistory(new JsonObject(), false);
      assertFalse(((List<?>) page.get("lines")).isEmpty());
      assertTrue(
          new Gson().toJson(page).getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 45000);
    }
  }

  @Test
  void oldCursorAndUnknownFiltersFailClosed() throws Exception {
    try (var s = open()) {
      boot(s, A);
      JsonObject q = new JsonObject();
      q.addProperty("unit", "other.service");
      assertThrows(IllegalArgumentException.class, () -> s.consoleHistory(q, false));
    }
  }

  @Test
  void incompletePresenceStreamsStayBoundedWithoutInventingACompleteRoster() throws Exception {
    Files.writeString(
        data.resolve("session-storage.json"), "{\"consoleRows\":100,\"eventRows\":100}");
    try (var s = open()) {
      boot(s, A);
      for (int i = 0; i < 120; i++) s.appendPresence(join());
      var view = s.sessionView();
      assertEquals(false, view.get("presenceComplete"));
      assertNull(view.get("onlineCount"));
      assertTrue(((List<?>) view.get("events")).size() <= 100);
      try (var database =
              java.sql.DriverManager.getConnection(
                  "jdbc:sqlite:" + data.resolve("telemetry/session-telemetry.sqlite"));
          var statement = database.createStatement();
          var rows = statement.executeQuery("SELECT COUNT(*) FROM player_presence_events")) {
        assertTrue(rows.next());
        assertEquals(100, rows.getInt(1));
      }
    }
  }
}
