package io.github.zpkdxgames.plexonpanel.presence;

import static org.junit.jupiter.api.Assertions.*;

import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PresenceJournalTest {
  @TempDir Path temporary;

  private static final Instant START = Instant.parse("2026-09-01T12:00:00Z");

  @Test
  void recordsJoinQuitDurationNameChangeIdempotenceAndStablePagination() throws Exception {
    MutableClock clock = new MutableClock(START);
    PresenceJournal journal = new PresenceJournal(temporary, settings(100, 100, 8192, 30), clock);
    journal.initialize(List.of());
    String player = UUID.randomUUID().toString();
    PresenceRecord joined = joined(player, "FirstName", START);
    assertTrue(journal.appendAndApply(joined, 50_000L, START.minusSeconds(3600).toString()));
    assertFalse(journal.appendAndApply(joined, 50_000L, START.minusSeconds(3600).toString()));

    clock.set(START.plusSeconds(125));
    PresenceRecord left = left(joined, "FirstName", clock.instant(), PresenceTermination.QUIT);
    assertTrue(journal.appendAndApply(left, 175_000L));
    PlayerPresenceSummary summary = journal.summary(player);
    assertFalse(summary.currentlyOnline());
    assertEquals(125_000L, summary.lastSessionDurationMillis());
    assertEquals(clock.instant().toString(), summary.lastLogoutAt());
    assertEquals(175_000L, summary.totalPlayTimeMillis());

    clock.set(START.plusSeconds(180));
    PresenceRecord renamed = joined(player, "SecondName", clock.instant());
    assertTrue(journal.appendAndApply(renamed, 180_000L));
    assertEquals("SecondName", journal.summary(player).name());
    assertNotEquals(joined.sessionId(), renamed.sessionId());

    PresenceJournal.Page first =
        journal.query(new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 1));
    assertEquals(1, first.entries().size());
    assertTrue(first.hasMore());
    assertNotNull(first.nextCursor());
    clock.set(START.plusSeconds(181));
    PresenceJournal.Page second =
        journal.query(
            new PresenceJournal.Query(
                "", PresenceJournal.Status.ALL, null, null, first.nextCursor(), 1));
    assertEquals(1, second.entries().size());
    assertNotEquals(first.entries().getFirst().eventId(), second.entries().getFirst().eventId());
    assertEquals(
        2,
        journal
            .query(
                new PresenceJournal.Query(
                    "firstname", PresenceJournal.Status.ALL, null, null, null, 10))
            .entries()
            .size());
  }

  @Test
  void reloadDoesNotInventAJoinAndNextStartupClosesAnOrphanHonestly() throws Exception {
    MutableClock clock = new MutableClock(START);
    String player = UUID.randomUUID().toString();
    ObservedPlayer observed =
        new ObservedPlayer(
            player,
            "AlreadyOnline",
            START.minusSeconds(60).toString(),
            START.minusSeconds(3600).toString(),
            60_000L);
    PresenceJournal first = new PresenceJournal(temporary, settings(100, 100, 8192, 30), clock);
    String recoveredSession = first.initialize(List.of(observed)).get(player).sessionId();
    assertTrue(
        first
            .query(new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10))
            .entries()
            .isEmpty());

    clock.set(START.plusSeconds(5));
    PresenceJournal reload = new PresenceJournal(temporary, settings(100, 100, 8192, 30), clock);
    assertEquals(recoveredSession, reload.initialize(List.of(observed)).get(player).sessionId());
    assertTrue(
        reload
            .query(new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10))
            .entries()
            .isEmpty());

    clock.set(START.plusSeconds(30));
    PresenceJournal restarted = new PresenceJournal(temporary, settings(100, 100, 8192, 30), clock);
    restarted.initialize(List.of());
    PresenceRecord unknown =
        restarted
            .query(new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10))
            .entries()
            .getFirst();
    assertEquals(PresenceTermination.UNKNOWN_DISCONNECT, unknown.termination());
    assertNull(unknown.sessionEndedAt());
    assertNull(unknown.sessionDurationMillis());
    assertEquals(START.plusSeconds(5).toString(), restarted.summary(player).lastDefinitelyObservedAt());
  }

  @Test
  void reloadKeepsNewestRetainedEventIdsIdempotent() throws Exception {
    MutableClock clock = new MutableClock(START);
    PresenceJournal first = new PresenceJournal(temporary, settings(2, 100, 8192, 30), clock);
    first.initialize(List.of());
    PresenceRecord older = joined(UUID.randomUUID().toString(), "Older", START);
    PresenceRecord newer = joined(UUID.randomUUID().toString(), "Newer", START.plusSeconds(1));
    first.appendAndApply(older, 0L);
    first.appendAndApply(newer, 0L);

    PresenceJournal reloaded = new PresenceJournal(temporary, settings(2, 100, 8192, 30), clock);
    reloaded.initialize(
        List.of(
            new ObservedPlayer(
                older.uuid(), "Older", older.sessionStartedAt(), START.toString(), 0L),
            new ObservedPlayer(
                newer.uuid(), "Newer", newer.sessionStartedAt(), START.toString(), 0L)));
    clock.set(START.plusSeconds(2));
    reloaded.appendAndApply(
        joined(UUID.randomUUID().toString(), "Newest", clock.instant()), 0L);
    assertFalse(reloaded.appendAndApply(newer, 0L));
  }

  @Test
  void truncatedTailIsBoundedWithoutDiscardingCompleteRecords() throws Exception {
    MutableClock clock = new MutableClock(START);
    PresenceJournal journal = new PresenceJournal(temporary, settings(100, 100, 8192, 30), clock);
    journal.initialize(List.of());
    PresenceRecord join = joined(UUID.randomUUID().toString(), "Complete", START);
    journal.appendAndApply(join, 0L);
    clock.set(START.plusSeconds(1));
    journal.appendAndApply(left(join, "Complete", clock.instant(), PresenceTermination.QUIT), 1000L);
    Path active = journal.directory().resolve("presence-2026-09-01.jsonl");
    Files.writeString(
        active,
        "{\"truncated\":",
        StandardCharsets.UTF_8,
        StandardOpenOption.APPEND);

    PresenceJournal recovered = new PresenceJournal(temporary, settings(100, 100, 8192, 30), clock);
    recovered.initialize(List.of());
    PresenceJournal.Page page =
        recovered.query(
            new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10));
    assertEquals(2, page.entries().size());
    assertTrue(page.boundedWindow());
  }

  @Test
  void rotatesBySizeAndDayCleansRetentionAndCapsEvents() throws Exception {
    MutableClock clock = new MutableClock(START);
    PresenceJournal journal = new PresenceJournal(temporary, settings(2, 100, 420, 2), clock);
    journal.initialize(List.of());
    PresenceRecord first = joined(UUID.randomUUID().toString(), "One", START);
    journal.appendAndApply(first, 0L);
    PresenceRecord second = joined(UUID.randomUUID().toString(), "Two", START.plusSeconds(1));
    journal.appendAndApply(second, 0L);
    clock.set(START.plusSeconds(2));
    PresenceRecord third = joined(UUID.randomUUID().toString(), "Three", START.plusSeconds(2));
    journal.appendAndApply(third, 0L);
    try (var files = Files.list(journal.directory())) {
      assertTrue(files.filter(path -> path.getFileName().toString().endsWith(".jsonl")).count() >= 2);
    }
    assertEquals(
        2,
        journal
            .query(new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10))
            .entries()
            .size());

    clock.set(START.plusSeconds(3 * 86400L));
    PresenceRecord later = joined(UUID.randomUUID().toString(), "Later", clock.instant());
    journal.appendAndApply(later, 0L);
    try (var files = Files.list(journal.directory())) {
      assertFalse(
          files.anyMatch(
              path -> path.getFileName().toString().startsWith("presence-2026-09-01")));
    }
    PresenceJournal.Page retained =
        journal.query(
            new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10));
    assertEquals(1, retained.entries().size());
    assertEquals(later.eventId(), retained.entries().getFirst().eventId());
  }

  @Test
  void rejectsUnsafePathsInvalidRecordsAndSummaryOverflow() throws Exception {
    Path outside = temporary.resolveSibling("presence-outside-" + UUID.randomUUID());
    Files.createDirectories(outside);
    Path linkedRoot = temporary.resolve("linked");
    Files.createDirectories(linkedRoot);
    try {
      Files.createSymbolicLink(linkedRoot.resolve("presence"), outside);
      assertThrows(
          java.io.IOException.class,
          () -> new PresenceJournal(linkedRoot, settings(100, 1, 8192, 30), Clock.systemUTC()));
    } catch (UnsupportedOperationException unsupported) {
      // This development filesystem does not expose symbolic links.
    }

    PresenceJournal journal =
        new PresenceJournal(temporary.resolve("safe"), settings(100, 1, 8192, 30), Clock.fixed(START, ZoneOffset.UTC));
    journal.initialize(List.of());
    PresenceRecord valid = joined(UUID.randomUUID().toString(), "Allowed", START);
    journal.appendAndApply(valid, 0L);
    assertThrows(
        java.io.IOException.class,
        () -> journal.appendAndApply(joined(UUID.randomUUID().toString(), "Overflow", START), 0L));
    PresenceRecord invalid =
        new PresenceRecord(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "Bad\nName",
            PresenceRecord.State.JOINED,
            START.toString(),
            START.toString(),
            null,
            null,
            PresenceTermination.OPEN);
    assertThrows(IllegalArgumentException.class, () -> journal.appendAndApply(invalid, 0L));
    assertThrows(
        IllegalArgumentException.class,
        () -> journal.appendAndApply(valid, -1L, START.toString()));
    assertThrows(
        IllegalArgumentException.class,
        () -> journal.appendAndApply(valid, 0L, START.plusSeconds(10).toString()));
    assertEquals(
        1,
        journal
            .query(new PresenceJournal.Query("", PresenceJournal.Status.ALL, null, null, null, 10))
            .entries()
            .size());
  }

  @Test
  void filesArePrivateAndContainNoUnrelatedSensitiveFields() throws Exception {
    PresenceJournal journal =
        new PresenceJournal(temporary, settings(100, 100, 8192, 30), Clock.fixed(START, ZoneOffset.UTC));
    journal.initialize(List.of());
    journal.appendAndApply(joined(UUID.randomUUID().toString(), "Privacy", START), 0L);
    String disk;
    try (var files = Files.list(journal.directory())) {
      Path path = files.filter(value -> value.toString().endsWith(".jsonl")).findFirst().orElseThrow();
      disk = Files.readString(path);
      if (System.getProperty("os.name").equalsIgnoreCase("Linux")) {
        assertEquals(
            Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path));
      }
    }
    for (String forbidden :
        List.of("address", "position", "chat", "console", "kickMessage", "secret", "token"))
      assertFalse(disk.contains(forbidden));
  }

  @Test
  void serviceAvoidsDoubleCloseRapidReconnectAndReportsQueuePressure() throws Exception {
    MutableClock clock = new MutableClock(START);
    PlayerPresenceService service =
        new PlayerPresenceService(
            temporary, settings(1000, 1000, 8192, 30), Logger.getAnonymousLogger(), clock, 1);
    service.start(List.of());
    String player = UUID.randomUUID().toString();
    PlayerPresenceService.CapturedEvent first =
        service.joined(player, "Rapid", START, START.minusSeconds(10), 0L);
    clock.set(START.plusSeconds(1));
    assertNotNull(service.left(player, "Rapid", clock.instant(), PresenceTermination.KICK, 1000L));
    assertNull(service.left(player, "Rapid", clock.instant(), PresenceTermination.QUIT, 1000L));
    clock.set(START.plusSeconds(2));
    PlayerPresenceService.CapturedEvent second =
        service.joined(player, "Rapid", clock.instant(), START.minusSeconds(10), 2000L);
    assertNotEquals(first.record().sessionId(), second.record().sessionId());

    for (int i = 0; i < 200 && ((Number) service.diagnostics().get("rejectedWrites")).longValue() == 0; i++) {
      String id = UUID.randomUUID().toString();
      service.joined(id, "Load" + i, clock.instant(), START.minusSeconds(10), 0L);
    }
    assertTrue(((Number) service.diagnostics().get("rejectedWrites")).longValue() > 0);
    service.close();
  }

  @Test
  void disabledHistoryWritesNothingAndShutdownFlushesAcceptedEvents() throws Exception {
    PanelSettings.PlayerHistory disabled =
        new PanelSettings.PlayerHistory(false, 30, 100, 100, 8192, 1_048_576, 10, 100, 2);
    PlayerPresenceService noHistory =
        new PlayerPresenceService(temporary.resolve("disabled"), disabled, Logger.getAnonymousLogger());
    noHistory.start(List.of());
    String id = UUID.randomUUID().toString();
    assertEquals(
        PlayerPresenceService.PersistenceState.DISABLED,
        noHistory.joined(id, "NoDisk", START, START, 0L).persistenceState());
    assertNull(noHistory.query(new com.google.gson.JsonObject()).get("nextCursor"));
    assertEquals(
        false, noHistory.query(new com.google.gson.JsonObject()).get("historyEnabled"));
    com.google.gson.JsonObject controlQuery = new com.google.gson.JsonObject();
    controlQuery.addProperty("query", "bad\nquery");
    assertThrows(IllegalArgumentException.class, () -> noHistory.query(controlQuery));
    com.google.gson.JsonObject offsetDate = new com.google.gson.JsonObject();
    offsetDate.addProperty("from", "2026-09-01T12:00:00+00:00");
    assertThrows(IllegalArgumentException.class, () -> noHistory.query(offsetDate));
    noHistory.close();
    assertFalse(Files.exists(temporary.resolve("disabled").resolve("presence")));

    Path enabledRoot = temporary.resolve("flush");
    PlayerPresenceService enabled =
        new PlayerPresenceService(
            enabledRoot,
            settings(100, 100, 8192, 30),
            Logger.getAnonymousLogger(),
            Clock.fixed(START, ZoneOffset.UTC));
    enabled.start(List.of());
    enabled.joined(UUID.randomUUID().toString(), "Flush", START, START, 0L);
    enabled.close();
    try (var files = Files.list(enabledRoot.resolve("presence"))) {
      assertTrue(files.anyMatch(path -> path.toString().endsWith(".jsonl")));
    }
  }

  private static PanelSettings.PlayerHistory settings(
      int maximumEvents, int maximumPlayers, long maximumFileBytes, int retentionDays) {
    return new PanelSettings.PlayerHistory(
        true,
        retentionDays,
        maximumEvents,
        maximumPlayers,
        maximumFileBytes,
        1_048_576,
        10,
        100,
        5);
  }

  private static PresenceRecord joined(String player, String name, Instant at) {
    return new PresenceRecord(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        player,
        name,
        PresenceRecord.State.JOINED,
        at.toString(),
        at.toString(),
        null,
        null,
        PresenceTermination.OPEN);
  }

  private static PresenceRecord left(
      PresenceRecord joined, String name, Instant at, PresenceTermination termination) {
    return new PresenceRecord(
        UUID.randomUUID().toString(),
        joined.sessionId(),
        joined.uuid(),
        name,
        PresenceRecord.State.LEFT,
        at.toString(),
        joined.sessionStartedAt(),
        at.toString(),
        at.toEpochMilli() - Instant.parse(joined.sessionStartedAt()).toEpochMilli(),
        termination);
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant value) {
      instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("UTC only");
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
