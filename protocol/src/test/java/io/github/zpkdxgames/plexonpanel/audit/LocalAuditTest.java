package io.github.zpkdxgames.plexonpanel.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalAuditTest {
  @TempDir Path directory;

  @Test
  void durableIntentRejectsDuplicateAfterRestart() throws Exception {
    var intent =
        Map.<String, Object>of(
            "requestId",
            UUID.randomUUID().toString(),
            "timestamp",
            Instant.now().toString(),
            "outcome",
            "STARTED",
            "deviceId",
            "browser");
    new LocalAudit(directory, 30).begin(intent);
    var restarted = new LocalAudit(directory, 30);
    assertThrows(SecurityException.class, () -> restarted.begin(intent));
    assertEquals(
        1,
        ((java.util.List<?>) restarted.list("browser", true, new JsonObject()).get("entries"))
            .size());
  }
}
