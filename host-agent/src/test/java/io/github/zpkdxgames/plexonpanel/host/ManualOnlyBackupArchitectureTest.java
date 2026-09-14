package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

class ManualOnlyBackupArchitectureTest {
  @Test
  void hostMainContainsNoLiveSnapshotOrPaperCoordinationRuntime() throws Exception {
    String source = read("src/main/java/io/github/zpkdxgames/plexonpanel/host/HostMain.java",
        "host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/HostMain.java");

    assertFalse(source.contains("new BackupManager("));
    assertFalse(source.contains("scheduledBackups"));
    assertFalse(source.contains("config.backups().intervalMinutes()"));
    assertFalse(source.contains("PaperSaveLease"));
    assertFalse(source.contains("PaperMaintenanceLink"));
    assertFalse(source.contains("backup.coordination.result"));
    assertFalse(source.contains("maintenance.coordination.result"));
    assertTrue(source.contains("MANUAL_FULL_ONLY"));
    assertTrue(source.contains("maintenance.full-backup.create"));
    assertTrue(source.contains("RconMinecraftCommandChannel"));
  }

  @Test
  void paperRuntimeContainsNoBackupCoordinationTransport() throws Exception {
    String source = read("../agent/src/main/java/io/github/zpkdxgames/plexonpanel/AgentRuntime.java",
        "agent/src/main/java/io/github/zpkdxgames/plexonpanel/AgentRuntime.java");

    assertFalse(source.contains("BackupCoordinator"));
    assertFalse(source.contains("MaintenanceCoordinator"));
    assertFalse(source.contains("backup.coordination"));
    assertFalse(source.contains("maintenance.coordination"));
  }

  private static String read(String first, String second) throws Exception {
    Path one = Path.of(first);
    if (Files.isRegularFile(one)) return Files.readString(one);
    Path two = Path.of(second);
    if (Files.isRegularFile(two)) return Files.readString(two);
    throw new IllegalStateException("Missing architecture fixture: " + first + " / " + second);
  }
}
