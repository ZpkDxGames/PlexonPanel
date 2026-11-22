package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

public final class BedrockMain {
  private static final int LOG_LIMIT = 12;
  private static final Duration LOG_WINDOW = Duration.ofMinutes(10);

  private BedrockMain() {}

  public static void main(String[] args) {
    try {
      if (args.length == 2 && "broker".equals(args[0])) {
        broker(Path.of(args[1]));
      } else if (args.length == 2 && "host".equals(args[0])) {
        host(Path.of(args[1]), BedrockConfig.BROKER_SOCKET, false);
      } else if (args.length == 3 && "host-fixture".equals(args[0])) {
        host(Path.of(args[1]), Path.of(args[2]), true);
      } else if (args.length == 5 && "identity-init".equals(args[0])) {
        var nodeId = BedrockConfig.uuid(args[1]);
        var identity =
            BedrockConfig.initialize(nodeId, URI.create(args[2]), args[3], Path.of(args[4]));
        JsonObject enrollment = new JsonObject();
        enrollment.addProperty("serverId", identity.serverId().toString());
        enrollment.addProperty("nodeId", nodeId.toString());
        enrollment.addProperty("instanceKey", BedrockConfig.INSTANCE);
        enrollment.addProperty("publicKey", identity.publicKeyBase64());
        enrollment.addProperty("publicKeyFingerprint", identity.fingerprint());
        System.out.println(UnixFrames.JSON.toJson(enrollment));
      } else {
        System.err.println("Usage: broker <config> | host <config> | "
            + "host-fixture <config> <socket> | identity-init <nodeId> <relayUrl> <relayPublicKey> <newConfig>");
        System.exit(2);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception failure) {
      // Config parse or connection errors may include values that must not enter a log.
      System.err.println("PlexonPanel Bedrock startup failed (" + failure.getClass().getSimpleName() + ")");
      System.exit(1);
    }
  }

  private static void broker(Path configFile) throws IOException {
    if (new UnixSystem().getUid() != 0) throw new IOException("Broker requires the configured service principal");
    var binding = BedrockConfig.loadBroker(configFile);
    Clock clock = Clock.systemUTC();
    DockerReadClient docker = new DockerReadClient(BedrockConfig.DOCKER_SOCKET, clock);
    try (BedrockBroker broker = new BedrockBroker(
        BedrockConfig.BROKER_SOCKET, BedrockConfig.HOST_USER, binding, dockerReadings(docker, clock))) {
      Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("bedrock-broker-shutdown").unstarted(() -> {
        try {
          broker.close();
        } catch (IOException failure) {
          System.err.println("PlexonPanel Bedrock broker socket cleanup failed");
        }
      }));
      broker.serve();
    }
  }

  static BedrockBroker.Readings dockerReadings(DockerReadClient docker, Clock clock) {
    return operation -> switch (operation) {
      case STATUS -> docker.snapshot().toWire();
      case LOGS -> {
        var lines = new JsonArray();
        for (BedrockLogLine line : docker.logs(clock.instant().minus(LOG_WINDOW), LOG_LIMIT)) {
          JsonObject value = new JsonObject();
          value.addProperty("capturedAt", line.capturedAt().toString());
          value.addProperty("stream", line.stream());
          value.addProperty("content", line.content());
          value.addProperty("truncated", line.truncated());
          lines.add(value);
        }
        yield logs(clock, true, lines);
      }
    };
  }

  private static void host(Path configFile, Path socket, boolean localFixture)
      throws IOException, InterruptedException {
    UnixSystem user = new UnixSystem();
    if (user.getUid() == 0 || !localFixture && !BedrockConfig.HOST_USER.equals(user.getUsername()))
      throw new IOException("Bedrock Host requires its unprivileged service principal");
    var config = BedrockConfig.loadHost(configFile, localFixture);
    String brokerUser = localFixture ? user.getUsername() : "root";
    Clock clock = Clock.systemUTC();
    try (var connection = new BedrockConnection(
        config,
        operation -> BedrockBroker.read(socket, brokerUser, config.binding(), operation),
        operation -> switch (operation) {
          case STATUS -> BedrockSnapshot.unavailable(clock.instant()).toWire();
          case LOGS -> logs(clock, false, new JsonArray());
        })) {
      Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().name("bedrock-host-shutdown").unstarted(connection::close));
      connection.start();
      new CountDownLatch(1).await();
    }
  }

  private static JsonObject logs(Clock clock, boolean available, JsonArray lines) {
    JsonObject value = new JsonObject();
    value.addProperty("capturedAt", clock.instant().toString());
    value.addProperty("source", "DOCKER_ENGINE");
    value.addProperty("recentWindow", true);
    value.addProperty("available", available);
    value.add("lines", lines);
    return value;
  }
}
