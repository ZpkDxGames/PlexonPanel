package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.protocol.ProtocolCodec;
import io.github.zpkdxgames.plexonpanel.protocol.ReconnectBackoff;
import io.github.zpkdxgames.plexonpanel.util.NamedThreadFactory;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

final class BedrockConnection implements AutoCloseable {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final long AUTH_TIMEOUT_NANOS = Duration.ofSeconds(15).toNanos();
  private static final long HEARTBEAT_TIMEOUT_NANOS = Duration.ofSeconds(45).toNanos();
  private final BedrockConfig.Host config;
  private final BedrockBroker.Readings readings;
  private final Function<BedrockBroker.Operation, JsonObject> unavailable;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, new NamedThreadFactory("plexonpanel-bedrock"));
  private final ExecutorService sender =
      Executors.newSingleThreadExecutor(new NamedThreadFactory("plexonpanel-bedrock-send"));
  private final ArrayBlockingQueue<Packet> outbound = new ArrayBlockingQueue<>(8);

  private record Packet(Peer peer, WebSocket socket, String wire) {}
  private Peer current;
  private int attempts;
  private long nextAttempt;
  private boolean closed;
  private boolean started;

  BedrockConnection(
      BedrockConfig.Host config,
      BedrockBroker.Readings readings,
      Function<BedrockBroker.Operation, JsonObject> unavailable) {
    this.config = config;
    this.readings = readings;
    this.unavailable = unavailable;
  }

  synchronized void start() {
    if (started || closed) throw new IllegalStateException("Bedrock connection is not restartable");
    started = true;
    sender.execute(this::sendLoop);
    scheduler.scheduleWithFixedDelay(this::maintain, 0, 5, TimeUnit.SECONDS);
    scheduler.scheduleWithFixedDelay(this::sample, 1, 5, TimeUnit.SECONDS);
  }

  private synchronized void maintain() {
    if (closed) return;
    Peer peer = current;
    long now = System.nanoTime();
    try {
      if (peer == null) {
        if (attempts == 0 || now - nextAttempt >= 0) connect();
      } else if (!peer.protocol.authenticated() && now - peer.openedAt > AUTH_TIMEOUT_NANOS) {
        reconnect(peer, "authentication timed out");
      } else if (now - peer.lastInbound > HEARTBEAT_TIMEOUT_NANOS) {
        reconnect(peer, "heartbeat timed out");
      } else if (peer.protocol.authenticated()) {
        peer.socket.sendPing(ByteBuffer.wrap(new byte[] {1}))
            .orTimeout(10, TimeUnit.SECONDS)
            .whenComplete((ignored, error) -> {
              if (error != null) reconnect(peer, "heartbeat send failed");
            });
      }
    } catch (RuntimeException error) {
      reconnect(current, "transport watchdog recovered");
    }
  }

  private void connect() {
    Peer peer = new Peer();
    current = peer;
    URI uri = URI.create(
        config.relayUrl() + "?serverId=" + config.binding().serverId() + "&agentKind=" + BedrockRelayProtocol.KIND);
    http.newWebSocketBuilder()
        .connectTimeout(CONNECT_TIMEOUT)
        .header("X-PlexonPanel-Protocol", Integer.toString(ProtocolCodec.VERSION))
        .buildAsync(uri, peer)
        .orTimeout(10, TimeUnit.SECONDS)
        .whenComplete((socket, error) -> {
          if (error != null) reconnect(peer, "connection failed");
        });
  }

  private void sample() {
    Peer peer;
    synchronized (this) {
      peer = current;
      if (!isAuthenticated(peer)) return;
    }
    for (var operation : BedrockBroker.Operation.values()) {
      JsonObject data;
      try {
        data = readings.read(operation);
      } catch (IOException | RuntimeException ignored) {
        if (Thread.currentThread().isInterrupted()) return;
        data = unavailable.apply(operation);
      }
      synchronized (this) {
        if (!isAuthenticated(peer)) return;
        try {
          enqueue(peer, peer.protocol.reading(operation, data));
        } catch (RuntimeException error) {
          reconnect(peer, "telemetry frame exceeded transport limits");
          return;
        }
      }
    }
  }

  private boolean isAuthenticated(Peer peer) {
    return peer != null && peer == current && peer.protocol.authenticated();
  }

  private void enqueue(Peer peer, String wire) {
    if (!outbound.offer(new Packet(peer, peer.socket, wire))) reconnect(peer, "outbound backlog");
  }

  private void sendLoop() {
    try {
      while (!Thread.currentThread().isInterrupted()) {
        Packet packet = outbound.take();
        try {
          synchronized (this) {
            if (packet.peer() != current || closed) continue;
          }
          packet.socket().sendText(packet.wire(), true).get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | RuntimeException e) {
          reconnect(packet.peer(), "outbound send failed");
        }
      }
    } catch (InterruptedException stopped) {
      Thread.currentThread().interrupt();
    }
  }

  private synchronized void reconnect(Peer peer, String reason) {
    if (peer == null || peer != current || closed) return;
    current = null;
    outbound.clear();
    if (peer.socket != null) peer.socket.abort();
    attempts = Math.min(30, attempts + 1);
    long delay = ReconnectBackoff.delayMillis(attempts, 2_000, 180_000, ThreadLocalRandom.current());
    nextAttempt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delay);
    log(reason);
  }

  private final class Peer implements WebSocket.Listener {
    private final BedrockRelayProtocol protocol = new BedrockRelayProtocol(config);
    private final StringBuilder fragments = new StringBuilder();
    private long openedAt = System.nanoTime();
    private long lastInbound = openedAt;
    private WebSocket socket;

    @Override
    public void onOpen(WebSocket ws) {
      synchronized (BedrockConnection.this) {
        if (this != current || closed) {
          ws.abort();
          return;
        }
        socket = ws;
        openedAt = System.nanoTime();
        lastInbound = openedAt;
        try {
          enqueue(this, protocol.hello());
          if (this == current) ws.request(1);
        } catch (RuntimeException error) {
          reconnect(this, "could not send hello");
        }
      }
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
      synchronized (BedrockConnection.this) {
        if (this != current) return null;
        try {
          if (fragments.length() + data.length() > ProtocolCodec.MAX_ENVELOPE_BYTES)
            throw new SecurityException("Oversized relay frame");
          fragments.append(data);
          if (last) {
            boolean wasAuthenticated = protocol.authenticated();
            String response = protocol.receive(fragments.toString());
            fragments.setLength(0);
            lastInbound = System.nanoTime();
            if (response != null) enqueue(this, response);
            if (!wasAuthenticated && protocol.authenticated()) {
              attempts = 0;
              log("authenticated with read-only Bedrock relay");
            }
          }
          if (this == current) ws.request(1);
        } catch (RuntimeException error) {
          reconnect(this, "relay message rejected");
        }
      }
      return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket ws, ByteBuffer pong) {
      synchronized (BedrockConnection.this) {
        if (this != current) return null;
        if (protocol.authenticated() && pong.remaining() == 1 && pong.get(pong.position()) == 1)
          lastInbound = System.nanoTime();
        try {
          ws.request(1);
        } catch (RuntimeException error) {
          reconnect(this, "receive request failed");
        }
      }
      return null;
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket ws, ByteBuffer bytes, boolean last) {
      reconnect(this, "unsupported relay frame");
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
      reconnect(this, "relay closed");
      return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
      reconnect(this, "relay transport error");
    }
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    if (current != null && current.socket != null) current.socket.abort();
    current = null;
    outbound.clear();
    scheduler.shutdownNow();
    sender.shutdownNow();
    http.shutdownNow();
  }

  private static void log(String reason) {
    System.err.println("PlexonPanel Bedrock Host: " + reason);
  }
}
