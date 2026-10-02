package io.github.zpkdxgames.plexonpanel.host;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderRuntimeConfigurationTest {
  @TempDir Path root;
  private HostConfig configured(String key) throws Exception {
    Path folder = root.resolve(key); Files.createDirectories(folder.resolve("state"));
    var fixture = new HostConfigTest(); fixture.root = folder;
    var raw = fixture.config(); var backup = raw.getAsJsonObject("backups");
    Path source = folder.resolve("provider-source.conf"); Files.writeString(source, "synthetic-private-provider-credential-"+key);
    backup.addProperty("rcloneRemote", "offsite:instances/"+raw.get("serverId").getAsString());
    backup.addProperty("rcloneConfig", source.toString()); raw.addProperty("dataDirectory", folder.resolve("state").toString());
    return HostConfig.fromJson(raw);
  }
  @Test void credentialsSeedOnceRemainPrivateAndRefreshedStateSurvivesRestartAcrossInstances() throws Exception {
    var a = configured("alpha"); var b = configured("bravo");
    var runtimeA = ProviderRuntimeConfiguration.prepare(a); var runtimeB = ProviderRuntimeConfiguration.prepare(b);
    Path pathA = Path.of(runtimeA.backups().rcloneConfig()), pathB = Path.of(runtimeB.backups().rcloneConfig());
    assertNotEquals(pathA,pathB); assertEquals(Files.readString(Path.of(a.backups().rcloneConfig())),Files.readString(pathA));
    assertNotEquals(Files.readString(pathA),Files.readString(pathB));
    assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(pathA)));
    assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(pathA.getParent())));
    io.github.zpkdxgames.plexonpanel.util.AtomicFiles.writeUtf8(pathA,"synthetic-refreshed-private-state");
    var restarted = ProviderRuntimeConfiguration.prepare(a);
    assertEquals(pathA.toString(),restarted.backups().rcloneConfig()); assertEquals("synthetic-refreshed-private-state",Files.readString(pathA));
    assertFalse(Files.readString(Path.of(a.backups().rcloneConfig())).contains("refreshed"));
  }
  @Test void interruptedInitialSeedCanRetryButMissingChangedAndUnsafeStateNeverOverwritesCredentials() throws Exception {
    var a = configured("alpha"); AtomicInteger writes = new AtomicInteger();
    assertThrows(IOException.class, () -> ProviderRuntimeConfiguration.prepare(a,(path,text) -> {
      if(writes.incrementAndGet()==2)throw new IOException("SIMULATED_MARKER_WRITE_FAILURE");
      io.github.zpkdxgames.plexonpanel.util.AtomicFiles.writeUtf8(path,text);
    }));
    var runtime = ProviderRuntimeConfiguration.prepare(a); Path target = Path.of(runtime.backups().rcloneConfig());
    byte[] before = Files.readAllBytes(target); Files.writeString(Path.of(a.backups().rcloneConfig()),"synthetic-source-rotation");
    var denied=assertThrows(IOException.class,()->ProviderRuntimeConfiguration.prepare(a));
    assertEquals("PROVIDER_SOURCE_CHANGE_REQUIRES_OFFLINE_RESEED",denied.getMessage()); assertArrayEquals(before,Files.readAllBytes(target));
    assertFalse(denied.toString().contains("synthetic"));
    var b = configured("bravo"); var runtimeB = ProviderRuntimeConfiguration.prepare(b); Path bTarget=Path.of(runtimeB.backups().rcloneConfig());
    Files.setPosixFilePermissions(bTarget,PosixFilePermissions.fromString("rw-r--r--"));
    assertEquals("PROVIDER_RUNTIME_AUTHORITY_INVALID",assertThrows(IOException.class,()->ProviderRuntimeConfiguration.prepare(b)).getMessage());
    Files.delete(bTarget); Files.createSymbolicLink(bTarget,target);
    assertThrows(IOException.class,()->ProviderRuntimeConfiguration.prepare(b));
  }
  @Test void missingRuntimeStateAndRedirectedSourceCannotRegenerateOrCopyCredentials() throws Exception {
    var a = configured("alpha"); var prepared = ProviderRuntimeConfiguration.prepare(a);
    Path target = Path.of(prepared.backups().rcloneConfig()); Files.delete(target);
    assertEquals("PROVIDER_RUNTIME_STATE_INCOMPLETE", assertThrows(IOException.class, () -> ProviderRuntimeConfiguration.prepare(a)).getMessage());
    assertFalse(Files.exists(target));
    Path source = Path.of(a.backups().rcloneConfig()); Path replacement = root.resolve("other-source");
    Files.writeString(replacement,"synthetic-other-credential"); Files.delete(source); Files.createSymbolicLink(source,replacement);
    assertThrows(IOException.class, () -> ProviderRuntimeConfiguration.prepare(a)); assertFalse(Files.exists(target));
  }
}
