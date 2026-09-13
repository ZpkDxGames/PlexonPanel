package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Minimal atomic journal cursor state. The journal remains the historical log source. */
final class ConsoleStateStore {
  static final int FORMAT_VERSION = 1;

  record State(int version, String cursor, String invocationId, long sourceSequence) {
    static State empty() {
      return new State(FORMAT_VERSION, null, null, 0L);
    }
  }

  private final Path path;
  private final Gson gson = new Gson();

  ConsoleStateStore(Path path) {
    this.path = path;
  }

  State load() {
    try {
      if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384)
        return State.empty();
      JsonObject json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      State state = gson.fromJson(json, State.class);
      if (state == null
          || state.version != FORMAT_VERSION
          || state.sourceSequence < 0
          || state.cursor != null && state.cursor.length() > 4096
          || state.invocationId != null && state.invocationId.length() > 256)
        return State.empty();
      return state;
    } catch (Exception ignored) {
      return State.empty();
    }
  }

  synchronized void save(State state) throws IOException {
    Files.createDirectories(path.getParent());
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    Files.writeString(
        temporary,
        gson.toJson(state),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    try {
      Files.move(
          temporary,
          path,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException unsupported) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
