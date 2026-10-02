package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FleetBindingStoreTest {
  @TempDir Path directory;

  @Test void restartAndRenamePreserveBindingButConcurrentUseIsDenied() throws Exception {
    Path root = Files.createDirectory(directory.resolve("server"));
    Path data = Files.createDirectory(directory.resolve("data"));
    FleetIdentity identity = new FleetIdentity(UUID.randomUUID(), UUID.randomUUID(), "instance-a", "A");
    var store = new FleetBindingStore(data, root);
    try (var lease = store.claim(identity, "public-test-fingerprint")) {
      assertThrows(IOException.class, () -> new FleetBindingStore(data, root).claim(identity, "public-test-fingerprint"));
    }
    String original = Files.readString(data.resolve("identity/fleet-binding.json"));
    try (var lease = store.claim(identity.renamed("Renamed"), "public-test-fingerprint")) {}
    assertEquals(original, Files.readString(data.resolve("identity/fleet-binding.json")));
    assertTrue(Files.exists(data.resolve("identity/fleet.lock")));
  }

  @Test void copiedStateAndChangedNodeUnitOrDeviceCannotSilentlyRebind() throws Exception {
    Path root = Files.createDirectory(directory.resolve("server"));
    Path data = Files.createDirectory(directory.resolve("data"));
    FleetIdentity id = new FleetIdentity(UUID.randomUUID(), UUID.randomUUID(), "instance-a", "A");
    var store = new FleetBindingStore(data, root);
    try (var lease = store.claim(id, "fingerprint-a")) {}
    String original = Files.readString(data.resolve("identity/fleet-binding.json"));
    Path cloneData = Files.createDirectories(directory.resolve("clone/identity")).getParent();
    Files.copy(data.resolve("identity/fleet-binding.json"), cloneData.resolve("identity/fleet-binding.json"));
    Path cloneRoot = Files.createDirectory(directory.resolve("cloned-server"));
    assertThrows(IOException.class, () -> new FleetBindingStore(cloneData, cloneRoot).claim(id, "fingerprint-a"));
    assertThrows(IOException.class, () -> store.claim(new FleetIdentity(id.serverId(), UUID.randomUUID(), "instance-a", "A"), "fingerprint-a"));
    assertThrows(IOException.class, () -> store.claim(new FleetIdentity(id.serverId(), id.nodeId(), "instance-b", "A"), "fingerprint-a"));
    assertThrows(IOException.class, () -> store.claim(id, "fingerprint-b"));
    assertEquals(original, Files.readString(data.resolve("identity/fleet-binding.json")));
  }

  @Test void malformedBindingAndSymlinkAreDeniedWithoutEchoingFileContents() throws Exception {
    Path root = Files.createDirectory(directory.resolve("server"));
    Path data = Files.createDirectories(directory.resolve("data/identity")).getParent();
    FleetIdentity id = new FleetIdentity(UUID.randomUUID(), UUID.randomUUID(), "instance-a", "A");
    Path binding = data.resolve("identity/fleet-binding.json");
    Files.writeString(binding, "private-invalid-input");
    var store = new FleetBindingStore(data, root);
    IOException failure = assertThrows(IOException.class, () -> store.claim(id, "fingerprint"));
    assertFalse(failure.toString().contains("private-invalid-input"));
    assertNull(failure.getCause());
    Files.delete(binding);
    Files.createSymbolicLink(binding, root.resolve("outside"));
    assertThrows(IOException.class, () -> store.claim(id, "fingerprint"));
  }
}
