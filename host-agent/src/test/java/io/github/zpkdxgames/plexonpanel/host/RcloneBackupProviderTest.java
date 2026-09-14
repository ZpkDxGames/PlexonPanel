package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RcloneBackupProviderTest {
  @Test
  void providerStatusTracksLastSuccessfulVerificationSeparatelyFromLatestTest() throws Exception {
    var config =
        new HostConfig.BackupConfig(
            true,
            "/tmp/plexonpanel-backups",
            List.of("world"),
            1,
            0,
            1024L,
            false,
            "/usr/bin/rclone",
            "gdrive:PlexonCraft",
            "/tmp/rclone.conf");
    var provider = new RcloneBackupProvider(config);

    assertEquals("", provider.status().get("lastSuccessfulVerificationAt"));

    var successField =
        RcloneBackupProvider.class.getDeclaredField("lastSuccessfulVerificationAt");
    successField.setAccessible(true);
    successField.set(provider, "2026-09-14T00:00:00Z");

    var testAtField = RcloneBackupProvider.class.getDeclaredField("lastTestAt");
    testAtField.setAccessible(true);
    testAtField.set(provider, "2026-09-14T01:00:00Z");

    var testStateField = RcloneBackupProvider.class.getDeclaredField("lastTestState");
    testStateField.setAccessible(true);
    testStateField.set(provider, "ERROR");

    var status = provider.status();
    assertEquals("DEGRADED", status.get("status"));
    assertEquals("2026-09-14T01:00:00Z", status.get("lastTestAt"));
    assertEquals("ERROR", status.get("lastTestState"));
    assertEquals("2026-09-14T00:00:00Z", status.get("lastSuccessfulVerificationAt"));
  }
}
