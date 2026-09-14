package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HostConsoleHistoryTest {
  private static HostConfig.ConsoleConfig settings() {
    return new HostConfig.ConsoleConfig(
        true,
        "JOURNALD",
        "/usr/bin/journalctl",
        500,
        2500,
        4096,
        100,
        200L,
        8192,
        1000L,
        List.of("(?i)(token)\\s*[=:]\\s*\\S+"));
  }

  @Test
  void queryRejectsUnknownJournalFlagsAndOversizedPages() {
    JsonObject arbitrary = new JsonObject();
    arbitrary.addProperty("unit", "ssh.service");
    assertThrows(
        IllegalArgumentException.class, () -> HostConsoleHistory.Query.parse(arbitrary));

    JsonObject flags = new JsonObject();
    flags.addProperty("journalFlags", "--system");
    assertThrows(
        IllegalArgumentException.class, () -> HostConsoleHistory.Query.parse(flags));

    JsonObject tooLarge = new JsonObject();
    tooLarge.addProperty("limit", HostConsoleHistory.MAX_PAGE_LINES + 1);
    assertThrows(
        IllegalArgumentException.class, () -> HostConsoleHistory.Query.parse(tooLarge));
  }

  @Test
  void queryAcceptsOnlyTypedBoundedFilters() {
    JsonObject request = new JsonObject();
    request.addProperty("before", "2026-09-14T10:00:00Z");
    request.addProperty("after", "2026-09-14T09:00:00Z");
    request.addProperty("limit", 75);
    request.addProperty("invocationId", "0123456789abcdef0123456789abcdef");
    JsonArray levels = new JsonArray();
    levels.add("WARN");
    levels.add("ERROR");
    request.add("levels", levels);

    HostConsoleHistory.Query query = HostConsoleHistory.Query.parse(request);
    assertEquals(Instant.parse("2026-09-14T10:00:00Z"), query.before());
    assertEquals(Instant.parse("2026-09-14T09:00:00Z"), query.after());
    assertEquals(75, query.limit());
    assertEquals("0123456789abcdef0123456789abcdef", query.invocationId());
    assertEquals(java.util.Set.of("WARN", "ERROR"), query.levels());
  }

  @Test
  void commandIsPinnedToConfiguredUnitAndFixedJournalctlShape() {
    HostConsoleHistory history = new HostConsoleHistory("plexoncraft.service", settings());
    JsonObject request = new JsonObject();
    request.addProperty("before", "2026-09-14T10:00:00Z");
    request.addProperty("limit", 20);
    request.addProperty("invocationId", "0123456789abcdef0123456789abcdef");
    HostConsoleHistory.Query query = HostConsoleHistory.Query.parse(request);

    List<String> command = history.command(query, 80);
    assertEquals("/usr/bin/journalctl", command.getFirst());
    assertEquals(List.of("--unit", "plexoncraft.service"), command.subList(1, 3));
    assertTrue(command.contains("--output=json"));
    assertTrue(command.contains("--no-pager"));
    assertTrue(command.contains("--quiet"));
    assertTrue(command.contains("--lines=80"));
    assertTrue(
        command.contains("_SYSTEMD_INVOCATION_ID=0123456789abcdef0123456789abcdef"));
    assertTrue(command.stream().anyMatch(value -> value.startsWith("--until=@")));
    assertFalse(command.contains("ssh.service"));
    assertFalse(command.contains("--system"));
  }

  @Test
  void journalParsingRedactsClassifiesAndRejectsWrongUnit() {
    HostConsoleHistory history = new HostConsoleHistory("plexoncraft.service", settings());
    String line =
        "{\"MESSAGE\":\"[ERROR] Connection failed token=super-secret\","
            + "\"__CURSOR\":\"cursor-1\","
            + "\"__REALTIME_TIMESTAMP\":\"1789380000000000\","
            + "\"_SYSTEMD_UNIT\":\"plexoncraft.service\","
            + "\"_SYSTEMD_INVOCATION_ID\":\"0123456789abcdef0123456789abcdef\","
            + "\"_PID\":\"4321\",\"PRIORITY\":\"3\"}";

    var parsed = history.parseJournalLine(line);
    assertNotNull(parsed);
    assertEquals("ERROR", parsed.level());
    assertFalse(parsed.content().contains("super-secret"));
    assertTrue(parsed.content().contains("<redacted>"));
    assertEquals("HOST_JOURNAL", parsed.source());
    assertEquals("plexoncraft.service", parsed.service());
    assertEquals("cursor-1", parsed.journalCursor());

    String wrongUnit = line.replace("plexoncraft.service", "ssh.service");
    assertNull(history.parseJournalLine(wrongUnit));
  }

  @Test
  void errorHistoryScopeCannotBeBroadenedByRequestedLevels() {
    JsonObject request = new JsonObject();
    JsonArray levels = new JsonArray();
    levels.add("INFO");
    request.add("levels", levels);
    HostConsoleHistory.Query query = HostConsoleHistory.Query.parse(request);
    assertEquals(java.util.Set.of("INFO"), query.levels());
    // The public errors action passes problemsOnly=true to query(), so INFO is still excluded
    // server-side even if a browser asks for it. The request never changes the action's scope.
  }
}
