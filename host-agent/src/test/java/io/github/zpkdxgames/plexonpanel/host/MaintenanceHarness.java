package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.audit.LocalAudit;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** Exercises the real durable worker/archive path with a fake systemd transport and provider. */
final class MaintenanceHarness implements AutoCloseable {
  final Path data, output;
  final FakeProbes probes;
  final ReentrantLock lock=new ReentrantLock();
  final List<String> actions=new CopyOnWriteArrayList<>(), events=new CopyOnWriteArrayList<>();
  final MaintenanceManager manager;
  final MaintenanceStateStore state;
  final FullRestorePointManager backups;
  volatile boolean active=true,ready=true,dropDuringCountdown;
  volatile Instant now=Instant.parse("2026-10-19T12:00:00Z");
  MaintenanceHarness(Path dir) throws Exception {
    Path root=Files.createDirectories(dir.resolve("server"));
    Files.createDirectories(root.resolve("world")); Files.writeString(root.resolve("world/level.dat"),"synthetic world");
    data=Files.createDirectories(dir.resolve("data")); output=dir.resolve("backups");
    var config=new HostConfig(UUID.randomUUID().toString(),"Test SMP","","",root.toString(),data.toString(),data.resolve("access.json").toString(),
        "minecraft@test.service",Map.of(),new HostConfig.BackupConfig(true,output.toString(),List.of("world"),3,0,1048576,false,"/usr/bin/rclone","drive:backups",dir.resolve("rclone.conf").toString()),HostConfig.ConsoleConfig.defaults());
    var service=new SystemdService(config.serviceName(),(args,seconds)-> {
      String command=args.get(1);
      if(command.equals("stop")) { actions.add("stop"); active=false; return ""; }
      if(command.equals("start")) { actions.add("start"); active=true; return ""; }
      return "ActiveState="+(active?"active":"inactive")+"\nMainPID=0\nSubState="+(active?"running":"dead")+"\n";
    });
    var commands=new MinecraftCommandChannel() {
      public boolean enabled() { return true; }
      public Result maintenanceNotice(MaintenanceOperation op,int seconds) { now=now.plusSeconds(seconds+1); if(dropDuringCountdown) probes.providerOk=false; return Result.ok(); }
      public Result saveAllFlush() { return Result.ok(); }
      public Result readinessProbe() { return ready?Result.ok():Result.failed("NOT_READY"); }
    };
    // A byte-accurate fake rclone: remote hashes are calculated from the actual ZIP, not guessed.
    Map<String,byte[]> remote=new HashMap<>();
    var provider=new RcloneBackupProvider(config.backups(),(args,seconds)-> {
      String op=args.get(1), path=args.get(2); byte[] bytes=remote.get(path);
      return switch(op) {
        case "lsjson" -> new RcloneBackupProvider.ProcessResult(0,"[]");
        case "copyto" -> { byte[] source=remote.get(path); if(source==null) source=Files.readAllBytes(Path.of(path)); remote.put(args.get(3),source); yield new RcloneBackupProvider.ProcessResult(0,""); }
        case "lsl" -> new RcloneBackupProvider.ProcessResult(bytes==null?4:0,bytes==null?"not found":bytes.length+" object");
        case "lsf" -> new RcloneBackupProvider.ProcessResult(bytes==null?4:0,bytes==null?"":"object");
        case "hashsum" -> { byte[] hashed=remote.get(args.get(3)); yield new RcloneBackupProvider.ProcessResult(hashed==null?4:0,hashed==null?"":HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(hashed))+"  "+args.get(3).substring(args.get(3).lastIndexOf('/')+1)); }
        case "deletefile" -> { remote.remove(path); yield new RcloneBackupProvider.ProcessResult(0,""); }
        default -> new RcloneBackupProvider.ProcessResult(1,"unsupported");
      };
    });
    backups=new FullRestorePointManager(config,service,()->false,lock,e->{},null,provider);
    state=new MaintenanceStateStore(data);
    probes=new FakeProbes(dir) {
      @Override public FullBackupPreflight.RecoveryGate recovery() {
        try { return new FullBackupPreflight.RecoveryGate(state.recoveryRequired()!=null,backups.recoveryRequired(),null); }
        catch(Exception e) { throw new IllegalStateException(e); }
      }
    };
    Clock clock=new Clock() { public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return now;} };
    manager=new MaintenanceManager(config,service,commands,lock,new LocalAudit(data.resolve("audit"),30),(type,body,priority)->{events.add(type);return true;},backups,clock,new FullBackupPreflight(s->probes));
  }
  void fallback(boolean value) throws Exception {
    JsonObject settings=new Gson().toJsonTree(manager.settings()).getAsJsonObject();
    settings.getAsJsonObject("fullRestorePoint").addProperty("allowLocalFallback",value); manager.updateSettings(settings);
  }
  MaintenanceStateStore.Job await(String id) throws Exception {
    long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
    while(System.nanoTime()<until) { var job=state.active(); if(job!=null && id.equals(job.jobId()) && MaintenanceStateStore.terminal(job)) return job; Thread.sleep(10); }
    fail("Job did not terminate: "+state.active()); return null;
  }
  void latch() throws Exception { var j=state.begin("FULL_RESTORE_POINT",null,false,"STOPPING_SERVER"); state.finish(j,"RECOVERY_REQUIRED","STOP_TIMEOUT"); }
  public void close(){manager.close();}
}
