package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FullBackupPreflightTest {
  @TempDir Path temporary;

  @Test
  void requiredSpaceIncludesTwoCopiesAndSafetyMargin() {
    assertEquals(2_000_000L + 256L * 1024 * 1024, FullBackupPreflight.requiredSpace(1_000_000L));
  }

  @Test
  void requiredSpaceKeepsTwoCopiesForLargeArchives() {
    long source = 10L * 1024 * 1024 * 1024;
    assertEquals(source * 2 + 256L * 1024 * 1024, FullBackupPreflight.requiredSpace(source));
  }

  @Test
  void requiredSpaceFailsClosedOnOverflow() {
    assertEquals(Long.MAX_VALUE, FullBackupPreflight.requiredSpace(Long.MAX_VALUE - 1));
  }

  @Test
  void scanIsBoundedToConfiguredTopLevelIncludes() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Files.createDirectories(root.resolve("world/region"));
    Files.writeString(root.resolve("world/level.dat"), "world-state");
    Files.writeString(root.resolve("world/region/r.0.0.mca"), "region-state");
    Files.writeString(root.resolve("unconfigured-secret.dat"), "must-not-be-read");

    FullBackupPreflight.Scan scan =
        FullBackupPreflight.scan(
            root,
            List.of("world"),
            MaintenanceSettings.migratedDefaults().fullRestorePoint());

    assertEquals(2, scan.entries());
    assertEquals("world-state".length() + "region-state".length(), scan.bytes());
  }

  @Test
  void missingConfiguredIncludeFailsClosed() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                FullBackupPreflight.scan(
                    root,
                    List.of("world"),
                    MaintenanceSettings.migratedDefaults().fullRestorePoint()));

    assertEquals("BACKUP_SOURCE_MISSING", failure.getMessage());
  }

  @Test
  void unreadableConfiguredFileFailsClosed() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path world = Files.createDirectory(root.resolve("world"));
    Path unreadable = Files.writeString(world.resolve("level.dat"), "world-state");
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
    Files.setPosixFilePermissions(unreadable, PosixFilePermissions.fromString("-w-------"));

    assumeFalse(Files.isReadable(unreadable), "Privileged process bypasses POSIX read denial");
    IOException failure = assertThrows(IOException.class, () -> FullBackupPreflight.scan(root,List.of("world"),MaintenanceSettings.migratedDefaults().fullRestorePoint()));
    assertEquals("BACKUP_SOURCE_UNREADABLE",failure.getMessage());
  }

  @Test
  void unreadableMandatoryExcludedDirectoryIsPrunedWithoutGrantingReadAccess() throws Exception {
    if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return;
    Path root = Files.createDirectory(temporary.resolve("server"));
    Path plugins = Files.createDirectories(root.resolve("plugins/PlexonPanel"));
    Path audit = Files.createDirectory(plugins.resolve("audit"));
    Files.writeString(audit.resolve("private.jsonl"), "must-not-be-read");
    Path included = Files.writeString(root.resolve("plugins/kept.yml"), "included");
    Files.setPosixFilePermissions(audit, PosixFilePermissions.fromString("---------"));

    try {
      assumeFalse(Files.isReadable(audit), "Privileged test process bypasses POSIX read denial");
      FullBackupPreflight.Scan scan =
          FullBackupPreflight.scan(
              root,
              List.of("plugins"),
              MaintenanceSettings.migratedDefaults().fullRestorePoint());

      assertEquals(1, scan.entries());
      assertEquals(Files.size(included), scan.bytes());
    } finally {
      Files.setPosixFilePermissions(audit, PosixFilePermissions.fromString("rwx------"));
    }
  }

  @Test
  void sourceFailureBecomesTypedBrowserSafePreflightFailure() {
    OperationFailure failure =
        FullBackupPreflight.translate(
            new IOException("BACKUP_SOURCE_MISSING"),
            "BACKUP_SOURCE_UNREADABLE",
            "source");

    assertEquals("BACKUP_SOURCE_MISSING", failure.code());
    assertEquals("PREFLIGHT", failure.phase());
    assertTrue(failure.retryable());
    assertEquals("source", failure.safeData().get("stage"));
    assertFalse(failure.getMessage().contains("/"));
  }

  @Test
  void rawIoFailureUsesTheStageFallbackWithoutLeakingItsMessage() {
    OperationFailure failure =
        FullBackupPreflight.translate(
            new IOException("/private/provider/path: permission denied"),
            "RCLONE_CONFIG_UNREADABLE",
            "provider");

    assertEquals("RCLONE_CONFIG_UNREADABLE", failure.code());
    assertEquals("provider", failure.safeData().get("stage"));
    assertFalse(failure.getMessage().contains("private"));
  }

  @Test void evaluatesAllGatesWithoutRclone() {
    var p=new FakeProbes(temporary); var pf=new FullBackupPreflight(s->p);
    var r=pf.inspect(null,false);
    assertEquals(FullBackupPreflight.Readiness.READY,r.source().state());
    assertEquals(FullBackupPreflight.Readiness.READY,r.storage().state());
    assertEquals(FullBackupPreflight.ProviderState.NOT_CONFIGURED,r.provider().state());
    assertEquals("RCLONE_UNAVAILABLE",r.provider().errorCode());
    assertTrue(r.localBackupReady()); assertFalse(r.offsiteBackupReady());
    assertDoesNotThrow(()->pf.check(null,false));
    assertEquals("RCLONE_UNAVAILABLE",assertThrows(OperationFailure.class,()->pf.check(null,true)).code());
  }
  @Test void recoveryIsNeverMaskedAndUnknownStateFailsClosed() {
    var p=new FakeProbes(temporary); p.recovery=true; var pf=new FullBackupPreflight(s->p);
    assertEquals("RECOVERY_REQUIRED",assertThrows(OperationFailure.class,()->pf.check(null,true)).code());
    p.recoveryThrows=true; assertTrue(pf.inspect(null,false).recovery().recoveryRequired());
  }
  @Test void independentDiskAndSourceReports() {
    var p=new FakeProbes(temporary); p.usable=1024; var pf=new FullBackupPreflight(s->p);
    assertEquals("INSUFFICIENT_DISK_SPACE",pf.inspect(null,false).storage().errorCode());
    assertFalse(pf.inspect(null,false).localBackupReady());
    p.sourceMissing=true; var r=pf.inspect(null,false);
    assertEquals("SOURCE_ROOT_MISSING",r.source().errorCode());
    assertEquals(FullBackupPreflight.Readiness.READY,r.storage().state());
  }
  @Test void providerCacheFreshBypassAndUnreadableConfig() {
    var p=new FakeProbes(temporary); p.providerConfigured=true; var pf=new FullBackupPreflight(s->p);
    pf.inspect(null,false); pf.inspect(null,false); assertEquals(1,p.tests.get());
    pf.inspect(null,true); assertEquals(2,p.tests.get());
    p.configUnreadable=true; assertEquals("RCLONE_CONFIG_UNREADABLE",pf.inspect(null,true).provider().errorCode());
  }
  @Test void reportExposesAllPublicKeys() {
    var p=new FakeProbes(temporary); p.providerConfigured=true;
    var m=new FullBackupPreflight(s->p).inspect(null,false).toMap();
    for(var key:List.of("recoveryRequired","sourceState","sourceBytes","entryCount","includes","storageState","backupRootWritable","usableBytes","requiredBytes","providerState","provider","remote","localBackupReady","offsiteBackupReady","providerStatus","ok")) assertTrue(m.containsKey(key),key);
    assertEquals(true,m.get("offsiteBackupReady"));
  }
}
