package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.*;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class HostConnection implements MessageSink, AutoCloseable {
  private static final long LINK_TICK_SECONDS = 5L;
  private static final long AUTHENTICATION_TIMEOUT_MILLIS = 15_000L;
  private static final long INBOUND_TIMEOUT_MILLIS = 45_000L;
  private static final long INITIAL_RECONNECT_MILLIS = 2_000L;
  private static final long MAXIMUM_RECONNECT_MILLIS = 180_000L;

  private final HostConfig config;
  private final DeviceIdentity identity;
  private final ProtocolCodec codec = new ProtocolCodec();
  private final ReplayGuard replay = new ReplayGuard(Duration.ofSeconds(30), 8192);
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("plexonpanel-host-link"));
  private final AgentSession wireSession = new AgentSession();

  private record Packet(String text, String session) {}

  private final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(128);
  private final AtomicBoolean running = new AtomicBoolean(), connecting = new AtomicBoolean();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private volatile long nextAttempt;
  private volatile WebSocket socket;
  private volatile boolean authenticated;
  private volatile long lastMessage;
  private volatile long connectedAt;
  private volatile String lastFailure = "";
  private int attempts;
  private Thread sender;
  private volatile Consumer<DecodedMessage> handler = m -> {};
  private volatile Runnable connected = () -> {};

  public HostConnection(HostConfig config, DeviceIdentity identity) {
    this.config = config;
    this.identity = identity;
  }

  public void handlers(Consumer<DecodedMessage> handler, Runnable connected) {
    this.handler = handler;
    this.connected = connected;
  }

  public boolean authenticated() {
    return authenticated;
  }

  public synchronized int reconnectAttempts() {
    return attempts;
  }

  public synchronized long nextRetryMillis() {
    return Math.max(0L, nextAttempt - System.currentTimeMillis());
  }

  public String lastFailure() {
    return lastFailure;
  }

  public void start() {
    if (!running.compareAndSet(false, true)) return;
    sender =
        Thread.ofPlatform()
            .daemon(true)
            .name("plexonpanel-host-send")
            .start(
                () -> {
                  while (running.get()) {
                    WebSocket attempted = null;
                    try {
                      Packet packet = queue.take();
                      CompletableFuture<WebSocket> sent = null;
                      synchronized (HostConnection.this) {
                        attempted = socket;
                        if (attempted != null && packet.session.equals(wireSession.nonce()))
                          sent = attempted.sendText(packet.text, true);
                      }
                      if (sent != null) sent.get(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    } catch (Exception e) {
                      disconnect(attempted, "Outbound send failed: " + describe(e));
                    }
                  }
                });
    scheduler.scheduleWithFixedDelay(
        this::maintainConnectionSafely, 0, LINK_TICK_SECONDS, TimeUnit.SECONDS);
  }

  /** Keep the watchdog fail-safe: an exception must never suppress all future link checks. */
  private void maintainConnectionSafely() {
    try {
      if (!running.get()) return;
      WebSocket current = socket;
      long now = System.currentTimeMillis();
      if (current == null) {
        if (now >= nextAttempt) connect();
        return;
      }
      if (!authenticated && now - connectedAt > AUTHENTICATION_TIMEOUT_MILLIS) {
        disconnect(current, "Relay authentication timed out");
        return;
      }
      if (now - lastMessage > INBOUND_TIMEOUT_MILLIS) {
        disconnect(current, "Relay heartbeat timed out");
        return;
      }
      if (authenticated) {
        try {
          current
              .sendPing(ByteBuffer.wrap(new byte[] {1}))
              .whenComplete(
                  (ignored, error) -> {
                    if (error != null)
                      disconnect(current, "Relay ping failed: " + describe(error));
                  });
        } catch (RuntimeException error) {
          disconnect(current, "Relay ping failed: " + describe(error));
        }
      }
    } catch (RuntimeException error) {
      disconnect(null, "Relay watchdog recovered from an unexpected failure: " + describe(error));
    }
  }

  private void connect() {
    if (!running.get() || !connecting.compareAndSet(false, true)) return;
    try {
      http.newWebSocketBuilder()
          .header("X-PlexonPanel-Protocol", "3")
          .connectTimeout(Duration.ofSeconds(10))
          .buildAsync(
              URI.create(config.relayUrl() + "?serverId=" + config.serverId() + "&agentKind=HOST"),
              new Listener())
          .whenComplete(
              (ws, error) -> {
                connecting.set(false);
                if (error != null) connectionFailed("Connection failed: " + describe(error));
              });
    } catch (RuntimeException error) {
      connecting.set(false);
      connectionFailed("Connection failed: " + describe(error));
    }
  }

  private synchronized void connectionFailed(String reason) {
    if (!running.get()) return;
    lastFailure = reason;
    backoff();
    log(reason + "; retry in approximately " + Math.max(1L, nextRetryMillis() / 1000L) + "s");
  }

  private synchronized void backoff() {
    attempts = Math.min(attempts + 1, 30);
    long delay =
        ReconnectBackoff.delayMillis(
            attempts,
            INITIAL_RECONNECT_MILLIS,
            MAXIMUM_RECONNECT_MILLIS,
            ThreadLocalRandom.current());
    nextAttempt = System.currentTimeMillis() + delay;
  }

  private synchronized void disconnect(WebSocket expected, String reason) {
    if (expected != null && socket != expected) return;
    boolean hadSession = socket != null || authenticated;
    authenticated = false;
    WebSocket old = socket;
    socket = null;
    connectedAt = 0L;
    lastMessage = 0L;
    queue.clear();
    if (old != null) old.abort();
    if (running.get()) backoff();
    if (reason != null && !reason.isBlank()) lastFailure = reason;
    if (hadSession || expected == null) log(reason);
  }

  public synchronized boolean send(String type, Object body, MessagePriority priority) {
    if (!running.get()
        || socket == null
        || !authenticated && !Set.of("agent.hello", "agent.challenge_response").contains(type))
      return false;
    try {
      String value = codec.encodeSigned(type, wireSession.stamp(body), identity);
      return queue.offer(new Packet(value, wireSession.nonce()));
    } catch (RuntimeException e) {
      return false;
    }
  }

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder fragments = new StringBuilder();

    public void onOpen(WebSocket ws) {
      synchronized (HostConnection.this) {
        if (!running.get()) {
          ws.abort();
          return;
        }
        wireSession.reset();
        queue.clear();
        socket = ws;
        connectedAt = System.currentTimeMillis();
        lastMessage = connectedAt;
        authenticated = false;
        requestNext(ws);
        if (ws != socket) return;
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("agentName", "PlexonPanel Host");
        hello.put("pluginVersion", implementationVersion());
        hello.put("protocolVersion", 3);
        hello.put("publicKey", identity.publicKeyBase64());
        hello.put("publicKeyFingerprint", identity.fingerprint());
        hello.put("paperVersion", "host companion");
        hello.put("minecraftVersion", "26.2");
        hello.put("javaVersion", System.getProperty("java.version"));
        hello.put(
            "operatingSystem", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        hello.put("capabilities", config.effectiveCapabilities());
        hello.put("agentKind", "HOST");
        hello.put("hostPublicKey", "");
        if (!send("agent.hello", hello, MessagePriority.CRITICAL))
          disconnect(ws, "Failed to queue Host hello");
      }
    }

    public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
      if (ws != socket) return null;
      if (fragments.length() + text.length() > ProtocolCodec.MAX_ENVELOPE_BYTES) {
        disconnect(ws, "Relay message exceeded protocol limits");
        return null;
      }
      fragments.append(text);
      if (last) {
        String value = fragments.toString();
        fragments.setLength(0);
        try {
          var m = codec.decode(value);
          if (!m.envelope().serverId().equals(config.serverId())
              || !codec.verify(m.envelope(), KeyCodec.decodePublic(config.relayPublicKey()))
              || !replay.accept(codec.messageId(m.envelope()), codec.timestamp(m.envelope())))
            throw new SecurityException("Invalid relay message");
          lastMessage = System.currentTimeMillis();
          switch (m.envelope().type()) {
            case "gateway.challenge" -> {
              String nonce = m.body().get("nonce").getAsString();
              if (nonce.length() > 128) throw new SecurityException();
              if (!send(
                  "agent.challenge_response",
                  Map.of(
                      "nonce",
                      nonce,
                      "proof",
                      identity.signBase64Url(
                          ("challenge:" + nonce).getBytes(StandardCharsets.UTF_8))),
                  MessagePriority.CRITICAL))
                throw new IllegalStateException("Challenge response queue failed");
            }
            case "gateway.authenticated" -> {
              if (m.body().get("protocolVersion").getAsInt() != 3) throw new SecurityException();
              authenticated = true;
              attempts = 0;
              nextAttempt = 0L;
              lastFailure = "";
              log("Authenticated with relay");
              connected.run();
            }
            case "gateway.snapshot_request" -> {
              if (authenticated) connected.run();
            }
            default -> {
              if (authenticated) handler.accept(m);
            }
          }
        } catch (Exception e) {
          disconnect(ws, "Inbound relay message rejected: " + describe(e));
          return null;
        }
      }
      requestNext(ws);
      return null;
    }

    public CompletionStage<?> onPong(WebSocket ws, ByteBuffer bytes) {
      if (ws != socket) return null;
      lastMessage = System.currentTimeMillis();
      requestNext(ws);
      return null;
    }

    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      disconnect(ws, "Relay closed connection (" + code + "): " + reason);
      return null;
    }

    public void onError(WebSocket ws, Throwable error) {
      disconnect(ws, "Relay WebSocket error: " + describe(error));
    }
  }

  private void requestNext(WebSocket ws) {
    if (ws != socket) return;
    try {
      ws.request(1);
    } catch (RuntimeException error) {
      disconnect(ws, "Relay receive request failed: " + describe(error));
    }
  }

  private static String implementationVersion() {
    String version = HostConnection.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }

  private static String describe(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) current = current.getCause();
    String message = current.getMessage();
    return current.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static void log(String message) {
    if (message != null && !message.isBlank()) System.err.println("PlexonPanel Host: " + message);
  }

  public void close() {
    if (!running.compareAndSet(true, false)) return;
    disconnect(null, "");
    if (sender != null) sender.interrupt();
    scheduler.shutdownNow();
    http.shutdownNow();
  }
}
