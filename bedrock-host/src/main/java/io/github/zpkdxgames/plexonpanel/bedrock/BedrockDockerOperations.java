package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockConsole;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.bool;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.number;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.object;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.parseObject;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.text;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.timestamp;

/** Privileged staging adapter. No production broker route constructs or calls this adapter. */
final class BedrockDockerOperations implements BedrockContainerControl, BedrockConsole.Attach {
  private static final String INSPECT = "/v1.44/containers/" + DockerReadClient.CONTAINER_NAME + "/json";
  private static final int INSPECT_LIMIT = 131_072;
  private static final Duration INSPECT_TIMEOUT = Duration.ofSeconds(2);

  private final DockerUnixHttp http;
  private final Path dataRoot;
  private final String imageId;
  private final Clock clock;

  BedrockDockerOperations(Path dockerSocket, Path dataRoot, String imageId, Clock clock) {
    if (!dockerSocket.isAbsolute() || !dockerSocket.normalize().equals(dockerSocket)
        || !dataRoot.isAbsolute() || !dataRoot.normalize().equals(dataRoot)
        || imageId == null || !imageId.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid locally pinned Bedrock operation policy");
    }
    this.http = new DockerUnixHttp(dockerSocket, INSPECT_TIMEOUT);
    this.dataRoot = dataRoot;
    this.imageId = imageId;
    this.clock = Objects.requireNonNull(clock);
  }

  @Override
  public Container inspect() throws IOException {
    return inspectPinned().container();
  }

  @Override
  public void start(Container expected) throws IOException {
    if (expected.phase() != Phase.CREATED && expected.phase() != Phase.EXITED) {
      throw new IOException("Bedrock start requires a stopped container");
    }
    requireExpected(expected);
    http.startPinned(expected.id());
  }

  @Override
  public void stop(Container expected, Duration grace) throws IOException {
    if (expected.phase() != Phase.RUNNING) {
      throw new IOException("Bedrock stop requires a verified running launch");
    }
    requireExpected(expected);
    http.stopPinned(expected.id(), grace);
  }

  @Override
  public void writeLine(Container expected, byte[] line) throws IOException {
    if (!BedrockConsole.isAllowedWireLine(line) || expected.phase() != Phase.RUNNING) {
      throw new IOException("Invalid staged Bedrock console request");
    }
    requireAttach(expected);
    http.writePinnedStdin(expected.id(), line, () -> requireAttach(expected));
  }

  private void requireAttach(Container expected) throws IOException {
    if (!requireExpected(expected).attachCompatible()) {
      throw new IOException("Bedrock container does not have persistent TTY stdin");
    }
  }

  private Inspection requireExpected(Container expected) throws IOException {
    Inspection current = inspectPinned();
    if (!expected.equals(current.container())) {
      throw new IOException("Pinned Bedrock launch changed before dispatch");
    }
    return current;
  }

  private Inspection inspectPinned() throws IOException {
    var response = http.get(INSPECT, INSPECT_LIMIT);
    if (response.status() != 200) {
      throw new IOException("Pinned Bedrock container is unavailable");
    }
    JsonObject value = parseObject(response.body());
    String id = text(value, "Id");
    JsonObject state = object(value, "State");
    JsonObject config = object(value, "Config");
    String stopSignal = text(config, "StopSignal");
    String restartPolicy = text(object(object(value, "HostConfig"), "RestartPolicy"), "Name");
    if (!("/" + DockerReadClient.CONTAINER_NAME).equals(text(value, "Name")) || !imageId.equals(text(value, "Image"))
        || id == null || !id.matches("[0-9a-f]{64}") || !"unless-stopped".equals(restartPolicy)
        || config == null || config.has("StopSignal") && !config.get("StopSignal").isJsonNull() && stopSignal == null
        || stopSignal != null && !stopSignal.isEmpty() && !stopSignal.equals("SIGTERM")
        || !matchesDataMount(value.get("Mounts"))) {
      throw new IOException("Pinned Bedrock container policy is inconsistent");
    }

    Instant now = clock.instant();
    Instant created = timestamp(value, "Created", now);
    Instant started = timestamp(state, "StartedAt", now);
    Boolean oom = bool(state, "OOMKilled");
    Long exit = number(state, "ExitCode", 255);
    Phase phase = phase(state, started);
    if (phase == Phase.UNKNOWN || created == null || oom == null || exit == null
        || started != null && started.isBefore(created)) {
      throw new IOException("Pinned Bedrock container state is ambiguous");
    }
    Container container = new Container(id, started, phase, exit.intValue(), oom, Readiness.UNKNOWN, dataRoot);
    boolean attachCompatible = Boolean.TRUE.equals(bool(config, "Tty"))
        && Boolean.TRUE.equals(bool(config, "OpenStdin")) && Boolean.FALSE.equals(bool(config, "StdinOnce"));
    return new Inspection(container, attachCompatible);
  }

  private boolean matchesDataMount(JsonElement mounts) {
    if (mounts == null || !mounts.isJsonArray()) {
      return false;
    }
    boolean found = false;
    for (JsonElement element : mounts.getAsJsonArray()) {
      if (!element.isJsonObject()) {
        return false;
      }
      JsonObject mount = element.getAsJsonObject();
      String destination = text(mount, "Destination");
      if (destination == null || destination.startsWith("/data/")) {
        return false;
      }
      if (destination.equals("/data")) {
        if (found || !"bind".equals(text(mount, "Type")) || !dataRoot.toString().equals(text(mount, "Source"))
            || !Boolean.TRUE.equals(bool(mount, "RW"))) {
          return false;
        }
        found = true;
      }
    }
    return found;
  }

  private static Phase phase(JsonObject state, Instant started) {
    String status = text(state, "Status");
    Boolean running = bool(state, "Running");
    Boolean paused = bool(state, "Paused");
    Boolean restarting = bool(state, "Restarting");
    Boolean dead = bool(state, "Dead");
    if (status == null || running == null || paused == null || restarting == null || !Boolean.FALSE.equals(dead)) {
      return Phase.UNKNOWN;
    }
    return switch (status) {
      case "running" -> running && !paused && !restarting && started != null ? Phase.RUNNING : Phase.UNKNOWN;
      case "exited" -> !running && !paused && !restarting && started != null ? Phase.EXITED : Phase.UNKNOWN;
      case "created" -> !running && !paused && !restarting && started == null
          && "0001-01-01T00:00:00Z".equals(text(state, "StartedAt")) ? Phase.CREATED : Phase.UNKNOWN;
      case "paused" -> running && paused && !restarting && started != null ? Phase.PAUSED : Phase.UNKNOWN;
      case "restarting" -> restarting && !paused ? Phase.RESTARTING : Phase.UNKNOWN;
      default -> Phase.UNKNOWN;
    };
  }

  private record Inspection(Container container, boolean attachCompatible) {}
}
