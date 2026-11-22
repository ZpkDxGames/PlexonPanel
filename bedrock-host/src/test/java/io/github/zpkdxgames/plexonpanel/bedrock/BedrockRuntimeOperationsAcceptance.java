package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockConsole;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Container;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Phase;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockContainerControl.Readiness;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockLifecycle;
import io.github.zpkdxgames.plexonpanel.bedrock.operations.BedrockOperationLock;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.bool;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.number;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.object;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.parseObject;
import static io.github.zpkdxgames.plexonpanel.bedrock.DockerReadClient.text;

/** Standalone acceptance for the real disposable image. This class is absent from the Host JAR. */
public final class BedrockRuntimeOperationsAcceptance implements BedrockContainerControl {
  private static final String LABEL = "com.plexonpanel.staging.run";
  private static final String LEVEL_NAME = "PhaseTwoDisposable";
  private static final Duration GRACE = Duration.ofSeconds(45);
  private static final Pattern LIST_RESPONSE = Pattern.compile("(?i).*\\b(?:there are )?0\\s*/\\s*4 players online\\b.*");
  private static final Pattern STOP_RESPONSE = Pattern.compile("(?i).*\\b(?:server stop requested|stopping server|quit correctly)\\b.*");
  private static final Pattern IMAGE = Pattern.compile("(?:docker\\.io/)?itzg/minecraft-bedrock-server@sha256:[0-9a-f]{64}");

  private final String id;
  private final String run;
  private final String imageId;
  private final String initialAddress;
  private final int port;
  private final Path data;
  private final DockerUnixHttp http;
  private final DockerReadClient reads;
  private final BedrockDockerOperations operations;

