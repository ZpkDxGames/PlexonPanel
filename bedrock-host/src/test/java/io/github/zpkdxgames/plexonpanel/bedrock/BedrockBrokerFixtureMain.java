package io.github.zpkdxgames.plexonpanel.bedrock;

import com.sun.security.auth.module.UnixSystem;
import java.nio.file.Path;
import java.time.Clock;

/** Test-classpath launcher for the real broker with an isolated simulated Docker Unix socket. */
public final class BedrockBrokerFixtureMain {
  private BedrockBrokerFixtureMain() {}

  public static void main(String[] args) throws Exception {
    var binding = new BedrockConfig.Binding(BedrockConfig.uuid(args[0]), BedrockConfig.uuid(args[1]));
    var clock = Clock.systemUTC();
    var docker = new DockerReadClient(Path.of(args[2]), clock);
    try (var broker = new BedrockBroker(Path.of(args[3]), new UnixSystem().getUsername(), binding,
        BedrockMain.dockerReadings(docker, clock))) {
      broker.serve();
    }
  }
}
