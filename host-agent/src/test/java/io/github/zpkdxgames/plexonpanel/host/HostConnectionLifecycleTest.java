package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import io.github.zpkdxgames.plexonpanel.identity.IdentityStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostConnectionLifecycleTest {
  @TempDir Path root;
  @Test void anUnstartedCompanionReleasesAllAllocatedResourcesAndCannotRestartAfterClose() throws Exception {
    var config = new HostConfig(UUID.randomUUID().toString(), "Test", "wss://relay.example.invalid/v1/agent", "unused",
        root.toString(), root.resolve("state").toString(), root.resolve("access.json").toString(),
        "minecraft@test.service", Map.of(), null, HostConfig.ConsoleConfig.defaults());
    var connection = new HostConnection(config, new IdentityStore(root.resolve("identity")).loadOrCreate());
    try {
      connection.close();
      assertTrue(connection.awaitClosed(Duration.ofSeconds(2)));
      assertThrows(IllegalStateException.class, connection::start);
      connection.close();
      assertTrue(connection.awaitClosed(Duration.ZERO));
    } finally { connection.close(); }
  }
}