  private BedrockRuntimeOperationsAcceptance(String[] args) throws IOException {
    require(args.length == 7, "Expected Docker socket, /data source, image ID, container ID, staging run UUID, and probe IP/port");
    Path socket = Path.of(args[0]);
    data = Path.of(args[1]);
    imageId = args[2];
    id = args[3];
    run = args[4];
    initialAddress = args[5];
    port = Integer.parseInt(args[6]);
    require(id.matches("[0-9a-f]{64}") && UUID.fromString(run).toString().equals(run)
        && imageId.matches("sha256:[0-9a-f]{64}") && privateAddress(initialAddress)
        && port > 0 && port <= 65535 && Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)
        && data.toRealPath().equals(data), "Invalid disposable acceptance inputs");
    Clock clock = Clock.systemUTC();
    http = new DockerUnixHttp(socket, Duration.ofSeconds(3));
    reads = new DockerReadClient(socket, clock);
    operations = new BedrockDockerOperations(socket, data, imageId, clock);
  }

  public static void main(String[] args) throws Exception {
    new BedrockRuntimeOperationsAcceptance(args).verify();
  }

  private void verify() throws Exception {
    Container original = inspect();
    require(original.phase() == Phase.RUNNING && original.readiness() == Readiness.UNKNOWN,
        "Expected a running container with independently unverified Bedrock readiness");
    require(initialAddress.equals(address()), "Initial probe address does not belong to the pinned staging container");
    JsonObject firstJoin = waitForJoin(Duration.ofSeconds(60));
    Object world = verifyWorld();
    System.out.println("PASS: NetherNet /v1/join returned the isolated server, disposable world, and Bedrock version "
        + text(firstJoin, "version"));

    BedrockOperationLock lock = new BedrockOperationLock();
    BedrockConsole console = new BedrockConsole(true, false, true, this, lock, operations);
    require(console.execute(BedrockConsole.Action.OP, "Disposable") == BedrockConsole.Outcome.DISABLED,
        "Player mutation gate was bypassed");
    require(console.execute(BedrockConsole.Action.SAY, "one\nstop") == BedrockConsole.Outcome.INVALID_ARGUMENT,
        "Multiline console input was admitted");
    var nonExclusive = new BedrockConsole(true, false, false, this, lock, operations);
    require(nonExclusive.execute(BedrockConsole.Action.LIST, null) == BedrockConsole.Outcome.ATTACH_NOT_EXCLUSIVE,
        "Console accepted another owner");
    requireDenied(() -> operations.writeLine(original, "stop\n".getBytes(StandardCharsets.UTF_8)),
        "Privileged adapter accepted an unapproved command");
    Instant submitted = Instant.now();
    require(console.execute(BedrockConsole.Action.LIST, null) == BedrockConsole.Outcome.DISPATCHED,
        "Fixed Bedrock list command was not dispatched");
    waitForLog(submitted, LIST_RESPONSE, "BDS did not return its player count after list");
    require(inspect().equals(original), "Console attach changed the pinned process");
    System.out.println("PASS: staged attach dispatched list and Box64 BDS returned the fresh 0/4 response");
    System.out.println("PASS: player changes, competing console ownership, multiline input, and raw stop were denied");

    BedrockLifecycle lifecycle = new BedrockLifecycle(true, this, lock, GRACE);
    UUID stopId = UUID.randomUUID();
    Instant stopRequest = Instant.now();
    Container recoverable = original;
    try {
      var result = lifecycle.execute(stopId, BedrockLifecycle.Action.STOP);
      require(result == BedrockLifecycle.Outcome.STOPPED, "Staged stop result was " + result);
      long cooldownEnd = System.nanoTime() + Duration.ofSeconds(31).toNanos();
      Container stopped = inspect();
      require(stopped.phase() == Phase.EXITED && stopped.exitCode() == 0 && !stopped.oomKilled()
          && original.startedAt().equals(stopped.startedAt()), "Docker did not record a clean exit for the expected launch");
      waitForLog(stopRequest, STOP_RESPONSE, "No BDS shutdown receipt for the verified clean exit");
      require(!acceptsJoin(initialAddress), "NetherNet still responded after the verified stop");
      require(world.equals(verifyWorld()), "Clean shutdown changed the disposable world identity");
      require(lifecycle.execute(stopId, BedrockLifecycle.Action.STOP) == BedrockLifecycle.Outcome.STOPPED
          && lifecycle.execute(stopId, BedrockLifecycle.Action.START) == BedrockLifecycle.Outcome.REQUEST_MISMATCH
          && lifecycle.execute(UUID.randomUUID(), BedrockLifecycle.Action.START) == BedrockLifecycle.Outcome.COOLDOWN,
          "Lifecycle receipt or cooldown failed");
      System.out.println("PASS: staged SIGTERM produced BDS shutdown output, exit 0, no OOM, unavailable endpoint, and intact LevelDB");
      System.out.println("PASS: replayed lifecycle action reused its receipt and conflict/cooldown were denied");

      waitForCooldown(cooldownEnd);
      checkStart(lifecycle.execute(UUID.randomUUID(), BedrockLifecycle.Action.START), original, firstJoin, world);
      System.out.println("PASS: staged start restored readiness on the same container and disposable world with a new launch");
      requireDenied(() -> operations.stop(original, GRACE), "Stale launch could be stopped");
      Container beforeRestart = inspect();
      recoverable = beforeRestart;
      long restartCooldownEnd = System.nanoTime() + Duration.ofSeconds(31).toNanos();
      waitForCooldown(restartCooldownEnd);
      checkStart(lifecycle.execute(UUID.randomUUID(), BedrockLifecycle.Action.RESTART), beforeRestart, firstJoin, world);
      System.out.println("PASS: staged restart verified a new launch and independently restored NetherNet readiness");
      System.out.println("PASS: the privileged adapter refused to dispatch a stop to the old launch");
    } catch (Exception failed) {
      recoverKnownStoppedLaunch(recoverable, failed);
      throw failed;
    }
  }

  @Override
  public Container inspect() throws IOException {
    inspectPolicy();
    Container container = operations.inspect();
    require(id.equals(container.id()) && data.equals(container.dataMount()), "Disposable container identity changed");
    return container;
  }

  @Override
  public void start(Container expected) throws IOException {
    require(inspect().equals(expected), "Disposable launch changed before start");
    operations.start(expected);
  }

  @Override
  public void stop(Container expected, Duration grace) throws IOException {
    require(inspect().equals(expected), "Disposable launch changed before stop");
    operations.stop(expected, grace);
  }

  private JsonObject inspectPolicy() throws IOException {
    var response = http.get("/v1.44/containers/" + id + "/json", 131_072);
    require(response.status() == 200, "Disposable container is unavailable");
    JsonObject inspection = parseObject(response.body());
    JsonObject config = object(inspection, "Config");
    JsonObject host = object(inspection, "HostConfig");
    JsonObject ports = object(host, "PortBindings");
    String reference = text(config, "Image");
    var mounts = inspection.get("Mounts");
    require(id.equals(text(inspection, "Id")) && ("/" + DockerReadClient.CONTAINER_NAME).equals(text(inspection, "Name"))
        && imageId.equals(text(inspection, "Image")) && run.equals(text(object(config, "Labels"), LABEL))
        && reference != null && IMAGE.matcher(reference).matches()
        && Boolean.TRUE.equals(bool(config, "Tty")) && Boolean.TRUE.equals(bool(config, "OpenStdin"))
        && Boolean.FALSE.equals(bool(config, "StdinOnce")) && "SIGTERM".equals(text(config, "StopSignal"))
        && (ports == null || ports.isEmpty()) && Boolean.FALSE.equals(bool(host, "Privileged"))
        && "json-file".equals(text(object(host, "LogConfig"), "Type"))
        && mounts != null && mounts.isJsonArray() && mounts.getAsJsonArray().size() == 1,
        "Docker inspection did not match the pinned and isolated acceptance policy");
    return inspection;
  }

  private String address() throws IOException {
    JsonObject networks = object(object(inspectPolicy(), "NetworkSettings"), "Networks");
    require(networks != null && networks.size() == 1, "Expected one disposable Docker bridge");
    var network = networks.entrySet().iterator().next();
    require(!network.getKey().equals("bridge") && network.getValue().isJsonObject(), "Expected a dedicated Docker bridge");
    String address = text(network.getValue().getAsJsonObject(), "IPAddress");
    require(address != null && privateAddress(address), "NetherNet probe is outside the private Docker network");
    return address;
  }

  private JsonObject waitForJoin(Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    do {
      try {
        JsonObject info = join(address());
        String version = text(info, "version");
        if (("plexonpanel-phase2-" + run).equals(text(info, "name")) && LEVEL_NAME.equals(text(info, "level"))
            && Objects.equals(number(info, "players", 4), 0L) && Objects.equals(number(info, "maxPlayers", 4), 4L)
            && version != null && version.matches("1\\.26\\.66(?:\\.1)?")) {
          return info;
        }
      } catch (IOException starting) {
        // The private bridge address or game listener may be unavailable during launch.
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    throw new IOException("No valid NetherNet readiness response for the expected server, world and release");
  }

  private boolean acceptsJoin(String address) {
    try {
      join(address);
      return true;
    } catch (IOException unreachable) {
      return false;
    }
  }

  private JsonObject join(String address) throws IOException {
    var url = URI.create("http://" + address + ":" + port + "/v1/join").toURL();
    var connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
    try {
      connection.setConnectTimeout(1_500);
      connection.setReadTimeout(2_000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("Connection", "close");
      require(connection.getResponseCode() == 200, "NetherNet readiness endpoint did not respond with success");
      try (var body = connection.getInputStream()) {
        byte[] bytes = body.readNBytes(8_193);
        require(bytes.length <= 8_192, "Oversized NetherNet readiness response");
        return parseObject(bytes);
      }
    } finally {
      connection.disconnect();
    }
  }

  private void waitForLog(Instant since, Pattern expected, String failure) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    do {
      if (reads.logs(since, 48).stream().anyMatch(line -> !line.truncated() && expected.matcher(line.content()).matches())) {
        return;
      }
      Thread.sleep(250);
    } while (System.nanoTime() < deadline);
    throw new IOException(failure);
  }

  private Object verifyWorld() throws IOException {
    Path world = data.resolve("worlds").resolve(LEVEL_NAME);
    require(world.toRealPath().equals(world) && Files.isRegularFile(world.resolve("level.dat"), LinkOption.NOFOLLOW_LINKS),
        "Expected the isolated Bedrock world and its level metadata");
    Path db = world.resolve("db");
    Path current = db.resolve("CURRENT");
    require(db.toRealPath().equals(db) && Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS) && Files.size(current) <= 64,
        "Expected Bedrock LevelDB CURRENT");
    String manifest = Files.readString(current, StandardCharsets.US_ASCII).strip();
    require(manifest.matches("MANIFEST-[0-9]+") && Files.isRegularFile(db.resolve(manifest), LinkOption.NOFOLLOW_LINKS),
        "LevelDB CURRENT did not identify a manifest in the disposable world");
    Object identity = Files.readAttributes(world, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
    require(identity != null, "Unable to verify the disposable world identity");
    return identity;
  }

  private void checkStart(BedrockLifecycle.Outcome result, Container previous, JsonObject firstJoin, Object world)
      throws Exception {
    require(result == BedrockLifecycle.Outcome.RUNNING_UNVERIFIED, "Staged lifecycle result was " + result);
    Container current = inspect();
    require(current.phase() == Phase.RUNNING && current.startedAt().isAfter(previous.startedAt()),
        "Expected a new launch of the verified container");
    JsonObject ready = waitForJoin(Duration.ofSeconds(60));
    require(Objects.equals(text(firstJoin, "version"), text(ready, "version")) && world.equals(verifyWorld()),
        "Ready restart did not recover the original disposable world and Bedrock release");
  }

  private void recoverKnownStoppedLaunch(Container original, Exception failed) {
    try {
      Container current = inspect();
      if (current.phase() == Phase.EXITED && current.exitCode() == 0 && !current.oomKilled()
          && original.startedAt().equals(current.startedAt())) {
        start(current);
        waitForJoin(Duration.ofSeconds(60));
        System.out.println("Recovery: restored only the verified intentionally stopped disposable launch");
      }
    } catch (Exception recovery) {
      failed.addSuppressed(recovery);
    }
  }

  private static void waitForCooldown(long cooldownEnd) throws InterruptedException {
    long remaining = cooldownEnd - System.nanoTime();
    if (remaining > 0) Thread.sleep(Duration.ofNanos(remaining));
  }

  private static boolean privateAddress(String address) {
    if (!address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) return false;
    String[] parts = address.split("\\.");
    for (String part : parts) {
      if (Integer.parseInt(part) > 255 || part.length() > 1 && part.charAt(0) == '0') return false;
    }
    int first = Integer.parseInt(parts[0]);
    int second = Integer.parseInt(parts[1]);
    return first == 10 || first == 172 && second >= 16 && second <= 31 || first == 192 && second == 168;
  }

  private static void require(boolean condition, String failure) throws IOException {
    if (!condition) throw new IOException(failure);
  }

  private static void requireDenied(CheckedOperation operation, String failure) throws IOException {
    try {
      operation.run();
    } catch (IOException denied) {
      return;
    }
    throw new IOException(failure);
  }

  @FunctionalInterface
  private interface CheckedOperation {
    void run() throws IOException;
  }
}
