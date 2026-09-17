package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RcloneBackupProviderTest {
  private static final String ROOT = "gdrive:PlexonCraft";
  @TempDir Path temporary;

  @Test
  void providerStatusTracksLastSuccessfulVerificationSeparatelyFromLatestTest() throws Exception {
    var provider = new RcloneBackupProvider(config());

    var initial = provider.status();
    assertEquals("RCLONE", initial.get("provider"));
    assertEquals("CONFIGURED_UNTESTED", initial.get("status"));
    assertEquals("", initial.get("lastSuccessfulVerificationAt"));

    var successField = RcloneBackupProvider.class.getDeclaredField("lastSuccessfulVerificationAt");
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

  @Test
  void connectivityTestDoesNotPretendAFullBackupWasRemotelyVerified() {
    var provider =
        new RcloneBackupProvider(
            config(),
            (arguments, timeoutSeconds) -> new RcloneBackupProvider.ProcessResult(0, "[]"));

    var result = provider.test(15);
    var status = provider.status();

    assertEquals("CONNECTED", result.get("status"));
    assertEquals("CONNECTED", status.get("status"));
    assertNotEquals("", status.get("lastTestAt"));
    assertEquals("", status.get("lastSuccessfulVerificationAt"));
  }

  @Test
  void providerTestCommandFailureReturnsTypedSafeFailure() {
    var provider =
        new RcloneBackupProvider(
            config(),
            (arguments, timeoutSeconds) ->
                new RcloneBackupProvider.ProcessResult(
                    1, "token=super-secret-value authentication failed"));

    OperationFailure failure = assertThrows(OperationFailure.class, () -> provider.test(15));

    assertEquals("RCLONE_TEST_FAILED", failure.code());
    assertEquals("PROVIDER_TEST", failure.phase());
    assertTrue(failure.retryable());
    assertEquals("PROVIDER_TEST", failure.safeData().get("phase"));
    assertEquals(true, failure.safeData().get("retryable"));
    assertFalse(failure.toString().contains("super-secret-value"));
  }

  @Test
  void providerTestTimeoutReturnsTypedSafeFailure() {
    var provider =
        new RcloneBackupProvider(
            config(),
            (arguments, timeoutSeconds) -> {
              throw new IOException("RCLONE_COMMAND_TIMEOUT");
            });

    OperationFailure failure = assertThrows(OperationFailure.class, () -> provider.test(15));

    assertEquals("RCLONE_TEST_FAILED", failure.code());
    assertEquals("PROVIDER_TEST", failure.phase());
    assertTrue(failure.retryable());
    assertEquals("PROVIDER_TEST", failure.safeData().get("phase"));
    assertEquals(true, failure.safeData().get("retryable"));
  }

  @Test
  void uploadRetriesTransientCopyFailureAndVerifiesCanonicalObjects() throws Exception {
    Path archive = local("archive.zip", 4096);
    Path metadata = local("archive.json", 512);
    FakeRclone fake = new FakeRclone();
    fake.failNextCopies = 1;
    var provider = new RcloneBackupProvider(config(), fake);

    String jobId = UUID.randomUUID().toString();
    var result = provider.uploadAndPromote(archive, metadata, jobId, "PlexonCraft-Latest.zip", 30);

    assertTrue(result.uploaded());
    assertTrue(result.verified());
    assertEquals(4096L, fake.remote.get(ROOT + "/PlexonCraft-Latest.zip"));
    assertEquals(512L, fake.remote.get(ROOT + "/PlexonCraft-Latest.json"));
    assertTrue(fake.copyAttempts >= 3, "the failed first upload should have been retried");
  }

  @Test
  void wholeRemotePhaseHonorsOneUploadDeadline() throws Exception {
    Path archive = local("timeout.zip", 1024);
    Path metadata = local("timeout.json", 128);
    FakeRclone fake = new FakeRclone();
    fake.sleepNextCopyMillis = 1100;
    var provider = new RcloneBackupProvider(config(), fake);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                provider.uploadAndPromote(
                    archive, metadata, UUID.randomUUID().toString(), "PlexonCraft-Latest.zip", 1));

    assertEquals("RCLONE_UPLOAD_TIMEOUT", failure.getMessage());
  }

  @Test
  void interruptedUploadStopsWithoutRetrying() throws Exception {
    Path archive = local("interrupted.zip", 1024);
    Path metadata = local("interrupted.json", 128);
    FakeRclone fake = new FakeRclone();
    fake.interruptNextCopy = true;
    var provider = new RcloneBackupProvider(config(), fake);

    try {
      IOException failure =
          assertThrows(
              IOException.class,
              () ->
                  provider.uploadAndPromote(
                      archive,
                      metadata,
                      UUID.randomUUID().toString(),
                      "PlexonCraft-Latest.zip",
                      30));
      assertEquals("RCLONE_UPLOAD_INTERRUPTED", failure.getMessage());
      assertEquals(1, fake.copyAttempts);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void providerFailureNeverLeaksCommandOutput() throws Exception {
    Path archive = local("auth.zip", 1024);
    Path metadata = local("auth.json", 128);
    FakeRclone fake = new FakeRclone();
    fake.failNextCopies = 3;
    fake.failureOutput = "token=super-secret-value authentication failed";
    var provider = new RcloneBackupProvider(config(), fake);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                provider.uploadAndPromote(
                    archive, metadata, UUID.randomUUID().toString(), "PlexonCraft-Latest.zip", 30));

    assertEquals("RCLONE_COMMAND_FAILED", failure.getMessage());
    assertFalse(failure.toString().contains("super-secret-value"));
  }

  @Test
  void promotionFailureRestoresPreviousCanonicalRestorePoint() throws Exception {
    Path archive = local("promotion.zip", 4096);
    Path metadata = local("promotion.json", 512);
    FakeRclone fake = new FakeRclone();
    String canonicalZip = ROOT + "/PlexonCraft-Latest.zip";
    String canonicalJson = ROOT + "/PlexonCraft-Latest.json";
    fake.remote.put(canonicalZip, 777L);
    fake.remote.put(canonicalJson, 88L);
    fake.failDestination = canonicalZip;
    fake.failDestinationCopies = 3;
    var provider = new RcloneBackupProvider(config(), fake);

    assertThrows(
        IOException.class,
        () ->
            provider.uploadAndPromote(
                archive, metadata, UUID.randomUUID().toString(), "PlexonCraft-Latest.zip", 30));

    assertEquals(777L, fake.remote.get(canonicalZip));
    assertEquals(88L, fake.remote.get(canonicalJson));
    assertTrue(
        fake.commands.stream()
            .anyMatch(
                command ->
                    command.size() > 3
                        && command.get(1).equals("copyto")
                        && command.get(2).contains("/staging/previous-")
                        && command.get(3).equals(canonicalZip)));
  }

  private Path local(String name, int bytes) throws IOException {
    Path file = temporary.resolve(name);
    Files.write(file, new byte[bytes]);
    return file;
  }

  private static HostConfig.BackupConfig config() {
    return new HostConfig.BackupConfig(
        true,
        "/tmp/plexonpanel-backups",
        List.of("world"),
        1,
        0,
        1024L * 1024L,
        false,
        "/usr/bin/rclone",
        ROOT,
        "/tmp/rclone.conf");
  }

  private static final class FakeRclone implements RcloneBackupProvider.CommandRunner {
    final Map<String, Long> remote = new HashMap<>();
    final List<List<String>> commands = new ArrayList<>();
    int failNextCopies;
    int copyAttempts;
    long sleepNextCopyMillis;
    boolean interruptNextCopy;
    String failDestination = "";
    int failDestinationCopies;
    String failureOutput = "simulated failure";

    @Override
    public RcloneBackupProvider.ProcessResult run(List<String> arguments, int timeoutSeconds)
        throws Exception {
      commands.add(List.copyOf(arguments));
      String operation = arguments.get(1);
      return switch (operation) {
        case "copyto" -> copy(arguments);
        case "lsl" -> size(arguments.get(2));
        case "lsf" -> exists(arguments.get(2));
        case "deletefile" -> delete(arguments.get(2));
        case "lsjson" -> new RcloneBackupProvider.ProcessResult(0, "[]");
        default -> new RcloneBackupProvider.ProcessResult(1, "unsupported operation");
      };
    }

    private RcloneBackupProvider.ProcessResult copy(List<String> arguments) throws Exception {
      copyAttempts++;
      if (interruptNextCopy) {
        interruptNextCopy = false;
        throw new InterruptedException("simulated interruption");
      }
      if (sleepNextCopyMillis > 0) {
        long sleep = sleepNextCopyMillis;
        sleepNextCopyMillis = 0;
        Thread.sleep(sleep);
      }
      String source = arguments.get(2), destination = arguments.get(3);
      if (failNextCopies > 0) {
        failNextCopies--;
        return new RcloneBackupProvider.ProcessResult(1, failureOutput);
      }
      if (destination.equals(failDestination) && failDestinationCopies > 0) {
        failDestinationCopies--;
        return new RcloneBackupProvider.ProcessResult(1, failureOutput);
      }
      Long bytes = remote.get(source);
      if (bytes == null && !source.startsWith(ROOT + "/")) {
        Path local = Path.of(source);
        if (Files.isRegularFile(local)) bytes = Files.size(local);
      }
      if (bytes == null) return new RcloneBackupProvider.ProcessResult(4, "not found");
      remote.put(destination, bytes);
      return new RcloneBackupProvider.ProcessResult(0, "");
    }

    private RcloneBackupProvider.ProcessResult size(String path) {
      Long bytes = remote.get(path);
      return bytes == null
          ? new RcloneBackupProvider.ProcessResult(4, "not found")
          : new RcloneBackupProvider.ProcessResult(0, bytes + " object");
    }

    private RcloneBackupProvider.ProcessResult exists(String path) {
      return remote.containsKey(path)
          ? new RcloneBackupProvider.ProcessResult(0, "object")
          : new RcloneBackupProvider.ProcessResult(4, "not found");
    }

    private RcloneBackupProvider.ProcessResult delete(String path) {
      remote.remove(path);
      return new RcloneBackupProvider.ProcessResult(0, "");
    }
  }
}
