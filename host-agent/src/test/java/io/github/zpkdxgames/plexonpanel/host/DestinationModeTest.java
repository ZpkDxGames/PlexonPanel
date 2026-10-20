package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import org.junit.jupiter.api.Test;

class DestinationModeTest {
  @Test void destinationMatrix() {
    for(var mode:DestinationMode.values()) for(var provider:FullBackupPreflight.ProviderState.values()) for(boolean fallback:new boolean[]{false,true}) {
      var plan=mode.plan(provider,fallback);
      boolean remote=mode!=DestinationMode.LOCAL_ONLY && (mode==DestinationMode.DRIVE_AND_LOCAL || provider==FullBackupPreflight.ProviderState.CONNECTED || !fallback);
      assertEquals(remote,plan.uploadOffsite(),mode+"/"+provider+"/"+fallback);
      assertEquals(remote,plan.requireRemote());
      assertEquals(remote?"DRIVE_AND_LOCAL":"LOCAL_ONLY",plan.effective());
      assertEquals(mode,plan.requested());
      assertEquals(mode==DestinationMode.AUTO && !remote?"PROVIDER_"+provider.name():null,plan.fallbackReason());
    }
  }
  @Test void parsePreservesDefaultsAndRejectsUnknownModes() {
    assertEquals(DestinationMode.AUTO,DestinationMode.parse(null));
    assertEquals(DestinationMode.AUTO,DestinationMode.parse(""));
    assertEquals(DestinationMode.LOCAL_ONLY,DestinationMode.parse(" local_only "));
    assertEquals("INVALID_DESTINATION_MODE",assertThrows(OperationFailure.class,()->DestinationMode.parse("nope")).code());
  }
  @Test void realStageContractAndOffsiteRange() {
    assertEquals(14,BackupStage.values().length);
    assertEquals(6,BackupStage.STOP_SERVICE.ordinal()+1);
    assertEquals("Stop service",BackupStage.STOP_SERVICE.label);
    assertEquals(java.util.List.of(12,13),BackupStage.offsiteStageOrdinals());
  }
}
