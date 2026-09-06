package io.github.zpkdxgames.plexonpanel.security;

import java.time.*;
import java.util.*;

/** Fail closed at capacity: never evict a still-valid replay marker to admit an attacker. */
public final class RequestGate {
  private final Clock clock;
  private final Map<String, Long> ids = new HashMap<>();
  private final Map<String, ArrayDeque<Long>> rates = new HashMap<>();

  public RequestGate() {
    this(Clock.systemUTC());
  }

  public RequestGate(Clock clock) {
    this.clock = clock;
  }

  public synchronized void accept(String device, String requestId) {
    accept(device, requestId, false);
  }

  public synchronized void accept(String device, String requestId, boolean transfer) {
    UUID.fromString(requestId);
    UUID.fromString(device);
    long now = clock.millis();
    ids.entrySet().removeIf(e -> e.getValue() <= now - 120000);
    rates.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().getLast() <= now - 10000);
    if (ids.containsKey(requestId)) throw new SecurityException("DUPLICATE_REQUEST");
    if (ids.size() >= 4096
        || rates.size() >= 128 && !rates.containsKey(device + (transfer ? ":transfer" : "")))
      throw new SecurityException("BUSY");
    var rate =
        rates.computeIfAbsent(device + (transfer ? ":transfer" : ""), x -> new ArrayDeque<>());
    while (!rate.isEmpty() && rate.getFirst() <= now - 10000) rate.removeFirst();
    if (rate.size() >= (transfer ? 160 : 20)) throw new SecurityException("RATE_LIMITED");
    rate.addLast(now);
    ids.put(requestId, now);
  }
}
