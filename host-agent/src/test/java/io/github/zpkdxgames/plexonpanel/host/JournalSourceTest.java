package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class JournalSourceTest {
  @Test void fleetNamespaceCannotExpandOrSelectAnotherInstance() {
    assertEquals(List.of("--unit", "minecraft@plexoncraft.service", "--namespace=plexonpanel-plexoncraft"),
        new JournalSource("minecraft@plexoncraft.service", "plexonpanel-plexoncraft").arguments());
    for (String namespace : List.of("*", "+plexonpanel-plexoncraft", "plexonpanel-other", "", "plexonpanel-plexoncraft --system"))
      assertThrows(IllegalArgumentException.class, () -> new JournalSource("minecraft@plexoncraft.service", namespace));
    assertThrows(IllegalArgumentException.class, () -> new JournalSource("ssh.service", "plexonpanel-plexoncraft"));
    assertThrows(IllegalArgumentException.class, () -> new JournalSource("minecraft@../other.service", "plexonpanel-other"));
    assertThrows(IllegalArgumentException.class, () -> new JournalSource("minecraft@" + "a".repeat(29) + ".service", "plexonpanel-" + "a".repeat(29)));
  }
  @Test void legacyJournalRemainsInOriginalNamespace() {
    assertEquals(List.of("--unit", "plexoncraft.service"), new JournalSource("plexoncraft.service", null).arguments());
  }
  @Test void retainedHistoryPinsNamespaceAndRejectsBrowserSelection() {
    var settings = new HostConfig.ConsoleConfig(true, "JOURNALD", "/usr/bin/journalctl", 500, 2500, 4096, 100, 200, 8192, 1000, List.of());
    var history = new HostConsoleHistory("minecraft@plexoncraft.service", settings, "plexonpanel-plexoncraft");
    var query = HostConsoleHistory.Query.parse(new JsonObject());
    var command = history.command(query, 40);
    assertEquals(1, command.stream().filter(s -> s.startsWith("--namespace=")).count());
    assertTrue(command.contains("--namespace=plexonpanel-plexoncraft"));
    assertFalse(command.contains("--system"));
    var stream = HostConsoleStreamService.journalCommand(new JournalSource("minecraft@plexoncraft.service", "plexonpanel-plexoncraft"), settings, "s=123;i=1");
    assertTrue(stream.contains("--namespace=plexonpanel-plexoncraft"));
    assertTrue(stream.contains("--follow"));
    assertTrue(stream.contains("--after-cursor=s=123;i=1"));
    assertFalse(stream.contains("--system"));
    for (String field : List.of("namespace", "unit", "journalFlags")) {
      var parameters = new JsonObject(); parameters.addProperty(field, "other");
      assertThrows(IllegalArgumentException.class, () -> HostConsoleHistory.Query.parse(parameters));
    }
  }
}
