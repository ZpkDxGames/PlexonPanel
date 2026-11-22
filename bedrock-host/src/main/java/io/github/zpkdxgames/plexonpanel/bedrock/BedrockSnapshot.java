package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import java.time.Instant;

/** A read-only Docker observation. Null readings identify unavailable evidence. */
record BedrockSnapshot(
    Instant capturedAt,
    String containerState,
    String containerHealth,
    String containerRestartPolicy,
    String containerImage,
    Instant containerCreatedAt,
    Instant containerStartedAt,
    Long containerUptimeSeconds,
    Double containerCpuPercent,
    Long containerMemoryBytes,
    Long containerMemoryLimitBytes,
    Instant containerStatsCapturedAt,
    Boolean oomKilled,
    Long restartCount,
    String version,
    Instant versionCapturedAt) {

  static BedrockSnapshot absent(Instant now) {
    return withoutReadings(now, "absent");
  }

  static BedrockSnapshot unavailable(Instant now) {
    return withoutReadings(now, "unknown");
  }

  private static BedrockSnapshot withoutReadings(Instant now, String state) {
    return new BedrockSnapshot(now, state, null, null, null, null, null, null, null, null,
        null, null, null, null, null, null);
  }

  JsonObject toWire() {
    JsonObject wire = new JsonObject();
    wire.addProperty("capturedAt", capturedAt.toString());
    wire.addProperty("source", "DOCKER_ENGINE");
    wire.addProperty("cpuUnit", "PERCENT_OF_ONE_CORE");
    wire.addProperty("containerState", containerState);
    wire.addProperty("containerHealth", containerHealth);
    wire.addProperty("containerRestartPolicy", containerRestartPolicy);
    wire.addProperty("containerImage", containerImage);
    wire.addProperty("containerCreatedAt", iso(containerCreatedAt));
    wire.addProperty("containerStartedAt", iso(containerStartedAt));
    wire.addProperty("containerUptimeSeconds", containerUptimeSeconds);
    wire.addProperty("containerCpuPercent", containerCpuPercent);
    wire.addProperty("containerMemoryBytes", containerMemoryBytes);
    wire.addProperty("containerMemoryLimitBytes", containerMemoryLimitBytes);
    wire.addProperty("containerStatsCapturedAt", iso(containerStatsCapturedAt));
    wire.addProperty("oomKilled", oomKilled);
    wire.addProperty("restartCount", restartCount);
    wire.addProperty("version", version);
    wire.addProperty("versionCapturedAt", iso(versionCapturedAt));
    wire.add("gameplayReady", JsonNull.INSTANCE);
    wire.add("playersOnline", JsonNull.INSTANCE);
    return wire;
  }

  private static String iso(Instant time) {
    return time == null ? null : time.toString();
  }
}
