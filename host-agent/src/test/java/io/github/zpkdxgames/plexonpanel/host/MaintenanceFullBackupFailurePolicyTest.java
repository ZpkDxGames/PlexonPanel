package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class MaintenanceFullBackupFailurePolicyTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
  @Test
  void archiveFailureBeforeLocalVerificationFailsClosed() {
    assertFalse(MaintenanceManager.shouldStartAfterFullBackupFailure(true, false));
  }

  @Test
  void preDestructiveFailureNeverStartsService() {
    assertFalse(MaintenanceManager.shouldStartAfterFullBackupFailure(false, false));
    assertFalse(MaintenanceManager.shouldStartAfterFullBackupFailure(false, true));
  }

  @Test
  void verifiedLocalBackupMayRecoverServiceAvailability() {
    assertTrue(MaintenanceManager.shouldStartAfterFullBackupFailure(true, true));
  }

  @Test void localOnlyCreatesVerifiedLocalArchiveWithoutProviderOrMaintenanceLeak() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      String id=h.manager.startFullBackup(null,0,DestinationMode.LOCAL_ONLY,false);
      var job=h.await(id); assertEquals("COMPLETED",job.phase(),job.toString()); var point=h.backups.list().getFirst();
      assertEquals("COMPLETED",job.phase()); assertEquals("VERIFIED_LOCAL",point.verification());
      assertTrue(point.local()); assertFalse(point.offsite()); assertNotNull(point.sha256());
      assertTrue(java.nio.file.Files.exists(h.backups.archive(point.backupId())));
      assertEquals(0,h.probes.tests.get()); assertEquals(java.util.List.of("stop","start"),h.actions);
      assertFalse(h.lock.isLocked());
      var detail=h.state.details(job); assertEquals("LOCAL_ONLY",detail.get("effectiveDestination"));
      assertFalse(((java.util.List<?>)detail.get("skippedStages")).isEmpty());
      assertTrue(h.manager.status().containsKey("stages"));
    }
  }
  @Test void autoFailsWithoutFallbackBeforeStopAndAutoFallsBackWhenOptedIn() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      assertEquals("RCLONE_UNAVAILABLE",assertThrows(io.github.zpkdxgames.plexonpanel.control.OperationFailure.class,()->h.manager.startFullBackup(null,0,DestinationMode.AUTO,false)).code());
      assertTrue(h.actions.isEmpty()); assertNull(h.state.active());
      h.fallback(true); var j=h.await(h.manager.startFullBackup(null,0,DestinationMode.AUTO,false));
      assertEquals("COMPLETED",j.phase());
      assertEquals("PROVIDER_NOT_CONFIGURED",h.state.details(j).get("fallbackReason"));
    }
  }
  @Test void driveDroppingDuringCountdownFailsBeforeStop() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      h.probes.providerConfigured=true; h.dropDuringCountdown=true;
      var j=h.await(h.manager.startFullBackup(null,300,DestinationMode.DRIVE_AND_LOCAL,false));
      assertEquals("FAILED",j.phase()); assertEquals("PROVIDER_UNREACHABLE",j.errorCode()); assertTrue(h.actions.isEmpty());
    }
  }
  @Test void resolveRecoveryPreservesFailureAndBroadcastsStatus() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      h.latch(); assertEquals(true,h.manager.resolveRecovery(null,false).get("resolved"));
      assertNull(h.state.recoveryRequired()); var j=h.state.active(); assertEquals("FAILED",j.phase());
      assertEquals("STOP_TIMEOUT",j.errorCode()); assertEquals("STOP_TIMEOUT",h.state.details(j).get("originalErrorCode"));
      assertTrue(h.events.contains("maintenance.recovery.resolved")); assertTrue(h.events.contains("maintenance.status"));
      assertEquals(false,h.manager.resolveRecovery(null,false).get("resolved"));
    }
  }
  @Test void recoveryRefusesInactiveOrNotReadyMinecraft() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      h.latch(); h.active=false;
      assertEquals("RECOVERY_SERVICE_NOT_ACTIVE",assertThrows(io.github.zpkdxgames.plexonpanel.control.OperationFailure.class,()->h.manager.resolveRecovery(null,false)).code());
      h.active=true; h.ready=false;
      assertEquals("RECOVERY_MINECRAFT_NOT_READY",assertThrows(io.github.zpkdxgames.plexonpanel.control.OperationFailure.class,()->h.manager.resolveRecovery(null,false)).code());
      assertNotNull(h.state.recoveryRequired());
    }
  }
  @Test void interruptedRestoreRequiresExplicitWorldReview() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      java.nio.file.Files.writeString(h.output.resolve("metadata/restore-journal.json"),"{\"restoreId\":\"interrupted\"}");
      assertEquals("RESTORE_RECOVERY_REQUIRES_REVIEW",assertThrows(io.github.zpkdxgames.plexonpanel.control.OperationFailure.class,()->h.manager.resolveRecovery(null,false)).code());
      assertTrue(h.backups.recoveryRequired());
      assertEquals(true,h.manager.resolveRecovery(null,true).get("resolved")); assertFalse(h.backups.recoveryRequired());
    }
  }
  @Test void promotionRehashesExistingArchiveWithoutStoppingOrMaintenanceLock() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      h.await(h.manager.startFullBackup(null,0,DestinationMode.LOCAL_ONLY,false));
      var point=h.backups.list().getFirst(); h.probes.providerConfigured=true; h.actions.clear();
      var promoted=h.manager.promoteToDrive(null,point.backupId());
      assertTrue(promoted.offsite()); assertTrue(promoted.local()); assertTrue(h.actions.isEmpty()); assertFalse(h.lock.isLocked());
      assertEquals(point.sha256(),h.backups.verify(point.backupId()).sha256());
      java.nio.file.Files.writeString(h.backups.archive(point.backupId()),"corrupt");
      assertThrows(Exception.class,()->h.manager.promoteToDrive(null,point.backupId()));
      assertTrue(h.actions.isEmpty());
    }
  }

  @Test void promotionDoesNotHoldTheMaintenanceMonitorOrQueueALateBackup() throws Exception {
    try(var h=new MaintenanceHarness(temporary)) {
      h.await(h.manager.startFullBackup(null,0,DestinationMode.LOCAL_ONLY,false));
      var point=h.backups.list().getFirst(); h.probes.providerConfigured=true; h.actions.clear();
      var entered=new java.util.concurrent.CountDownLatch(1); var release=new java.util.concurrent.CountDownLatch(1);
      h.uploadHook=()->{ entered.countDown(); try { release.await(5,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);} };
      try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
        var upload=pool.submit(()->h.manager.promoteToDrive(null,point.backupId()));
        try {
          assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
          assertEquals(true,h.manager.status().get("operationBusy"));
          var request=pool.submit(()->assertThrows(io.github.zpkdxgames.plexonpanel.control.OperationFailure.class,
              ()->h.manager.startFullBackup(null,0,DestinationMode.LOCAL_ONLY,false)));
          assertEquals("BUSY",request.get(1,java.util.concurrent.TimeUnit.SECONDS).code());
          assertTrue(h.actions.isEmpty());
        } finally { release.countDown(); }
        assertTrue(upload.get(5,java.util.concurrent.TimeUnit.SECONDS).offsite());
      }
      assertTrue(h.actions.isEmpty()); assertFalse(h.lock.isLocked());
    }
  }
}
