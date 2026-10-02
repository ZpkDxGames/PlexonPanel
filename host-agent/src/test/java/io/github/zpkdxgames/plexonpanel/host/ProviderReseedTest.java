package io.github.zpkdxgames.plexonpanel.host;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderReseedTest {
  @TempDir Path root;
  @Test void offlinePreservationRefusesActiveLeaseAndKeepsOldPrivateStateWithoutDeletion() throws Exception {
    Path provider = root.resolve("provider"); Files.createDirectory(provider, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path value = provider.resolve("rclone.conf"); io.github.zpkdxgames.plexonpanel.util.AtomicFiles.writeUtf8(value,"synthetic-private-preserved-provider-state");
    Files.createDirectories(root.resolve("identity")); Path lock = root.resolve("identity/fleet.lock");
    Files.createFile(lock,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    String owner = Files.getOwner(provider).getName();
    try(var channel=FileChannel.open(lock,StandardOpenOption.WRITE);var held=channel.lock()) {
      assertEquals("PROVIDER_RESEED_BUSY",assertThrows(IOException.class,()->ProviderReseed.preserve(root,owner)).getMessage());
      assertTrue(Files.exists(value));
    }
    Path archive = ProviderReseed.preserve(root,owner);
    assertFalse(Files.exists(provider)); assertTrue(Files.exists(lock));
    assertEquals("synthetic-private-preserved-provider-state",Files.readString(archive.resolve("rclone.conf")));
    assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(archive)));
    assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(archive.resolve("rclone.conf"))));
  }
}
