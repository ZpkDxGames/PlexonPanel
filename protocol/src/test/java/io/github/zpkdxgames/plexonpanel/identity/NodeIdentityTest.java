package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NodeIdentityTest {
  @TempDir Path directory;

  @Test void initializesOnePublicNodeIdentityAndPreservesIt() throws Exception {
    Path file = directory.resolve("node-id");
    var first = NodeIdentity.initialize(file);
    assertEquals(first, NodeIdentity.read(file));
    assertEquals(first, NodeIdentity.initialize(file));
    assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
  }

  @Test void invalidMissingAndSymlinkedIdentityNeverRegenerates() throws Exception {
    Path file = directory.resolve("node-id");
    assertThrows(IOException.class, () -> NodeIdentity.read(file));
    Files.writeString(file, "invalid-private-input");
    assertThrows(IOException.class, () -> NodeIdentity.initialize(file));
    assertEquals("invalid-private-input", Files.readString(file));
    Path link = Files.createSymbolicLink(directory.resolve("link"), file);
    assertThrows(IOException.class, () -> NodeIdentity.read(link));
    assertThrows(IOException.class, () -> NodeIdentity.initialize(link));
  }
}
