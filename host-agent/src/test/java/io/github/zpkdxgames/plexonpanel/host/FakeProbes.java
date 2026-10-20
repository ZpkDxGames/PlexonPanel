package io.github.zpkdxgames.plexonpanel.host;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class FakeProbes implements FullBackupPreflight.Probes {
  final Path root;
  boolean recovery, recoveryThrows, sourceMissing, providerConfigured, providerOk=true;
  boolean configUnreadable;
  long usable=Long.MAX_VALUE;
  final AtomicInteger tests=new AtomicInteger();
  FakeProbes(Path root) { this.root=root; }
  public FullBackupPreflight.RecoveryGate recovery() {
    if(recoveryThrows) throw new IllegalStateException("private store path");
    return new FullBackupPreflight.RecoveryGate(recovery,false,null);
  }
  public FullBackupPreflight.SourceGate source(MaintenanceSettings settings) {
    return new FullBackupPreflight.SourceGate(sourceMissing?FullBackupPreflight.Readiness.FAILED:FullBackupPreflight.Readiness.READY,
        sourceMissing?0:1024,sourceMissing?0:1,List.of("world"),sourceMissing?"SOURCE_ROOT_MISSING":null);
  }
  public Path backupRoot() { return root.resolve("backups"); }
  public Path stagingRoot() { return backupRoot().resolve("staging"); }
  public long usableBytes(Path ignored) { return usable; }
  public FullBackupPreflight.ProviderProbe provider() { return new FullBackupPreflight.ProviderProbe() {
    public boolean binaryPresent() { return providerConfigured; }
    public FullBackupPreflight.ConfigState configState() { return configUnreadable?FullBackupPreflight.ConfigState.UNREADABLE:FullBackupPreflight.ConfigState.OK; }
    public String name() { return "RCLONE"; }
    public String remote() { return "drive:backups"; }
    public FullBackupPreflight.TestResult test(Duration timeout) {
      tests.incrementAndGet();
      return new FullBackupPreflight.TestResult(providerOk,providerOk?null:"PROVIDER_UNREACHABLE",providerOk?null:"Provider unreachable.");
    }
  }; }
}
