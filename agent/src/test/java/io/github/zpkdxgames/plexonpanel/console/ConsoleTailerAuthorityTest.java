package io.github.zpkdxgames.plexonpanel.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.zpkdxgames.plexonpanel.model.ConsoleLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleTailerAuthorityTest {
  @TempDir Path root;

  @Test
  void hostSuppressionAdvancesTailWithoutReplayingSuppressedPaperLines() throws Exception {
    Path log = root.resolve("latest.log");
    Files.writeString(log, "existing-before-start\n");
    List<ConsoleLine> delivered = new ArrayList<>();
    ConsoleTailer tailer =
        new ConsoleTailer(
            log,
            250,
            8192,
            true,
            true,
            new ConsoleRedactor(List.of()),
            delivered::add,
            Logger.getLogger("ConsoleTailerAuthorityTest"),
            Clock.fixed(Instant.parse("2026-09-13T18:00:00Z"), ZoneOffset.UTC));

    tailer.poll();
    assertTrue(delivered.isEmpty(), "startup positions at the current end of latest.log");

    tailer.setOutputEnabled(false);
    Files.writeString(log, "host-owned-line\n", StandardOpenOption.APPEND);
    tailer.poll();
    assertTrue(delivered.isEmpty(), "suppressed lines must not be published by Paper");

    tailer.setOutputEnabled(true);
    Files.writeString(log, "paper-fallback-line token=secret\n", StandardOpenOption.APPEND);
    tailer.poll();
    assertEquals(1, delivered.size());
    assertEquals("paper-fallback-line token=<redacted>", delivered.getFirst().content());
  }
}
