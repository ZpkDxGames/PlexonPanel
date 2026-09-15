package io.github.zpkdxgames.plexonpanel.host;

import java.util.Objects;

/**
 * Caches Minecraft RCON readiness for passive Host telemetry.
 *
 * <p>Passive service snapshots run frequently, so they must not create an RCON connection every
 * time. A real probe is performed once when systemd transitions into active state and whenever an
 * operation explicitly requires fresh readiness. Lifecycle operations can mark a readiness result
 * that they have already verified.
 */
final class MinecraftReadinessCache {
  private final MinecraftCommandChannel channel;
  private boolean serviceActive;
  private boolean ready;

  MinecraftReadinessCache(MinecraftCommandChannel channel) {
    this.channel = Objects.requireNonNull(channel);
  }

  synchronized boolean observeServiceState(boolean active) {
    boolean transitionedToActive = active && !serviceActive;
    serviceActive = active;
    if (!active) {
      ready = false;
      return false;
    }
    if (transitionedToActive) ready = probeChannel();
    return ready;
  }

  synchronized boolean refresh(boolean active) {
    serviceActive = active;
    if (!active) {
      ready = false;
      return false;
    }
    ready = probeChannel();
    return ready;
  }

  synchronized boolean probeNow() {
    ready = probeChannel();
    return ready;
  }

  synchronized void markStarted() {
    serviceActive = true;
    ready = true;
  }

  synchronized void markStopped() {
    serviceActive = false;
    ready = false;
  }

  private boolean probeChannel() {
    return channel.enabled() && channel.readinessProbe().success();
  }
}
