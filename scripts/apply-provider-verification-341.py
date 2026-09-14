from pathlib import Path

path = Path("host-agent/src/main/java/io/github/zpkdxgames/plexonpanel/host/RcloneBackupProvider.java")
text = path.read_text()

def swap(old: str, new: str, label: str) -> None:
    global text
    if new in text:
        print(label + ": already applied")
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    text = text.replace(old, new)
    print(label + ": applied")

swap(
    '  private volatile String lastTestAt = "";\n  private volatile String lastTestState = "NOT_TESTED";',
    '  private volatile String lastTestAt = "";\n  private volatile String lastTestState = "NOT_TESTED";\n  private volatile String lastSuccessfulVerificationAt = "";',
    "provider success timestamp state",
)
swap(
    '    result.put("lastTestAt", lastTestAt);\n    result.put("lastTestState", lastTestState);',
    '    result.put("lastTestAt", lastTestAt);\n    result.put("lastTestState", lastTestState);\n    result.put("lastSuccessfulVerificationAt", lastSuccessfulVerificationAt);',
    "provider status success timestamp",
)
swap(
    '      lastTestAt = checkedAt;\n      lastTestState = "CONNECTED";',
    '      lastTestAt = checkedAt;\n      lastTestState = "CONNECTED";\n      lastSuccessfulVerificationAt = checkedAt;',
    "provider test success timestamp",
)
swap(
    '''    return new Promotion(
        true,
        true,
        safeRemoteLabel() + "/" + canonicalFilename,
        Instant.now().toString(),
        "Remote staging verified before canonical promotion");''',
    '''    String verifiedAt = Instant.now().toString();
    lastSuccessfulVerificationAt = verifiedAt;
    return new Promotion(
        true,
        true,
        safeRemoteLabel() + "/" + canonicalFilename,
        verifiedAt,
        "Remote staging verified before canonical promotion");''',
    "provider upload verification timestamp",
)
path.write_text(text)

test = Path("host-agent/src/test/java/io/github/zpkdxgames/plexonpanel/host/RcloneBackupProviderTest.java")
test.write_text('''package io.github.zpkdxgames.plexonpanel.host;

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
''')
print("provider verification regression test written")
