package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsoleStateStoreTest {
  @TempDir Path root;

  @Test
  void roundTripsBoundedCursorInvocationAndSequence() throws Exception {
    Path path = root.resolve("console/state.json");
    ConsoleStateStore store = new ConsoleStateStore(path);
    ConsoleStateStore.State expected =
        new ConsoleStateStore.State(ConsoleStateStore.FORMAT_VERSION, "cursor-42", "invocation-7", 99L);
    store.save(expected);
    assertEquals(expected, store.load());
    assertFalse(Files.exists(path.resolveSibling("state.json.tmp")));
  }

  @Test
  void missingCorruptOversizedAndInvalidStateFailToEmpty() throws Exception {
    Path path = root.resolve("console/state.json");
    ConsoleStateStore store = new ConsoleStateStore(path);
    assertEquals(ConsoleStateStore.State.empty(), store.load());

    Files.createDirectories(path.getParent());
    Files.writeString(path, "not-json");
    assertEquals(ConsoleStateStore.State.empty(), store.load());

    Files.writeString(
        path,
        "{\"version\":1,\"cursor\":\"x\",\"invocationId\":\"y\",\"sourceSequence\":-1}");
    assertEquals(ConsoleStateStore.State.empty(), store.load());

    Files.writeString(path, "x".repeat(16385));
    assertEquals(ConsoleStateStore.State.empty(), store.load());
  }
}
