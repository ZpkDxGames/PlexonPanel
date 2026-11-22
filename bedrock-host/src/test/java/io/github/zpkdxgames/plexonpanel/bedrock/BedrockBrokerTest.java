package io.github.zpkdxgames.plexonpanel.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.sun.security.auth.module.UnixSystem;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class BedrockBrokerTest {
  @TempDir Path directory;
  private final BedrockConfig.Binding binding =
      new BedrockConfig.Binding(UUID.randomUUID(), UUID.randomUUID());
  private final String local = new UnixSystem().getUsername();

  @Test
  void authenticatesBothPeersAndRateLimitsEachOperation() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    Path socket = directory.resolve("broker.sock");
    try (var broker = new BedrockBroker(socket, local, binding, operation -> {
      reads.incrementAndGet();
      return new JsonObject();
    })) {
      Thread serving = serve(broker);
      assertThrows(IOException.class,
          () -> BedrockBroker.read(socket, "unrelated-account", binding, BedrockBroker.Operation.STATUS));
      assertEquals(0, reads.get());
      assertNotNull(BedrockBroker.read(socket, local, binding, BedrockBroker.Operation.STATUS));
      assertThrows(IOException.class,
          () -> BedrockBroker.read(socket, local, binding, BedrockBroker.Operation.STATUS));
      assertNotNull(BedrockBroker.read(socket, local, binding, BedrockBroker.Operation.LOGS));
      assertEquals(2, reads.get());
      broker.close();
      serving.join(Duration.ofSeconds(2));
      assertFalse(serving.isAlive());
    }
  }

  @Test
  void refusesForeignUnixPeerEvenWhenRequestUsesExactBinding() throws Exception {
    Path socket = directory.resolve("broker.sock");
    AtomicInteger reads = new AtomicInteger();
    try (var broker = new BedrockBroker(socket, "unrelated-account", binding, operation -> {
      reads.incrementAndGet();
      return new JsonObject();
    })) {
      serve(broker);
      assertThrows(IOException.class,
          () -> BedrockBroker.read(socket, local, binding, BedrockBroker.Operation.STATUS));
      assertEquals(0, reads.get());
    }
  }

  @Test
  void malformedPacketsAndIdentitySubstitutionNeverReachDocker() throws Exception {
    Path socket = directory.resolve("broker.sock");
    AtomicInteger reads = new AtomicInteger();
    try (var broker = new BedrockBroker(socket, local, binding, operation -> {
      reads.incrementAndGet();
      return new JsonObject();
    })) {
      serve(broker);
      try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
        channel.connect(UnixDomainSocketAddress.of(socket));
        channel.write(ByteBuffer.allocate(4).putInt(1025).flip());
      }
      var other = new BedrockConfig.Binding(UUID.randomUUID(), binding.nodeId());
      assertThrows(IOException.class,
          () -> BedrockBroker.read(socket, local, other, BedrockBroker.Operation.STATUS));
      JsonObject request = new JsonObject();
      request.addProperty("version", 1);
      request.addProperty("operation", "STATUS");
      request.addProperty("serverId", binding.serverId().toString());
      request.addProperty("nodeId", binding.nodeId().toString());
      request.addProperty("instanceKey", "tonimbedrock");
      request.addProperty("container", "../../another-container");
      assertThrows(IOException.class, () -> BedrockBroker.validate(request, binding));
      request.remove("container");
      request.addProperty("instanceKey", "tonimmain");
      assertThrows(IOException.class, () -> BedrockBroker.validate(request, binding));
      request.addProperty("instanceKey", "tonimbedrock");
      request.addProperty("operation", "START");
      assertThrows(IOException.class, () -> BedrockBroker.validate(request, binding));
      assertThrows(IOException.class, () -> UnixFrames.readObject("{\"version\":1,\"version\":1}"));
      assertThrows(IOException.class, () -> UnixFrames.readObject("{\"version\":1} {\"version\":1}"));
      assertEquals(0, reads.get());
      assertNotNull(BedrockBroker.read(socket, local, binding, BedrockBroker.Operation.STATUS));
      assertEquals(1, reads.get());
    }
  }

  private static Thread serve(BedrockBroker broker) {
    return Thread.ofPlatform().daemon(true).start(() -> {
      try {
        broker.serve();
      } catch (IOException error) {
        throw new AssertionError(error);
      }
    });
  }
}
