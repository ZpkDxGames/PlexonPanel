package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeInstanceRegistryTest {
  @TempDir Path root;
  @Test void allocationIsPublicAtomicIdempotentAndRejectsCrossPortAndIdentityCollisions() throws Exception {
    Path path = root.resolve("instances.json"); UUID node = UUID.randomUUID();
    var a = new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", 25565, 25575);
    var b = new NodeInstanceRegistry.Entry(UUID.randomUUID(), "bravo", 25566, 25576);
    NodeInstanceRegistry.register(path, node, a);
    String before = Files.readString(path);
    Object lockInode = Files.readAttributes(root.resolve("instances.json.lock"), java.nio.file.attribute.BasicFileAttributes.class).fileKey();
    NodeInstanceRegistry.register(path, node, a); assertEquals(before, Files.readString(path));
    NodeInstanceRegistry.register(path, node, b);
    assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
    assertEquals(lockInode, Files.readAttributes(root.resolve("instances.json.lock"), java.nio.file.attribute.BasicFileAttributes.class).fileKey());
    var registry = NodeInstanceRegistry.read(path); assertEquals(2, registry.instances().size());
    assertEquals(a, registry.require(a.identity(node, "Renamed server")));
    assertThrows(IOException.class, () -> registry.require(a.identity(UUID.randomUUID(), "Other node")));
    assertThrows(IOException.class, () -> registry.instance("missing"));
    for (var denied : List.of(new NodeInstanceRegistry.Entry(UUID.randomUUID(), "charlie", 25575, null),
        new NodeInstanceRegistry.Entry(UUID.randomUUID(), "delta", 25567, 25565),
        new NodeInstanceRegistry.Entry(a.serverId(), "echo", 25568, 25578),
        new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", 25569, 25579))) {
      String valid = Files.readString(path);
      assertThrows(IOException.class, () -> NodeInstanceRegistry.register(path, node, denied));
      assertEquals(valid, Files.readString(path));
    }
    assertThrows(IOException.class, () -> NodeInstanceRegistry.register(path, UUID.randomUUID(), b));
  }
  @Test void malformedRegistrySymlinksAndInvalidAllocationsFailWithoutReturningSuppliedValues() throws Exception {
    Path path = root.resolve("registry.json");
    for (String schema : List.of("5.5", "4294967301", "6", "\"5\"")) {
      Files.writeString(path, "{\"schemaVersion\":"+schema+",\"nodeId\":\""+UUID.randomUUID()+"\",\"instances\":[]}");
      assertEquals("NODE_REGISTRY_INVALID", assertThrows(IOException.class, () -> NodeInstanceRegistry.read(path)).getMessage());
    }
    Files.writeString(path, "private-invalid-sentinel");
    assertFalse(assertThrows(IOException.class, () -> NodeInstanceRegistry.read(path)).toString().contains("sentinel"));
    Path link = root.resolve("link.json"); Files.createSymbolicLink(link, path);
    assertThrows(IOException.class, () -> NodeInstanceRegistry.read(link));
    Files.delete(path); Files.createDirectory(root.resolve("registry.json.lock"));
    assertThrows(IOException.class, () -> NodeInstanceRegistry.register(path, UUID.randomUUID(), new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", 25565, null)));
    for (int port : List.of(0, -1, 65536)) assertThrows(IllegalArgumentException.class, () -> new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", port, null));
    assertThrows(IllegalArgumentException.class, () -> new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", 25565, 25565));
    assertThrows(IllegalArgumentException.class, () -> new NodeInstanceRegistry.Entry(UUID.randomUUID(), "../other", 25565, null));
  }
  @Test void competingAdministratorProcessesCannotAllocateTheSameNodePort() throws Exception {
    Path path = root.resolve("shared.json"); UUID node = UUID.randomUUID();
    var a = child(path, node, "alpha"); var b = child(path, node, "bravo");
    assertTrue(a.waitFor(10, TimeUnit.SECONDS)); assertTrue(b.waitFor(10, TimeUnit.SECONDS));
    assertEquals(List.of(0, 2), java.util.stream.Stream.of(a.exitValue(), b.exitValue()).sorted().toList());
    assertEquals(1, NodeInstanceRegistry.read(path).instances().size());
  }
  private Process child(Path path, UUID node, String key) throws Exception {
    String cp = java.util.stream.Stream.of(NodeInstanceRegistryTest.class, NodeInstanceRegistry.class, Gson.class)
        .map(c -> { try { return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
          catch (Exception failure) { throw new RuntimeException(failure); } }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", cp,
        RegistrationProcess.class.getName(), path.toString(), node.toString(), key).redirectErrorStream(true).start();
  }
  public static final class RegistrationProcess {
    public static void main(String[] args) throws Exception {
      try { NodeInstanceRegistry.register(Path.of(args[0]), UUID.fromString(args[1]), new NodeInstanceRegistry.Entry(UUID.randomUUID(), args[2], 25565, null)); }
      catch (IOException collision) { System.exit(2); }
    }
  }
}
