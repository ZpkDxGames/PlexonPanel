package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Independent, non-destructive backup gates. No provider failure masks local readiness. */
final class FullBackupPreflight {
  private static final int ENTRY_LIMIT = 500_000;
  private static final long SAFETY_MARGIN = 256L * 1024 * 1024;
  enum Readiness { READY, FAILED }
  enum ProviderState { CONNECTED, NOT_CONFIGURED, FAILED }
  enum ConfigState { ABSENT, UNREADABLE, OK }
  record ActiveRecovery(String jobId, String kind, String stage, int stageIndex,
      String errorCode, String errorMessage, Instant updatedAt) {}
  record RecoveryGate(boolean jobRecoveryRequired, boolean restoreRecoveryRequired, ActiveRecovery active) {
    boolean recoveryRequired() { return jobRecoveryRequired || restoreRecoveryRequired; }
  }
  record SourceGate(Readiness state, long bytes, long entryCount, List<String> includes, String errorCode) {}
  record StorageGate(Readiness state, boolean backupRootWritable, long usableBytes, long requiredBytes, String errorCode) {}
  record ProviderGate(ProviderState state, String provider, String remote, String errorCode, String message, Instant testedAt) {}
  record PreflightReport(RecoveryGate recovery, SourceGate source, StorageGate storage, ProviderGate provider) {
    boolean localBackupReady() { return !recovery.recoveryRequired() && source.state() == Readiness.READY && storage.state() == Readiness.READY; }
    boolean offsiteBackupReady() { return localBackupReady() && provider.state() == ProviderState.CONNECTED; }
    Map<String,Object> toMap() {
      var m = new LinkedHashMap<String,Object>();
      m.put("recoveryRequired", recovery.recoveryRequired());
      m.put("jobRecoveryRequired", recovery.jobRecoveryRequired());
      m.put("restoreRecoveryRequired", recovery.restoreRecoveryRequired());
      if (recovery.active() != null) {
        var a = recovery.active(); var j = new LinkedHashMap<String,Object>();
        j.put("jobId",a.jobId()); j.put("kind",a.kind()); j.put("stage",a.stage()); j.put("stageIndex",a.stageIndex());
        j.put("errorCode",a.errorCode()); j.put("errorMessage",a.errorMessage()); j.put("updatedAt",a.updatedAt()==null?null:a.updatedAt().toString());
        m.put("recoveryJob",j);
      }
      m.put("sourceState",source.state().name()); m.put("sourceBytes",source.bytes()); m.put("entryCount",source.entryCount()); m.put("includes",source.includes()); m.put("sourceErrorCode",source.errorCode());
      m.put("storageState",storage.state().name()); m.put("backupRootWritable",storage.backupRootWritable()); m.put("usableBytes",storage.usableBytes()); m.put("requiredBytes",storage.requiredBytes()); m.put("storageErrorCode",storage.errorCode());
      m.put("providerState",provider.state().name()); m.put("provider",provider.provider()); m.put("remote",provider.remote()); m.put("providerErrorCode",provider.errorCode()); m.put("providerMessage",provider.message()); m.put("providerTestedAt",provider.testedAt()==null?null:provider.testedAt().toString());
      m.put("providerStatus",provider.state().name()); m.put("localBackupReady",localBackupReady()); m.put("offsiteBackupReady",offsiteBackupReady()); m.put("ok",offsiteBackupReady());
      return m;
    }
  }
  interface Probes {
    RecoveryGate recovery() throws Exception;
    SourceGate source(MaintenanceSettings settings) throws Exception;
    Path backupRoot(); Path stagingRoot(); long usableBytes(Path dir) throws IOException;
    ProviderProbe provider();
  }
  interface ProviderProbe {
    boolean binaryPresent(); ConfigState configState(); String name(); String remote();
    TestResult test(Duration timeout);
  }
  record TestResult(boolean ok, String errorCode, String message) {}
  private record CachedProvider(ProviderGate gate, Instant at) {}
  private final Function<MaintenanceSettings,Probes> probesFor;
  private final AtomicReference<CachedProvider> providerCache = new AtomicReference<>();
  FullBackupPreflight(Function<MaintenanceSettings,Probes> probesFor) { this.probesFor=probesFor; }
  FullBackupPreflight(HostConfig config, FullRestorePointManager backups) {
    this(settings -> new Probes() {
      public RecoveryGate recovery() throws Exception {
        var store = new MaintenanceStateStore(Path.of(config.dataDirectory()));
        var job = store.recoveryRequired(); ActiveRecovery active = null;
        if (job != null) {
          var detail=store.details(job); String phase=String.valueOf(detail.getOrDefault("failedStage",job.phase()));
          if ("RECOVERY_REQUIRED".equals(phase) && job.progressPercent()>=35 && job.progressPercent()<40) phase="STOPPING_SERVER";
          var stage=BackupStage.forPhase(phase);
          active=new ActiveRecovery(job.jobId(),job.kind(),stage.label,stage.ordinal()+1,job.errorCode(),job.errorMessage(),Instant.parse(job.updatedAt()));
        }
        return new RecoveryGate(job!=null,backups.recoveryRequired(),active);
      }
      public SourceGate source(MaintenanceSettings settings) throws IOException {
        var includes=List.copyOf(config.backups().include());
        Path root=Path.of(config.serverRoot());
        if (!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return new SourceGate(Readiness.FAILED,0,0,includes,"SOURCE_ROOT_MISSING");
        Path real=root.toRealPath();
        for(String include:includes) {
          if(include!=null && !real.resolve(include).normalize().startsWith(real))
            return new SourceGate(Readiness.FAILED,0,0,includes,"SOURCE_PATH_ESCAPES_ROOT");
        }
        Scan scanned=scan(real,includes,settings.fullRestorePoint());
        return new SourceGate(scanned.entries()>0?Readiness.READY:Readiness.FAILED,scanned.bytes(),scanned.entries(),includes,scanned.entries()>0?null:"SOURCE_EMPTY");
      }
      public Path backupRoot() { return Path.of(config.backups().directory()).toAbsolutePath().normalize(); }
      public Path stagingRoot() { return backupRoot().resolve("staging"); }
      public long usableBytes(Path dir) throws IOException { return Files.getFileStore(dir).getUsableSpace(); }
      public ProviderProbe provider() { return new ProviderProbe() {
        final HostConfig.BackupConfig c=config.backups();
        public boolean binaryPresent() { return !remote().isBlank() && "/usr/bin/rclone".equals(c.rcloneExecutable()) && Files.isRegularFile(Path.of(c.rcloneExecutable()),LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(Path.of(c.rcloneExecutable())); }
        public ConfigState configState() {
          if (c.rcloneConfig()==null || c.rcloneConfig().isBlank()) return ConfigState.ABSENT;
          Path p=Path.of(c.rcloneConfig());
          if (!Files.exists(p,LinkOption.NOFOLLOW_LINKS)) return ConfigState.ABSENT;
          return p.isAbsolute() && Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(p) && Files.isReadable(p) ? ConfigState.OK:ConfigState.UNREADABLE;
        }
        public String name() { return "RCLONE"; }
        public String remote() { return String.valueOf(backups.providerStatus().getOrDefault("remote","")); }
        public TestResult test(Duration timeout) {
          try { backups.testProvider((int)timeout.toSeconds()); return new TestResult(true,null,null); }
          catch (OperationFailure e) { return new TestResult(false,e.code(),"The running Host could not validate the off-site provider."); }
          catch (Exception e) { return new TestResult(false,"PROVIDER_UNREACHABLE","The running Host could not reach the off-site provider."); }
        }
      }; }
    });
  }
  PreflightReport inspect(MaintenanceSettings settings, boolean freshProvider) {
    Probes p;
    try { p=probesFor.apply(settings); }
    catch (RuntimeException e) { return new PreflightReport(new RecoveryGate(true,false,null),new SourceGate(Readiness.FAILED,0,0,List.of(),"SOURCE_UNREADABLE"),new StorageGate(Readiness.FAILED,false,0,0,"STORAGE_UNAVAILABLE"),failedProvider("PREFLIGHT_INTERNAL_ERROR")); }
    try (var executor=Executors.newVirtualThreadPerTaskExecutor()) {
      Future<ProviderGate> provider=executor.submit(() -> providerGate(p,freshProvider));
      RecoveryGate recovery;
      try { recovery=Objects.requireNonNull(p.recovery()); } catch (Exception e) { recovery=new RecoveryGate(true,false,null); }
      SourceGate source;
      try { source=p.source(settings); }
      catch (Exception e) { String code=e.getMessage(); source=new SourceGate(Readiness.FAILED,0,0,List.of(),safeSourceCode(code)); }
      StorageGate storage=storageGate(p,source);
      ProviderGate remote;
      try { remote=provider.get(12,TimeUnit.SECONDS); }
      catch (InterruptedException e) { Thread.currentThread().interrupt(); provider.cancel(true); remote=failedProvider("PREFLIGHT_INTERRUPTED"); }
      catch (Exception e) { provider.cancel(true); remote=failedProvider("PREFLIGHT_INTERNAL_ERROR"); }
      return new PreflightReport(recovery,source,storage,remote);
    }
  }
  PreflightReport check(MaintenanceSettings settings, boolean requireRemote) {
    var report=inspect(settings,requireRemote); assertReady(report,requireRemote); return report;
  }
  static void assertReady(PreflightReport r, boolean remote) {
    if(r.recovery().recoveryRequired()) throw preflightFailure("RECOVERY_REQUIRED","Recovery must be resolved before a backup.");
    if(r.source().state()!=Readiness.READY) throw preflightFailure(r.source().errorCode(),"The server source cannot be scanned safely.");
    if(r.storage().state()!=Readiness.READY) throw preflightFailure(r.storage().errorCode(),"Backup storage lacks write access or sufficient headroom.");
    if(remote) assertProvider(r.provider());
  }
  static void assertProvider(ProviderGate p) { if(p.state()!=ProviderState.CONNECTED) throw preflightFailure(p.errorCode(),p.message()); }
  private static StorageGate storageGate(Probes p, SourceGate source) {
    long required=source.state()==Readiness.READY?requiredSpace(source.bytes()):0;
    try {
      if(!writable(p.backupRoot())) return new StorageGate(Readiness.FAILED,false,0,required,"BACKUP_DIR_NOT_WRITABLE");
      long rootFree=p.usableBytes(p.backupRoot());
      if(!writable(p.stagingRoot())) return new StorageGate(Readiness.FAILED,true,rootFree,required,"STAGING_NOT_WRITABLE");
      long usable=Math.min(rootFree,p.usableBytes(p.stagingRoot()));
      return new StorageGate(required>0 && usable<required?Readiness.FAILED:Readiness.READY,true,usable,required,required>0 && usable<required?"INSUFFICIENT_DISK_SPACE":null);
    } catch(Exception e) { return new StorageGate(Readiness.FAILED,false,0,required,"STORAGE_UNAVAILABLE"); }
  }
  private static boolean writable(Path path) {
    try {
      if(path==null || Files.isSymbolicLink(path)) return false;
      Files.createDirectories(path); Path probe=Files.createTempFile(path,".preflight-",".tmp"); Files.delete(probe); return true;
    } catch(IOException | SecurityException e) { return false; }
  }
  private synchronized ProviderGate providerGate(Probes p, boolean fresh) {
    var cached=providerCache.get(); Instant now=Instant.now();
    if(!fresh && cached!=null && Duration.between(cached.at(),now).compareTo(Duration.ofSeconds(30))<0) return cached.gate();
    ProviderGate gate;
    try {
      var pr=p.provider();
      if(!pr.binaryPresent() || pr.configState()==ConfigState.ABSENT) gate=new ProviderGate(ProviderState.NOT_CONFIGURED,pr.name(),pr.remote(),"RCLONE_UNAVAILABLE","Off-site provider is not configured on this Host.",now);
      else if(pr.configState()==ConfigState.UNREADABLE) gate=new ProviderGate(ProviderState.FAILED,pr.name(),pr.remote(),"RCLONE_CONFIG_UNREADABLE","The rclone configuration file cannot be read.",now);
      else { var test=pr.test(Duration.ofSeconds(10)); gate=new ProviderGate(test.ok()?ProviderState.CONNECTED:ProviderState.FAILED,pr.name(),pr.remote(),test.ok()?null:orDefault(test.errorCode(),"PROVIDER_UNREACHABLE"),test.ok()?null:orDefault(test.message(),"Provider validation failed."),now); }
    } catch (RuntimeException e) { gate=failedProvider("PREFLIGHT_INTERNAL_ERROR"); }
    providerCache.set(new CachedProvider(gate,Instant.now())); return gate;
  }
  private static ProviderGate failedProvider(String code) { return new ProviderGate(ProviderState.FAILED,"RCLONE",null,code,"Provider check did not complete.",Instant.now()); }
  private static String orDefault(String v,String d) { return v==null || v.isBlank()?d:v; }
  private static String safeSourceCode(String code) { return code!=null && Set.of("BACKUP_INCLUDE_INVALID","BACKUP_SOURCE_MISSING","BACKUP_SOURCE_UNREADABLE","BACKUP_SOURCE_CHANGED","BACKUP_SYMLINK_REJECTED","BACKUP_ENTRY_LIMIT","BACKUP_SIZE_LIMIT").contains(code)?code:"SOURCE_UNREADABLE"; }
  static OperationFailure preflightFailure(String code,String message) { return OperationFailure.withSafeDetails(orDefault(code,"PREFLIGHT_INTERNAL_ERROR"),"PREFLIGHT",orDefault(message,"Backup preflight failed."),true,Map.of()); }
  static OperationFailure translate(IOException e,String fallback,String stage) { return OperationFailure.withSafeDetails(safeSourceCode(e.getMessage()).equals("SOURCE_UNREADABLE")?fallback:e.getMessage(),"PREFLIGHT","The Host backup preflight failed its local safety contract.",true,Map.of("stage",stage)); }
  static long requiredSpace(long bytes) { try { return Math.addExact(Math.multiplyExact(bytes,2),SAFETY_MARGIN); } catch(ArithmeticException e) { return Long.MAX_VALUE; } }
  static Scan scan(
      Path root, List<String> includes, MaintenanceSettings.FullRestorePoint settings)
      throws IOException {
    long[] bytes = {0};
    int[] entries = {0};
    FullBackupSource.walk(
        root,
        includes,
        settings,
        new FullBackupSource.Visitor() {
          @Override
          public void visitFile(Path file, Path relative, BasicFileAttributes attributes)
              throws IOException {
            if (++entries[0] > ENTRY_LIMIT) throw new IOException("BACKUP_ENTRY_LIMIT");
            try {
              bytes[0] = Math.addExact(bytes[0], attributes.size());
            } catch (ArithmeticException overflow) {
              throw new IOException("BACKUP_SIZE_LIMIT", overflow);
            }
            if (bytes[0] > settings.maximumBytes()) throw new IOException("BACKUP_SIZE_LIMIT");
          }
        });
    return new Scan(bytes[0], entries[0]);
  }

  record Scan(long bytes, int entries) {}
}
