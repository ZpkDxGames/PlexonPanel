package io.github.zpkdxgames.plexonpanel.identity;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperInstanceBootstrapTest {
  @TempDir Path root;
  private final UUID serverId = UUID.randomUUID(), nodeId = UUID.randomUUID();
  private NodeInstanceRegistry.Registry registry() {
    return new NodeInstanceRegistry.Registry(5, nodeId, List.of(new NodeInstanceRegistry.Entry(serverId, "plexoncraft", 25565, 25575)));
  }
  private FleetIdentity validate(Path path, String user, int port, UUID node) throws Exception {
    return PaperInstanceBootstrap.validate(path, path.resolve("plugins/PlexonPanel"), user, port, registry(), node);
  }
  @Test void exactPublicRegistrationDeterminesFreshIdentityBeforeAnyFilePublication() throws Exception {
    var target = validate(Path.of("/srv/plexonpanel/servers/plexoncraft/server"), "mc-plexoncraft", 25565, nodeId);
    assertEquals(serverId, target.serverId()); assertEquals("plexoncraft", target.instanceKey());
    new IdentityStore(root).validateRegisteredServerId(target.serverId());
    assertFalse(Files.exists(root.resolve("identity")));
    assertEquals(serverId, new IdentityStore(root).loadOrCreate(target.serverId()).serverId());
  }
  @Test void copiedRootWrongAccountPortNodeAndUnregisteredInstanceFail() {
    Path canonical = Path.of("/srv/plexonpanel/servers/plexoncraft/server");
    assertThrows(Exception.class, () -> validate(Path.of("/opt/plexoncraft/server"), "mc-plexoncraft", 25565, nodeId));
    assertThrows(Exception.class, () -> validate(canonical.resolve("../server"), "mc-plexoncraft", 25565, nodeId));
    assertThrows(Exception.class, () -> validate(canonical, "mc-other", 25565, nodeId));
    assertThrows(Exception.class, () -> validate(canonical, "mc-plexoncraft", 25566, nodeId));
    assertThrows(Exception.class, () -> validate(canonical, "mc-plexoncraft", 25565, UUID.randomUUID()));
    assertThrows(Exception.class, () -> validate(Path.of("/srv/plexonpanel/servers/other/server"), "mc-other", 25565, nodeId));
    assertThrows(Exception.class, () -> PaperInstanceBootstrap.validate(canonical, root, "mc-plexoncraft", 25565, registry(), nodeId));
  }
  @Test void copiedExistingUuidFailsPreflightWithoutRewritingKeysOrPermissions() throws Exception {
    var store = new IdentityStore(root); var original = store.loadOrCreate();
    Path key = root.resolve("identity/device.key"), metadata = root.resolve("identity/device.json");
    byte[] keyBytes = Files.readAllBytes(key), metadataBytes = Files.readAllBytes(metadata);
    var permissions = Files.getPosixFilePermissions(key);
    assertThrows(java.io.IOException.class, () -> store.validateRegisteredServerId(serverId));
    assertArrayEquals(keyBytes, Files.readAllBytes(key)); assertArrayEquals(metadataBytes, Files.readAllBytes(metadata));
    assertEquals(permissions, Files.getPosixFilePermissions(key));
    store.validateRegisteredServerId(original.serverId());
    Files.delete(key);
    assertThrows(java.io.IOException.class, () -> store.validateRegisteredServerId(original.serverId()));
    assertFalse(Files.exists(key));
  }
}
