package io.github.zpkdxgames.plexonpanel.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Fixed-argument rclone adapter. Credentials remain host-local and output is bounded/redacted. */
public final class RcloneBackupProvider {
  public record Promotion(
      boolean uploaded,
      boolean verified,
      String remotePath,
      String verifiedAt,
      String detail) {}

  private static final int OUTPUT_LIMIT = 64 * 1024;
  private final HostConfig.BackupConfig config;

  public RcloneBackupProvider(HostConfig.BackupConfig config) {
    this.config = Objects.requireNonNull(config);
  }

  public boolean configured() {
    return config.rcloneRemote() != null && !config.rcloneRemote().isBlank();
  }

  public Map<String, Object> status() {
    return Map.of(
        "provider", configured() ? "RCLONE" : "LOCAL",
        "configured", configured(),
        "remote", configured() ? safeRemoteLabel() : "",
        "executable", configured() ? config.rcloneExecutable() : "");
  }

  public Map<String, Object> test(int timeoutSeconds) throws Exception {
    requireConfigured();
    long started = System.nanoTime();
    run(
        List.of(
            config.rcloneExecutable(),
            "lsjson",
            remoteRoot(),
            "--max-depth",
            "1",
            "--config",
            config.rcloneConfig()),
        timeoutSeconds);
    return Map.of(
        "provider",
        "RCLONE",
        "status",
        "CONNECTED",
        "remote",
        safeRemoteLabel(),
        "checkedAt",
        Instant.now().toString(),
        "durationMillis",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
  }

  public Promotion uploadAndPromote(
      Path archive,
      Path metadata,
      String jobId,
      String canonicalFilename,
      int timeoutSeconds)
      throws Exception {
    requireConfigured();
    UUID.fromString(jobId);
    validateCanonical(canonicalFilename);
    if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(archive)
        || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(metadata)) throw new IOException("Local restore point is unavailable");

    String root = remoteRoot(),
        canonicalZip = root + "/" + canonicalFilename,
        canonicalJson = root + "/" + canonicalFilename.substring(0, canonicalFilename.length() - 4) + ".json",
        stageZip = root + "/staging/PlexonCraft-" + jobId + ".zip",
        stageJson = root + "/staging/PlexonCraft-" + jobId + ".json",
        previousZip = root + "/staging/previous-" + jobId + ".zip",
        previousJson = root + "/staging/previous-" + jobId + ".json";

    copyLocalToRemote(archive, stageZip, timeoutSeconds);
    long localBytes = Files.size(archive), remoteBytes = remoteSize(stageZip, timeoutSeconds);
    if (remoteBytes != localBytes) {
      safeDelete(stageZip, timeoutSeconds);
      throw new IOException("REMOTE_VERIFY_FAILED");
    }
    copyLocalToRemote(metadata, stageJson, timeoutSeconds);

    boolean hadZip = remoteExists(canonicalZip, timeoutSeconds),
        hadJson = remoteExists(canonicalJson, timeoutSeconds);
    if (hadZip) copyRemote(canonicalZip, previousZip, timeoutSeconds);
    if (hadJson) copyRemote(canonicalJson, previousJson, timeoutSeconds);

    try {
      copyRemote(stageZip, canonicalZip, timeoutSeconds);
      if (remoteSize(canonicalZip, timeoutSeconds) != localBytes)
        throw new IOException("REMOTE_PROMOTION_FAILED");
      copyRemote(stageJson, canonicalJson, timeoutSeconds);
    } catch (Exception failure) {
      try {
        if (hadZip && remoteExists(previousZip, timeoutSeconds))
          copyRemote(previousZip, canonicalZip, timeoutSeconds);
        if (hadJson && remoteExists(previousJson, timeoutSeconds))
          copyRemote(previousJson, canonicalJson, timeoutSeconds);
      } catch (Exception ignored) {
        // Preserve original failure. Previous objects remain in staging for manual recovery.
      }
      throw failure;
    }

    safeDelete(stageZip, timeoutSeconds);
    safeDelete(stageJson, timeoutSeconds);
    safeDelete(previousZip, timeoutSeconds);
    safeDelete(previousJson, timeoutSeconds);
    return new Promotion(
        true,
        true,
        safeRemoteLabel() + "/" + canonicalFilename,
        Instant.now().toString(),
        "Remote staging verified before canonical promotion");
  }

