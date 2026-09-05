package io.github.zpkdxgames.plexonpanel.control;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.security.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ControlEngineTest {
  @TempDir Path root;

  @Test
  void enforcementPrecedesExecutionAndAuditDoesNotContainParameters() throws Exception {
    String server = UUID.randomUUID().toString();
    var devices = new DeviceRegistry(root.resolve("access.json"), server);
    String pair = UUID.randomUUID().toString();
    devices.begin(pair, "Owner", Scopes.ALL, Instant.now().plusSeconds(60), 1);
    var d = devices.consume(pair, UUID.randomUUID().toString(), "Owner test");
    var audit = new LocalAudit(root.resolve("audit"), 30);
    var responses = new LinkedBlockingQueue<JsonObject>();
    var count = new AtomicInteger();
    Gson gson = new Gson();
    try (var engine =
        new ControlEngine(
            devices,
            Map.of("console.execute.allowed", true, "player.op", false),
            audit,
            null,
            (a, p, dev) -> {
              count.incrementAndGet();
              return Map.of("output", List.of("done"));
            },
            (t, b, priority) -> {
              if (t.equals("action.result")) responses.add(gson.toJsonTree(b).getAsJsonObject());
              return true;
            },
            () -> true,
            server)) {
      String id = UUID.randomUUID().toString();
      var command = new JsonObject();
      command.addProperty("command", "login never-log-this-password");
      engine.accept(
          request(server, d, devices.snapshot().generation(), id, "console.execute", command));
      assertEquals("SUCCESS", responses.poll(3, TimeUnit.SECONDS).get("status").getAsString());
      engine.accept(
          request(server, d, devices.snapshot().generation(), id, "console.execute", command));
      assertEquals(
          "DUPLICATE_REQUEST", responses.poll(3, TimeUnit.SECONDS).get("code").getAsString());
      var op = new JsonObject();
      op.addProperty("confirmed", true);
      engine.accept(
          request(
              server,
              d,
              devices.snapshot().generation(),
              UUID.randomUUID().toString(),
              "player.op",
              op));
      assertEquals(
          "CAPABILITY_DISABLED", responses.poll(3, TimeUnit.SECONDS).get("code").getAsString());
      assertEquals(1, count.get());
    }
    try (var paths = Files.list(root.resolve("audit"))) {
      for (var p : paths.toList()) {
        String log = Files.readString(p);
        assertFalse(log.contains("never-log-this-password"));
        assertTrue(log.contains("console.execute"));
      }
    }
  }

  @Test
  void anUnauthenticatedSessionCannotQueueWork() throws Exception {
    var invoked = new AtomicInteger();
    try (var engine =
        new ControlEngine(
            null,
            Map.of(),
            null,
            null,
            (a, p, d) -> {
              invoked.incrementAndGet();
              return Map.of();
            },
            (t, b, p) -> {
              invoked.incrementAndGet();
              return true;
            },
            () -> false,
            UUID.randomUUID().toString())) {
      engine.accept(
          new DecodedMessage(
              new ProtocolEnvelope(3, "action.request", "", "", "", "", ""), new JsonObject()));
      assertEquals(0, invoked.get());
    }
  }

  static DecodedMessage request(
      String server,
      DeviceRegistry.Device d,
      long generation,
      String id,
      String action,
      JsonObject p) {
    var body = new JsonObject();
    body.addProperty("requestId", id);
    body.addProperty("deviceId", d.deviceId());
    body.addProperty("generation", generation);
    body.addProperty("action", action);
    body.add("parameters", p);
    return new DecodedMessage(
        new ProtocolEnvelope(
            3,
            "action.request",
            UUID.randomUUID().toString(),
            server,
            Instant.now().toString(),
            "",
            ""),
        body);
  }
}
