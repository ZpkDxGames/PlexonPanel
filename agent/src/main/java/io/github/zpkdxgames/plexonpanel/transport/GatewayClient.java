package io.github.zpkdxgames.plexonpanel.transport;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.config.ControlPolicy;
import io.github.zpkdxgames.plexonpanel.config.PanelSettings;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.identity.KeyCodec;
import io.github.zpkdxgames.plexonpanel.identity.PairingCodeGenerator;
import io.github.zpkdxgames.plexonpanel.identity.PairingState;
import io.github.zpkdxgames.plexonpanel.model.HelloPayload;
import io.github.zpkdxgames.plexonpanel.protocol.DecodedMessage;
import io.github.zpkdxgames.plexonpanel.protocol.MessagePriority;
import io.github.zpkdxgames.plexonpanel.protocol.MessageSink;
import io.github.zpkdxgames.plexonpanel.protocol.ProtocolCodec;
import io.github.zpkdxgames.plexonpanel.protocol.ReplayGuard;
import io.github.zpkdxgames.plexonpanel.security.DeviceRegistry;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class GatewayClient implements MessageSink, AutoCloseable {
  private final JavaPlugin plugin;
  private final PanelSettings.Gateway settings;
  private final DeviceIdentity identity;
  private final PairingState pairingState;
  private final ControlPolicy policy;
  private final DeviceRegistry devices;
  private final io.github.zpkdxgames.plexonpanel.audit.LocalAudit accessAudit;
  private final PairingCodeGenerator pairingCodeGenerator;
  private final Map<String, Boolean> capabilities;
  private final ProtocolCodec codec;
  private final ReplayGuard replayGuard;
  private final PublicKey gatewayPublicKey;
  private final ArrayBlockingQueue<OutboundMessage> outbound;
  private final ScheduledExecutorService scheduler;
  private final ExecutorService senderExecutor;
  private final ExecutorService httpExecutor;
  private final HttpClient httpClient;
  private final String paperVersion;
  private final String minecraftVersion;
  private final AtomicReference<ConnectionState> state =
      new AtomicReference<>(ConnectionState.DISABLED);
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean connecting = new AtomicBoolean();
  private final AtomicBoolean authenticated = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicLong droppedMessages = new AtomicLong();
  private final io.github.zpkdxgames.plexonpanel.protocol.AgentSession wireSession =
      new io.github.zpkdxgames.plexonpanel.protocol.AgentSession();
  private volatile WebSocket webSocket;
  private volatile Consumer<DecodedMessage> inboundHandler = ignored -> {};
  private volatile Runnable connectedHandler = () -> {};
  private volatile String lastError = "";
  private volatile int reconnectAttempts;
  private volatile Instant lastConnectedAt;
  private volatile Instant lastMessageAt;
  private ScheduledFuture<?> heartbeatTask;
  private ScheduledFuture<?> authenticationTimeoutTask;

  public GatewayClient(
      JavaPlugin plugin,
      PanelSettings panelSettings,
      DeviceIdentity identity,
      PairingState pairingState,
      ControlPolicy policy,
      DeviceRegistry devices,
      io.github.zpkdxgames.plexonpanel.audit.LocalAudit accessAudit) {
    this.plugin = plugin;
    this.settings = panelSettings.gateway();
    this.identity = identity;
    this.pairingState = pairingState;
    this.policy = policy;
    this.devices = devices;
    this.accessAudit = accessAudit;
    this.pairingCodeGenerator = new PairingCodeGenerator();
    PanelSettings.RemoteActions remoteSettings = panelSettings.remoteActions();
    this.capabilities = policy.capabilities();
    this.codec = new ProtocolCodec();
    this.replayGuard = new ReplayGuard(remoteSettings.maximumClockSkew(), 8192);
    this.gatewayPublicKey =
        settings.publicKeyBase64().isBlank()
            ? null
            : KeyCodec.decodePublic(settings.publicKeyBase64());
    this.outbound = new ArrayBlockingQueue<>(settings.queueCapacity());
    this.scheduler =
        Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("plexonpanel-gateway"));
    this.senderExecutor =
        Executors.newSingleThreadExecutor(new NamedThreadFactory("plexonpanel-sender"));
    this.httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
            .executor(httpExecutor)
            .build();
    this.paperVersion = plugin.getServer().getVersion();
    this.minecraftVersion = plugin.getServer().getMinecraftVersion();
  }

  public void setInboundHandler(Consumer<DecodedMessage> handler) {
    this.inboundHandler = Objects.requireNonNull(handler, "handler");
  }

  public void setConnectedHandler(Runnable handler) {
    this.connectedHandler = Objects.requireNonNull(handler, "handler");
  }

  public void start() {
    if (closed.get()) {
      throw new IllegalStateException("Relay client is closed");
    }
    if (!settings.enabled()) {
      state.set(ConnectionState.DISABLED);
      return;
    }
    if (settings.url() == null) {
      state.set(ConnectionState.DISABLED);
      lastError = "Relay URL is not configured";
      return;
    }
    if (!running.compareAndSet(false, true)) {
      return;
    }
    senderExecutor.execute(this::senderLoop);
    scheduleConnect(0L);
  }

  public synchronized boolean requestPairingCode() {
    return requestPairingCode(policy.defaultRole());
  }

  public synchronized boolean requestPairingCode(String requestedRole) {
    String role = policy.roleName(requestedRole);
    if (state.get() != ConnectionState.CONNECTED || !authenticated.get()) {
      return false;
    }
    PairingCodeGenerator.GeneratedCode code = pairingCodeGenerator.generate();
    devices.begin(
        code.requestId(),
        role,
        policy.roles().get(role),
        code.expiresAt(),
        policy.credentialDays());
    pairingState.beginCode(code.requestId(), code.value(), code.expiresAt());
    boolean sent =
        send(
            "agent.pairing_begin",
            Map.of(
                "requestId", code.requestId(),
                "code", code.value(),
                "requestedAt", code.createdAt().toString(),
                "expiresAt", code.expiresAt().toString(),
                "fingerprint", identity.fingerprint(),
                "role", role,
                "scopes", policy.roles().get(role),
                "credentialDays", policy.credentialDays()),
            MessagePriority.CRITICAL);
    if (!sent) {
      pairingState.rejectCode(code.requestId());
    }
    return sent;
  }

  public void requestUnpair() throws IOException {
    auditAccess("devices.revoke-all", "all devices", "STARTED");
    devices.revokeAll();
    pairingState.clear();
    auditAccess("devices.revoke-all", "all devices", "SUCCESS");
    send("access.sync", devices.snapshot(), MessagePriority.CRITICAL);
  }

  public void revokeDevice(String deviceId) throws IOException {
    auditAccess("devices.revoke", deviceId, "STARTED");
    devices.revoke(deviceId);
    send("access.sync", devices.snapshot(), MessagePriority.CRITICAL);
    auditAccess("devices.revoke", deviceId, "SUCCESS");
  }

  private void auditAccess(String action, String target, String outcome) throws IOException {
    Map<String, Object> entry = new java.util.LinkedHashMap<>();
    entry.put("timestamp", Instant.now().toString());
    entry.put("requestId", UUID.randomUUID().toString());
    entry.put("serverId", identity.serverId().toString());
    entry.put("deviceId", "local-operator");
    entry.put("role", "Local");
    entry.put("actorLabel", "Local operator");
    entry.put("actionType", action);
    entry.put("target", target);
    entry.put("outcome", outcome);
    entry.put("durationMillis", 0);
    entry.put("metadata", Map.of());
    accessAudit.append(entry);
  }

  @Override
  public synchronized boolean send(String type, Object body, MessagePriority priority) {
    if (state.get() != ConnectionState.CONNECTED || !running.get()) {
      return false;
    }
    if (!authenticated.get() && !isPreAuthenticationMessage(type)) {
      return false;
    }
    String encoded;
    try {
      encoded = codec.encodeSigned(type, wireSession.stamp(body), identity);
    } catch (RuntimeException error) {
      plugin.getLogger().log(Level.WARNING, "Refused to encode outbound message " + type, error);
      return false;
    }
    OutboundMessage message = new OutboundMessage(type, encoded, priority, wireSession.nonce());
    if (outbound.offer(message)) {
      return true;
    }
    if (priority == MessagePriority.CRITICAL && evictNonCritical()) {
      return outbound.offer(message);
    }
    droppedMessages.incrementAndGet();
    return false;
  }

  private boolean evictNonCritical() {
    Optional<OutboundMessage> candidate =
        outbound.stream()
            .filter(message -> message.priority() != MessagePriority.CRITICAL)
            .findFirst();
    return candidate.filter(outbound::remove).isPresent();
  }

  private static boolean isPreAuthenticationMessage(String type) {
    return "agent.hello".equals(type) || "agent.challenge_response".equals(type);
  }

  private void scheduleConnect(long delaySeconds) {
    if (!running.get()) {
      return;
    }
    state.set(delaySeconds == 0L ? ConnectionState.CONNECTING : ConnectionState.BACKOFF);
    scheduler.schedule(this::connect, delaySeconds, TimeUnit.SECONDS);
  }

  private void connect() {
    if (!running.get() || !connecting.compareAndSet(false, true)) {
      return;
    }
    state.set(ConnectionState.CONNECTING);
    httpClient
        .newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
        .header("User-Agent", "PlexonPanel/" + plugin.getPluginMeta().getVersion())
        .header("X-PlexonPanel-Protocol", Integer.toString(ProtocolCodec.VERSION))
        .buildAsync(agentEndpoint(), new GatewayWebSocketListener())
        .whenComplete(
            (socket, error) -> {
              connecting.set(false);
              if (error != null) {
                handleDisconnect("Connection failed: " + rootMessage(error));
              }
            });
  }

  private void senderLoop() {
    while (running.get() && !Thread.currentThread().isInterrupted()) {
      try {
        OutboundMessage message = outbound.take();
        java.util.concurrent.CompletableFuture<WebSocket> sent;
        synchronized (this) {
          WebSocket socket = webSocket;
          if (socket == null
              || state.get() != ConnectionState.CONNECTED
              || !message.session().equals(wireSession.nonce())) {
            droppedMessages.incrementAndGet();
            continue;
          }
          sent = socket.sendText(message.encoded(), true);
        }
        sent.get(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception error) {
        handleDisconnect("Send failed: " + rootMessage(error));
      }
    }
  }

  private void sendHello() {
    HelloPayload payload =
        new HelloPayload(
            "PlexonPanel",
            plugin.getPluginMeta().getVersion(),
            ProtocolCodec.VERSION,
            identity.publicKeyBase64(),
            identity.fingerprint(),
            paperVersion,
            minecraftVersion,
            System.getProperty("java.version", "unknown"),
            System.getProperty("os.name", "unknown")
                + " "
                + System.getProperty("os.arch", "unknown"),
            pairingState.isPaired(),
            capabilities,
            "PAPER",
            policy.hostPublicKey());
    send("agent.hello", payload, MessagePriority.CRITICAL);
  }

  private void handleInbound(String json) {
    try {
      DecodedMessage message = codec.decode(json);
      if (!identity.serverId().toString().equals(message.envelope().serverId())) {
        throw new SecurityException("Message serverId does not match this agent");
      }
      if (gatewayPublicKey == null) {
        if (settings.requireSignedMessages()) {
          throw new SecurityException("Relay public key is not configured");
        }
      } else if (!codec.verify(message.envelope(), gatewayPublicKey)) {
        throw new SecurityException("Invalid relay message signature");
      }
      if (!replayGuard.accept(
          codec.messageId(message.envelope()), codec.timestamp(message.envelope()))) {
        throw new SecurityException("Expired or replayed relay message");
      }
      lastMessageAt = Instant.now();
      if (handleControlMessage(message)) {
        return;
      }
      if (gatewayPublicKey == null) {
        throw new SecurityException("Privileged messages require a configured relay public key");
      }
      if (!authenticated.get()) throw new SecurityException("Session is not authenticated");
      inboundHandler.accept(message);
    } catch (Exception error) {
      lastError = "Inbound message rejected: " + rootMessage(error);
      plugin.getLogger().warning(lastError);
    }
  }

  private boolean handleControlMessage(DecodedMessage message) throws IOException {
    JsonObject body = message.body();
    return switch (message.envelope().type()) {
      case "gateway.challenge" -> {
        String nonce = requiredString(body, "nonce");
        String proof =
            identity.signBase64Url(("challenge:" + nonce).getBytes(StandardCharsets.UTF_8));
        send(
            "agent.challenge_response",
            Map.of("nonce", nonce, "proof", proof),
            MessagePriority.CRITICAL);
        yield true;
      }
      case "gateway.authenticated" -> {
        if (!body.has("protocolVersion") || body.get("protocolVersion").getAsInt() != 3)
          throw new SecurityException("Protocol mismatch");
        authenticated.set(true);
        cancelAuthenticationTimeout();
        if (requiredBoolean(body, "paired")) {
          pairingState.markPaired();
        } else {
          pairingState.clear();
        }
        runConnectedHandler("Authenticated handler failed");
        yield true;
      }
      case "gateway.snapshot_request" -> {
        runConnectedHandler("Snapshot refresh handler failed");
        yield true;
      }
      case "pairing.registered" -> {
        pairingState.markCodeRegistered(
            requiredString(body, "requestId"),
            requiredString(body, "challengeId"),
            Instant.parse(requiredString(body, "expiresAt")));
        yield true;
      }
      case "pairing.rejected" -> {
        pairingState.rejectCode(requiredString(body, "requestId"));
        yield true;
      }
      case "pairing.consume" -> {
        if (!authenticated.get()) throw new SecurityException("Session is not authenticated");
        String requestId = requiredString(body, "requestId");
        try {
          DeviceRegistry.Device device =
              devices.consume(
                  requestId, requiredString(body, "deviceId"), requiredString(body, "name"));
          pairingState.markPaired();
          var accessState = devices.snapshot();
          if (!accessState.devices().contains(device))
            throw new SecurityException("DEVICE_REVOKED");
          accessAudit.append(
              Map.of(
                  "timestamp",
                  Instant.now().toString(),
                  "requestId",
                  requestId,
                  "serverId",
                  identity.serverId().toString(),
                  "deviceId",
                  device.deviceId(),
                  "role",
                  device.role(),
                  "actorLabel",
                  device.name(),
                  "actionType",
                  "devices.pair",
                  "outcome",
                  "SUCCESS"));
          send(
              "pairing.accepted",
              Map.of(
                  "requestId",
                  requestId,
                  "device",
                  device,
                  "generation",
                  accessState.generation(),
                  "revision",
                  accessState.revision()),
              MessagePriority.CRITICAL);
          send("access.sync", devices.snapshot(), MessagePriority.CRITICAL);
        } catch (Exception error) {
          send("pairing.denied", Map.of("requestId", requestId), MessagePriority.CRITICAL);
        }
        yield true;
      }
      case "pairing.complete" -> {
        pairingState.markPaired();
        yield true;
      }
      case "pairing.revoked" -> {
        pairingState.clear();
        yield true;
      }
      case "heartbeat.ack", "paper.connection" -> true;
      default -> false;
    };
  }

  private void scheduleHeartbeat() {
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
    }
    heartbeatTask =
        scheduler.scheduleAtFixedRate(
            () -> {
              WebSocket socket = webSocket;
              if (socket != null && state.get() == ConnectionState.CONNECTED) {
                long epochMillis = System.currentTimeMillis();
                socket
                    .sendPing(
                        ByteBuffer.wrap(
                            Long.toString(epochMillis).getBytes(StandardCharsets.US_ASCII)))
                    .whenComplete(
                        (ignored, error) -> {
                          if (error != null && running.get()) {
                            scheduler.execute(
                                () -> handleDisconnect("Heartbeat failed: " + rootMessage(error)));
                          }
                        });
              }
            },
            settings.heartbeatSeconds(),
            settings.heartbeatSeconds(),
            TimeUnit.SECONDS);
  }

  private synchronized void scheduleAuthenticationTimeout(WebSocket expectedSocket) {
    cancelAuthenticationTimeout();
    authenticationTimeoutTask =
        scheduler.schedule(
            () -> {
              if (running.get() && webSocket == expectedSocket && !authenticated.get()) {
                handleDisconnect("Relay authentication timed out");
              }
            },
            Math.max(10, settings.connectTimeoutSeconds()),
            TimeUnit.SECONDS);
  }

  private synchronized void cancelAuthenticationTimeout() {
    if (authenticationTimeoutTask != null) {
      authenticationTimeoutTask.cancel(false);
      authenticationTimeoutTask = null;
    }
  }

  private java.net.URI agentEndpoint() {
    return java.net.URI.create(settings.url() + "?serverId=" + identity.serverId());
  }

  private void runConnectedHandler(String failureMessage) {
    try {
      connectedHandler.run();
    } catch (RuntimeException error) {
      plugin.getLogger().log(Level.WARNING, failureMessage, error);
    }
  }

  private synchronized void handleDisconnect(String reason) {
    if (!running.get()) {
      return;
    }
    if (state.get() == ConnectionState.BACKOFF && webSocket == null) {
      return;
    }
    lastError = reason;
    authenticated.set(false);
    WebSocket socket = webSocket;
    webSocket = null;
    if (socket != null && !socket.isOutputClosed()) {
      socket.abort();
    }
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
      heartbeatTask = null;
    }
    cancelAuthenticationTimeout();
    outbound.clear();
    reconnectAttempts = Math.min(reconnectAttempts + 1, 30);
    long factor = 1L << Math.min(reconnectAttempts - 1, 10);
    long delay =
        Math.min(
            settings.maximumReconnectDelaySeconds(),
            settings.initialReconnectDelaySeconds() * factor);
    scheduleConnect(delay);
  }

  public ConnectionState state() {
    return state.get();
  }

  public long droppedMessages() {
    return droppedMessages.get();
  }

  public String lastError() {
    return lastError;
  }

  public Instant lastConnectedAt() {
    return lastConnectedAt;
  }

  public Instant lastMessageAt() {
    return lastMessageAt;
  }

  public boolean hasGatewayVerificationKey() {
    return gatewayPublicKey != null;
  }

  public boolean isAuthenticated() {
    return authenticated.get();
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    running.set(false);
    authenticated.set(false);
    state.set(ConnectionState.STOPPED);
    if (heartbeatTask != null) {
      heartbeatTask.cancel(false);
    }
    cancelAuthenticationTimeout();
    WebSocket socket = webSocket;
    webSocket = null;
    if (socket != null && !socket.isOutputClosed()) {
      try {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "Plugin disabled").get(2, TimeUnit.SECONDS);
      } catch (Exception ignored) {
        socket.abort();
      }
    }
    outbound.clear();
    senderExecutor.shutdownNow();
    scheduler.shutdownNow();
    httpExecutor.shutdownNow();
  }

  private static String requiredString(JsonObject object, String name) {
    if (!object.has(name) || !object.get(name).isJsonPrimitive()) {
      throw new IllegalArgumentException("Missing string field: " + name);
    }
    String value = object.get(name).getAsString();
    if (value.isBlank() || value.length() > 4096) {
      throw new IllegalArgumentException("Invalid string field: " + name);
    }
    return value;
  }

  private static boolean requiredBoolean(JsonObject object, String name) {
    if (!object.has(name)
        || !object.get(name).isJsonPrimitive()
        || !object.get(name).getAsJsonPrimitive().isBoolean()) {
      throw new IllegalArgumentException("Missing boolean field: " + name);
    }
    return object.get(name).getAsBoolean();
  }

  private static String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private record OutboundMessage(
      String type, String encoded, MessagePriority priority, String session) {}

  private final class GatewayWebSocketListener implements WebSocket.Listener {
    private final StringBuilder fragments = new StringBuilder();

    @Override
    public void onOpen(WebSocket socket) {
      synchronized (GatewayClient.this) {
        if (!running.get()) {
          socket.abort();
          return;
        }
        wireSession.reset();
        outbound.clear();
        webSocket = socket;
        authenticated.set(false);
        state.set(ConnectionState.CONNECTED);
        reconnectAttempts = 0;
        lastConnectedAt = Instant.now();
        lastError = "";
        socket.request(1);
        sendHello();
        scheduleAuthenticationTimeout(socket);
        scheduleHeartbeat();
      }
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      if (socket != webSocket) return null;
      if (fragments.length() + data.length() > ProtocolCodec.MAX_ENVELOPE_BYTES) {
        fragments.setLength(0);
        socket.abort();
        plugin.getLogger().warning("Rejected oversized relay WebSocket message");
        return null;
      }
      fragments.append(data);
      if (last) {
        String complete = fragments.toString();
        fragments.setLength(0);
        if (running.get()) {
          handleInbound(complete);
        }
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
      lastMessageAt = Instant.now();
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
      if (running.get()) {
        scheduler.execute(
            () -> handleDisconnect("Relay closed connection (" + statusCode + "): " + reason));
      }
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable error) {
      if (running.get()) {
        scheduler.execute(() -> handleDisconnect("WebSocket error: " + rootMessage(error)));
      }
    }
  }
}
