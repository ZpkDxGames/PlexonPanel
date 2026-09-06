package io.github.zpkdxgames.plexonpanel.files;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SafeFilesTest {
  @TempDir Path temporary;

  SafeFiles files(Path root) throws Exception {
    return new SafeFiles(new PathPolicy(Map.of("server", root), List.of(root.resolve("private"))));
  }

  @Test
  void absoluteEncodedTraversalAndProtectedPathsAreDenied() throws Exception {
    var p = files(temporary).policy();
    for (String s :
        List.of(
            "../outside.txt",
            "%2e%2e/outside.txt",
            "/etc/passwd",
            "a/../../x",
            "a//x",
            "a/./x",
            "plugins/PlexonPanel/access.json",
            "rclone.conf",
            "secret.json",
            "private/config.yml",
            "x.jar",
            "C:" + (char) 92 + "Windows")) {
      if (s.equals("x.jar")) {
        assertFalse(p.editable(s));
        continue;
      }
      assertThrows(SecurityException.class, () -> p.parts("server", s, false), s);
    }
  }

  @Test
  void symbolicLinksAndReplacedRootAreRejected() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server")),
        outside = Files.createDirectory(temporary.resolve("outside"));
    Files.writeString(outside.resolve("data.txt"), "outside");
    var files = files(root);
    Files.createSymbolicLink(root.resolve("escape"), outside);
    assertThrows(IOException.class, () -> files.read("server", "escape/data.txt"));
    Files.move(root, temporary.resolve("original"));
    Files.createSymbolicLink(root, outside);
    assertThrows(IOException.class, () -> files.read("server", "data.txt"));
    Files.delete(root);
    Files.createDirectory(root);
    Files.writeString(root.resolve("data.txt"), "replacement");
    assertThrows(IOException.class, () -> files.read("server", "data.txt"));
    assertEquals("outside", Files.readString(outside.resolve("data.txt")));
  }

  @Test
  void atomicWriteChecksHashAndPreservesPermissions() throws Exception {
    Path file = temporary.resolve("config.json");
    Files.writeString(file, "{\"x\":1}");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
    var f = files(temporary);
    String hash = (String) f.read("server", "config.json").get("sha256");
    var result = f.write("server", "config.json", "{\"x\":2}", hash, false);
    assertEquals(hash, result.get("oldHash"));
    assertEquals("{\"x\":2}", Files.readString(file));
    assertEquals(PosixFilePermissions.fromString("rw-r-----"), Files.getPosixFilePermissions(file));
    assertThrows(
        SafeFiles.ConflictException.class,
        () -> f.write("server", "config.json", "{}", hash, false));
    assertEquals("{\"x\":2}", Files.readString(file));
    try (var list = Files.list(temporary)) {
      assertFalse(list.anyMatch(p -> p.getFileName().toString().startsWith(".plexonpanel-write-")));
    }
  }

  @Test
  void createNeverOverwritesAndUploadCannotWriteExecutables() throws Exception {
    var f = files(temporary);
    f.write("server", "new.yml", "enabled: true", "", true);
    assertThrows(
        SafeFiles.ConflictException.class, () -> f.write("server", "new.yml", "changed", "", true));
    assertThrows(
        SecurityException.class, () -> f.write("server", "plugin.jar", "binary", "", true));
    assertThrows(SecurityException.class, () -> f.write("server", "live.db", "binary", "", true));
    assertEquals("enabled: true", Files.readString(temporary.resolve("new.yml")));
  }

  @Test
  void readOnlyRootBinaryMalformedAndOversizedTextAreDenied() throws Exception {
    Files.writeString(temporary.resolve("read.txt"), "hello");
    var f = new SafeFiles(new PathPolicy(Map.of("server", temporary), List.of()), Set.of());
    assertThrows(
        SecurityException.class,
        () -> f.delete("server", "read.txt", SafeFiles.hash("hello".getBytes())));
    Files.write(temporary.resolve("bad.txt"), new byte[] {(byte) 0xff});
    assertThrows(IOException.class, () -> f.read("server", "bad.txt"));
    Files.writeString(temporary.resolve("large.txt"), "a".repeat(SafeFiles.MAX_TEXT_BYTES + 1));
    assertThrows(IOException.class, () -> f.read("server", "large.txt"));
    assertThrows(
        IllegalArgumentException.class,
        () -> files(temporary).write("server", "large.txt", "á".repeat(24576), "", true));
  }

  @Test
  void sqlIsReadableButCannotBeChanged() throws Exception {
    Files.writeString(temporary.resolve("schema.sql"), "SELECT 1;");
    var f = files(temporary);
    assertEquals(false, f.read("server", "schema.sql").get("editable"));
    assertThrows(SecurityException.class, () -> f.delete("server", "schema.sql", "any"));
  }
}