  public Path fetchCanonical(
      Path stagingDirectory, String canonicalFilename, int timeoutSeconds) throws Exception {
    requireConfigured();
    validateCanonical(canonicalFilename);
    Files.createDirectories(stagingDirectory);
    Path partial = stagingDirectory.resolve(canonicalFilename + ".download.partial"),
        complete = stagingDirectory.resolve(canonicalFilename + ".download");
    Files.deleteIfExists(partial);
    Files.deleteIfExists(complete);
    run(
        List.of(
            config.rcloneExecutable(),
            "copyto",
            remoteRoot() + "/" + canonicalFilename,
            partial.toString(),
            "--config",
            config.rcloneConfig(),
            "--transfers",
            "1",
            "--checkers",
            "1",
            "--log-level",
            "ERROR"),
        timeoutSeconds);
    if (!Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) || Files.size(partial) == 0)
      throw new IOException("REMOTE_DOWNLOAD_FAILED");
    try (var channel = java.nio.channels.FileChannel.open(partial, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    Files.move(partial, complete, StandardCopyOption.ATOMIC_MOVE);
    return complete;
  }

  private void copyLocalToRemote(Path local, String remote, int timeout) throws Exception {
    run(
        List.of(
            config.rcloneExecutable(),
            "copyto",
            local.toString(),
            remote,
            "--config",
            config.rcloneConfig(),
            "--transfers",
            "1",
            "--checkers",
            "1",
            "--log-level",
            "ERROR"),
        timeout);
  }

  private void copyRemote(String source, String destination, int timeout) throws Exception {
    run(
        List.of(
            config.rcloneExecutable(),
            "copyto",
            source,
            destination,
            "--config",
            config.rcloneConfig(),
            "--transfers",
            "1",
            "--checkers",
            "1",
            "--log-level",
            "ERROR"),
        timeout);
  }

  private long remoteSize(String remote, int timeout) throws Exception {
    String output =
        run(
            List.of(
                config.rcloneExecutable(),
                "lsl",
                remote,
                "--config",
                config.rcloneConfig(),
                "--max-depth",
                "1"),
            timeout);
    for (String line : output.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) continue;
      String first = trimmed.split("\\s+", 2)[0];
      try {
        return Long.parseLong(first);
      } catch (NumberFormatException ignored) {
      }
    }
    throw new IOException("REMOTE_VERIFY_FAILED");
  }

  private boolean remoteExists(String remote, int timeout) throws Exception {
    ProcessResult result =
        runAllowFailure(
            List.of(
                config.rcloneExecutable(),
                "lsf",
                remote,
                "--config",
                config.rcloneConfig(),
                "--max-depth",
                "1"),
            timeout);
    return result.exitCode == 0 && !result.output.isBlank();
  }

  private void safeDelete(String remote, int timeout) {
    try {
      runAllowFailure(
          List.of(
              config.rcloneExecutable(),
              "deletefile",
              remote,
              "--config",
              config.rcloneConfig()),
          timeout);
    } catch (Exception ignored) {
    }
  }

  private String run(List<String> arguments, int timeoutSeconds) throws Exception {
    ProcessResult result = runAllowFailure(arguments, timeoutSeconds);
    if (result.exitCode != 0)
      throw new IOException("Rclone operation failed with exit code " + result.exitCode);
    return result.output;
  }

  private ProcessResult runAllowFailure(List<String> arguments, int timeoutSeconds) throws Exception {
    if (!arguments.get(0).equals(config.rcloneExecutable()))
      throw new SecurityException("RCLONE_EXECUTABLE_MISMATCH");
    Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
    CompletableFuture<String> output = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (InputStream input = process.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] bytes = new byte[2048];
                int count;
                while ((count = input.read(bytes)) >= 0) {
                  if (buffer.size() + count > OUTPUT_LIMIT) {
                    process.destroyForcibly();
                    throw new IOException("Rclone output exceeded limit");
                  }
                  buffer.write(bytes, 0, count);
                }
                output.complete(buffer.toString(StandardCharsets.UTF_8));
              } catch (Exception e) {
                output.completeExceptionally(e);
              }
            });
    try {
      if (!process.waitFor(Math.max(10, timeoutSeconds), TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("Rclone operation timed out");
      }
      String text = output.get(5, TimeUnit.SECONDS);
      return new ProcessResult(process.exitValue(), redact(text));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private String remoteRoot() {
    return config.rcloneRemote().replaceAll("/+$", "");
  }

  private String safeRemoteLabel() {
    String value = remoteRoot();
    return value.length() <= 220 ? value : value.substring(0, 220);
  }

  private void requireConfigured() {
    if (!configured()) throw new IllegalStateException("RCLONE_UNAVAILABLE");
    if (!"/usr/bin/rclone".equals(config.rcloneExecutable()))
      throw new IllegalStateException("RCLONE_CONFIG_INVALID");
    Path path = Path.of(config.rcloneConfig());
    if (!path.isAbsolute()) throw new IllegalStateException("RCLONE_CONFIG_INVALID");
  }

  private static void validateCanonical(String filename) {
    if (filename == null || !filename.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.zip"))
      throw new IllegalArgumentException("Invalid canonical restore-point filename");
  }

  private static String redact(String text) {
    if (text == null) return "";
    String cleaned = text.replaceAll("(?i)(token|secret|password|client_secret)[^\\r\\n]{0,256}", "$1=[REDACTED]");
    return cleaned.length() > OUTPUT_LIMIT ? cleaned.substring(0, OUTPUT_LIMIT) : cleaned;
  }

  private record ProcessResult(int exitCode, String output) {}
}
