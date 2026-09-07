package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.*;
import io.github.zpkdxgames.plexonpanel.protocol.*;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public final class HostConnection implements MessageSink, AutoCloseable {
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

  public void start() {
    running.set(true);
    sender =
        Thread.ofPlatform()
            .daemon(true)
            .name("plexonpanel-host-send")
            .start(
                () -> {
                  while (running.get()) {
                    try {
                      Packet packet = queue.take();
                      CompletableFuture<WebSocket> sent = null;
                      synchronized (HostConnection.this) {
                        WebSocket current = socket;
                        if (current != null && packet.session.equals(wireSession.nonce()))
                          sent = current.sendText(packet.text, true);
                      }
                      if (sent != null) sent.get(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                      return;
                    } catch (Exception e) {
                      disconnect();
                    }
                  }
                });
    scheduler.scheduleWithFixedDelay(
        () -> {
          if (!running.get()) return;
          if (socket == null && System.currentTimeMillis() >= nextAttempt) connect();
          else if (socket != null && System.currentTimeMillis() - lastMessage > 45000) disconnect();
          else if (socket != null && authenticated)
            socket.sendPing(java.nio.ByteBuffer.wrap(new byte[] {1}));
        },
        0,
        5,
        TimeUnit.SECONDS);
  }

  private void connect() {
    if (!connecting.compareAndSet(false, true)) return;
    try {
      http.newWebSocketBuilder()
          .header("X-PlexonPanel-Protocol", "3")
          .connectTimeout(Duration.ofSeconds(10))
          .buildAsync(
              URI.create(config.relayUrl() + "?serverId=" + config.serverId() + "&agentKind=HOST"),
              new Listener())
          .whenComplete(
              (ws, e) -> {
                connecting.set(false);
                if (e != null) {
                  backoff();
                }
              });
    } catch (Exception e) {
      connecting.set(false);
      backoff();
    }
  }

  private synchronized void backoff() {
    attempts = Math.min(attempts + 1, 6);
    nextAttempt =
        System.currentTimeMillis()
            + Math.min(60000, 1000L * (1L << attempts))
            + ThreadLocalRandom.current().nextInt(1000);
  }

  private synchronized void disconnect() {
    authenticated = false;
    WebSocket old = socket;
    socket = null;
    if (old != null) {
      old.abort();
      backoff();
    }
    queue.clear();
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
        lastMessage = System.currentTimeMillis();
        authenticated = false;
        ws.request(1);
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("agentName", "PlexonPanel Host");
        hello.put("pluginVersion", "3.0.0");
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
        send("agent.hello", hello, MessagePriority.CRITICAL);
      }
    }

    public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
      if (ws != socket) return null;
      if (fragments.length() + text.length() > ProtocolCodec.MAX_ENVELOPE_BYTES) {
        disconnect();
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
              send(
                  "agent.challenge_response",
                  Map.of(
                      "nonce",
                      nonce,
                      "proof",
                      identity.signBase64Url(
                          ("challenge:" + nonce).getBytes(StandardCharsets.UTF_8))),
                  MessagePriority.CRITICAL);
            }
            case "gateway.authenticated" -> {
              if (m.body().get("protocolVersion").getAsInt() != 3) throw new SecurityException();
              authenticated = true;
              attempts = 0;
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
          disconnect();
        }
      }
      ws.request(1);
      return null;
    }

    public CompletionStage<?> onPong(WebSocket ws, java.nio.ByteBuffer bytes) {
      lastMessage = System.currentTimeMillis();
      ws.request(1);
      return null;
    }

    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      if (ws == socket) disconnect();
      return null;
    }

    public void onError(WebSocket ws, Throwable error) {
      if (ws == socket) disconnect();
    }
  }

  public void close() {
    running.set(false);
    disconnect();
    if (sender != null) sender.interrupt();
    scheduler.shutdownNow();
    http.shutdownNow();
  }
}
