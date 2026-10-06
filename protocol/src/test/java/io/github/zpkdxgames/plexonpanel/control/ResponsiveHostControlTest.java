package io.github.zpkdxgames.plexonpanel.control;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import io.github.zpkdxgames.plexonpanel.security.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponsiveHostControlTest {
  @TempDir Path root;

  final class Fixture implements AutoCloseable {
    final String server = UUID.randomUUID().toString();
    final DeviceRegistry devices;
    final DeviceRegistry.Device device;
    final BlockingQueue<JsonObject> replies = new LinkedBlockingQueue<>();
    final ControlEngine engine;

    Fixture(ControlEngine.Backend backend, Map<String, Boolean> caps) throws Exception {
      devices = new DeviceRegistry(root.resolve(server + ".json"), server);
      String pair = UUID.randomUUID().toString();
      devices.begin(pair, "Owner", Scopes.ALL, Instant.now().plusSeconds(60), 1);
      device = devices.consume(pair, UUID.randomUUID().toString(), "Host fixture");
      engine = new ControlEngine(devices, caps, new LocalAudit(root.resolve(server + "-audit"), 30),
          null, backend, (type, body, priority) -> {
            if (type.equals("action.result")) replies.add(new Gson().toJsonTree(body).getAsJsonObject());
            return true;
          }, () -> true, server, ControlEngine.DispatchMode.HOST);
    }

    String send(String action) throws Exception {
      String id = UUID.randomUUID().toString();
      var parameters = new JsonObject();
      parameters.addProperty("confirmed", true);
      engine.accept(ControlEngineTest.request(server, device, devices.snapshot().generation(),
          id, action, parameters));
      return id;
    }

    JsonObject reply() throws Exception {
      var result = replies.poll(3, TimeUnit.SECONDS);
      assertNotNull(result, "Host request must finish without waiting for blocked I/O");
      return result;
    }

    public void close() { engine.close(); }
  }

  @Test
  void uploadConflictReturnsBusyAndStatusRemainsAvailableWithoutLateStop() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var operationLock = new ReentrantLock();
    var stops = new AtomicInteger();
    try (var fixture = new Fixture((action, parameters, actor) -> {
      if (action.equals("backup.full.retry-upload")) {
        operationLock.lock();
        try { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
        finally { operationLock.unlock(); }
      } else if (action.equals("server.stop")) {
        if (!operationLock.tryLock()) throw new SecurityException("BUSY");
        try { stops.incrementAndGet(); } finally { operationLock.unlock(); }
      }
      return Map.of("state", "active");
    }, Map.of("backup.create", true, "server.stop", true, "server.status", true))) {
      try {
        fixture.send("backup.full.retry-upload");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        String stop = fixture.send("server.stop");
        var rejected = fixture.reply();
        assertEquals(stop, rejected.get("requestId").getAsString());
        assertEquals("BUSY", rejected.get("code").getAsString());
        assertTrue(rejected.get("message").getAsString().contains("not queued"));
        fixture.send("server.status");
        assertEquals("SUCCESS", fixture.reply().get("status").getAsString());
        assertEquals(0, stops.get());
        release.countDown();
        assertEquals("backup.full.retry-upload", fixture.reply().get("action").getAsString());
        fixture.send("server.status");
        fixture.reply();
        assertEquals(0, stops.get(), "Rejected stop must never execute after upload finishes");
        fixture.send("server.stop");
        assertEquals("SUCCESS", fixture.reply().get("status").getAsString());
        assertEquals(1, stops.get());
      } finally { release.countDown(); }
    }
  }

  @Test
  void secondLifecycleIntentIsRejectedRatherThanQueuedAndStatusCanRunDuringStop() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    try (var fixture = new Fixture((action, parameters, actor) -> {
      if (action.equals("server.stop")) {
        calls.incrementAndGet(); entered.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
      }
      return Map.of("state", "deactivating");
    }, Map.of("server.stop", true, "server.status", true))) {
      try {
        fixture.send("server.stop");
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        String second = fixture.send("server.stop");
        var rejected = fixture.reply();
        assertEquals(second, rejected.get("requestId").getAsString());
        assertEquals("BUSY", rejected.get("code").getAsString());
        fixture.send("server.status");
        assertEquals("deactivating", fixture.reply().getAsJsonObject("data").get("state").getAsString());
        release.countDown();
        assertEquals("SUCCESS", fixture.reply().get("status").getAsString());
        assertEquals(1, calls.get());
      } finally { release.countDown(); }
    }
  }

  @Test
  void independentWorkersStillEnforceCapabilityConfirmationGenerationAndReplay() throws Exception {
    var calls = new AtomicInteger();
    try (var fixture = new Fixture((action, parameters, actor) -> {
      calls.incrementAndGet(); return Map.of();
    }, Map.of("server.start", false, "server.stop", true, "server.status", true))) {
      fixture.send("server.start");
      assertEquals("CAPABILITY_DISABLED", fixture.reply().get("code").getAsString());
      fixture.engine.accept(ControlEngineTest.request(fixture.server, fixture.device,
          fixture.devices.snapshot().generation(), UUID.randomUUID().toString(), "server.stop", new JsonObject()));
      assertEquals("CONFIRMATION_REQUIRED", fixture.reply().get("code").getAsString());
      fixture.engine.accept(ControlEngineTest.request(fixture.server, fixture.device,
          fixture.devices.snapshot().generation() + 1, UUID.randomUUID().toString(), "server.status", new JsonObject()));
      assertEquals("DEVICE_REVOKED", fixture.reply().get("code").getAsString());
      String request = fixture.send("server.status");
      assertEquals("SUCCESS", fixture.reply().get("status").getAsString());
      fixture.engine.accept(ControlEngineTest.request(fixture.server, fixture.device,
          fixture.devices.snapshot().generation(), request, "server.status", new JsonObject()));
      assertEquals("DUPLICATE_REQUEST", fixture.reply().get("code").getAsString());
      assertEquals(1, calls.get());
    }
  }

  @Test
  void shutdownDrainsAllWorkersAndDropsQueuedStatusWork() throws Exception {
    var entered = new CountDownLatch(4);
    var interrupted = new CountDownLatch(4);
    var calls = new AtomicInteger();
    try (var fixture = new Fixture((action, parameters, actor) -> {
      calls.incrementAndGet(); entered.countDown();
      try { new CountDownLatch(1).await(); }
      finally { interrupted.countDown(); }
      return Map.of();
    }, Map.of("backup.create", true, "server.stop", true, "server.status", true))) {
      fixture.send("backup.full.retry-upload");
      fixture.send("server.stop");
      fixture.send("server.status");
      fixture.send("server.status");
      assertTrue(entered.await(3, TimeUnit.SECONDS));
      fixture.send("server.status");
      fixture.engine.beginClose();
      fixture.send("server.status");
      assertTrue(fixture.engine.awaitClosed(Duration.ofSeconds(3)));
      assertEquals(0, interrupted.getCount());
      assertEquals(4, calls.get());
    }
  }
}
