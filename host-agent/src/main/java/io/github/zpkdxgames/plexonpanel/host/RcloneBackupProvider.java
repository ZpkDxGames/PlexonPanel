package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Fixed-argument rclone adapter. Credentials remain host-local and output is bounded/redacted. */
public final class RcloneBackupProvider {
  public record Promotion(
      boolean uploaded,
      boolean verified,
      String remotePath,
      String verifiedAt,
      String detail) {}

  public record TransferProgress(long bytesTransferred, long totalBytes, long bytesPerSecond) {}

  @FunctionalInterface
  interface CommandRunner {
    ProcessResult run(List<String> arguments, int timeoutSeconds) throws Exception;

    default ProcessResult run(
        List<String> arguments, int timeoutSeconds, Consumer<String> outputLine) throws Exception {
      return run(arguments, timeoutSeconds);
    }
  }

  record ProcessResult(int exitCode, String output) {}

  private static final int OUTPUT_LIMIT = 64 * 1024;
  private static final int MAX_ATTEMPTS = 3;
  private static final Pattern TRANSFERRED_BYTES =
      Pattern.compile(
          "(?i)Transferred:\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTPE]?i?B)\\s*/");
  private static final Pattern TRANSFER_PERCENT = Pattern.compile(",\\s*(\\d{1,3})%");
  private static final Pattern TRANSFER_SPEED =
      Pattern.compile(
          "(?i),\\s*(?:\\d{1,3})%,\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGTPE]?i?B)/s");
  private final HostConfig.BackupConfig config;
  private final CommandRunner commandRunner;
  private final boolean enforceHostFiles;
  private volatile String lastTestAt = "";
  private volatile String lastTestState = "NOT_TESTED";
  private volatile String lastSuccessfulVerificationAt = "";

  public RcloneBackupProvider(HostConfig.BackupConfig config) {
    this.config = Objects.requireNonNull(config);
    this.commandRunner =
        new CommandRunner() {
          @Override
          public ProcessResult run(List<String> arguments, int timeoutSeconds) throws Exception {
            return executeProcess(arguments, timeoutSeconds, ignored -> {});
          }

          @Override
          public ProcessResult run(
              List<String> arguments, int timeoutSeconds, Consumer<String> outputLine)
              throws Exception {
            return executeProcess(arguments, timeoutSeconds, outputLine);
          }
        };
    this.enforceHostFiles = true;
  }

  RcloneBackupProvider(HostConfig.BackupConfig config, CommandRunner commandRunner) {
    this.config = Objects.requireNonNull(config);
    this.commandRunner = Objects.requireNonNull(commandRunner);
    this.enforceHostFiles = false;
  }

  public boolean configured() {
    return config.rcloneRemote() != null && !config.rcloneRemote().isBlank();
  }

  public Map<String, Object> status() {
    boolean configured = configured();
    String state =
        !configured
            ? "LOCAL"
            : lastTestState.equals("CONNECTED")
                ? "CONNECTED"
                : lastTestState.equals("ERROR") ? "DEGRADED" : "CONFIGURED_UNTESTED";
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("provider", configured ? "RCLONE" : "LOCAL");
    result.put("providerMode", configured ? "RCLONE" : "LOCAL");
    result.put("configured", configured);
    result.put("status", state);
    result.put("remote", configured ? safeRemoteLabel() : "");
    result.put("lastTestAt", lastTestAt);
    result.put("lastTestState", lastTestState);
    result.put("lastSuccessfulVerificationAt", lastSuccessfulVerificationAt);
    return Map.copyOf(result);
  }

  public Map<String, Object> test(int timeoutSeconds) {
    if (!configured())
      throw new OperationFailure(
          "RCLONE_UNAVAILABLE",
          "PROVIDER_TEST",
          "No off-site rclone provider is configured on the running Host.",
          false);
    long started = System.nanoTime();
    String checkedAt = Instant.now().toString();
    try {
      requireConfigured(enforceHostFiles);
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
      lastTestAt = checkedAt;
      lastTestState = "CONNECTED";
      return Map.of(
          "provider",
          "RCLONE",
          "status",
          "CONNECTED",
          "remote",
          safeRemoteLabel(),
          "checkedAt",
          checkedAt,
          "durationMillis",
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    } catch (OperationFailure failure) {
      lastTestAt = checkedAt;
      lastTestState = "ERROR";
      throw failure;
    } catch (Exception failure) {
      lastTestAt = checkedAt;
      lastTestState = "ERROR";
      throw new OperationFailure(
          "RCLONE_TEST_FAILED",
          "PROVIDER_TEST",
          "The running Host could not reach or validate the configured off-site provider.",
          true);
    }
  }

  public Promotion uploadAndPromote(
      Path archive,
      Path metadata,
      String jobId,
      String canonicalFilename,
      int timeoutSeconds)
      throws Exception {
    return uploadAndPromote(
        archive, metadata, jobId, canonicalFilename, timeoutSeconds, ignored -> {});
  }

  public Promotion uploadAndPromote(
      Path archive,
      Path metadata,
      String jobId,
      String canonicalFilename,
      int timeoutSeconds,
      Consumer<TransferProgress> progress)
      throws Exception {
    return uploadAndPromote(
        archive, metadata, jobId, canonicalFilename, timeoutSeconds,
        BackupManager.fileHash(archive), progress);
  }

  public Promotion uploadAndPromote(
      Path archive,
      Path metadata,
      String jobId,
      String canonicalFilename,
      int timeoutSeconds,
      String expectedSha256,
      Consumer<TransferProgress> progress)
      throws Exception {
    requireConfigured(enforceHostFiles);
    Objects.requireNonNull(progress);
    if (expectedSha256 == null || !expectedSha256.matches("(?i)[0-9a-f]{64}"))
      throw new IllegalArgumentException("Invalid verified archive hash");
    UUID.fromString(jobId);
    validateCanonical(canonicalFilename);
    if (timeoutSeconds < 1) throw new IllegalArgumentException("Invalid upload timeout");
    if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(archive)
        || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(metadata)) throw new IOException("Local restore point is unavailable");

    Deadline deadline = Deadline.after(timeoutSeconds);
    String root = remoteRoot(),
        canonicalZip = root + "/" + canonicalFilename,
        canonicalJson = root + "/" + canonicalFilename.substring(0, canonicalFilename.length() - 4) + ".json",
        stageZip = root + "/staging/PlexonCraft-" + jobId + ".zip",
        stageJson = root + "/staging/PlexonCraft-" + jobId + ".json",
        previousZip = root + "/staging/previous-" + jobId + ".zip",
        previousJson = root + "/staging/previous-" + jobId + ".json";

    long localBytes = Files.size(archive);
    long metadataBytes = Files.size(metadata);
    copyLocalToRemote(archive, stageZip, localBytes, deadline, progress);
    if (remoteSize(stageZip, deadline) != localBytes) {
      safeDelete(stageZip, deadline);
      throw new IOException("REMOTE_VERIFY_FAILED");
    }
    copyLocalToRemote(metadata, stageJson, metadataBytes, deadline, ignored -> {});
    if (remoteSize(stageJson, deadline) != metadataBytes) {
      safeDelete(stageJson, deadline);
      throw new IOException("REMOTE_VERIFY_FAILED");
    }

    boolean hadZip = remoteExists(canonicalZip, deadline),
        hadJson = remoteExists(canonicalJson, deadline);
    if (hadZip) copyRemote(canonicalZip, previousZip, deadline);
    if (hadJson) copyRemote(canonicalJson, previousJson, deadline);

    try {
      copyRemote(stageZip, canonicalZip, deadline);
      if (remoteSize(canonicalZip, deadline) != localBytes)
        throw new IOException("REMOTE_PROMOTION_FAILED");
      copyRemote(stageJson, canonicalJson, deadline);
      if (remoteSize(canonicalJson, deadline) != metadataBytes)
        throw new IOException("REMOTE_PROMOTION_FAILED");
      // Google Drive exposes SHA-256 for new binary uploads. Never discard the local
      // ZIP when the promoted object has no hash or its content differs.
      if (!expectedSha256.equalsIgnoreCase(remoteSha256(canonicalZip, deadline)))
        throw new IOException("REMOTE_VERIFY_FAILED");
    } catch (Exception failure) {
      rollbackCanonical(
          canonicalZip,
          canonicalJson,
          previousZip,
          previousJson,
          hadZip,
          hadJson,
          deadline);
      throw failure;
    }

    safeDelete(stageZip, deadline);
    safeDelete(stageJson, deadline);
    safeDelete(previousZip, deadline);
    safeDelete(previousJson, deadline);
    String verifiedAt = Instant.now().toString();
    lastTestAt = verifiedAt;
    lastTestState = "CONNECTED";
    lastSuccessfulVerificationAt = verifiedAt;
    return new Promotion(
        true,
        true,
        safeRemoteLabel() + "/" + canonicalFilename,
        verifiedAt,
        "Remote archive and metadata verified before canonical promotion");
  }

  public Path fetchCanonical(
      Path stagingDirectory, String canonicalFilename, int timeoutSeconds) throws Exception {
    requireConfigured(enforceHostFiles);
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

  private void rollbackCanonical(
      String canonicalZip,
      String canonicalJson,
      String previousZip,
      String previousJson,
      boolean hadZip,
      boolean hadJson,
      Deadline deadline) {
    try {
      if (hadZip && remoteExists(previousZip, deadline)) copyRemote(previousZip, canonicalZip, deadline);
      else if (!hadZip) safeDelete(canonicalZip, deadline);
      if (hadJson && remoteExists(previousJson, deadline)) copyRemote(previousJson, canonicalJson, deadline);
      else if (!hadJson) safeDelete(canonicalJson, deadline);
    } catch (Exception ignored) {
      // Preserve the primary failure. Previous verified objects remain staged for operator recovery.
    }
  }

  private void copyLocalToRemote(
      Path local,
      String remote,
      long totalBytes,
      Deadline deadline,
      Consumer<TransferProgress> progress)
      throws Exception {
    retry(
        deadline,
        timeout -> {
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
                  "--stats",
                  "1s",
                  "--stats-one-line",
                  "--stats-log-level",
                  "NOTICE",
                  "--stats-unit",
                  "bytes",
                  "--log-level",
                  "NOTICE"),
              timeout,
              line ->
                  parseProgressLine(line, totalBytes)
                      .ifPresent(
                          update -> {
                            try {
                              progress.accept(update);
                            } catch (RuntimeException ignored) {
                              // Telemetry must never interrupt or invalidate a backup transfer.
                            }
                          }));
          return null;
        });
  }

  private void copyRemote(String source, String destination, Deadline deadline) throws Exception {
    retry(
        deadline,
        timeout -> {
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
          return null;
        });
  }

  private long remoteSize(String remote, Deadline deadline) throws Exception {
    return retry(
        deadline,
        timeout -> {
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
        });
  }

  private String remoteSha256(String remote, Deadline deadline) throws Exception {
    String output =
        run(
            List.of(
                config.rcloneExecutable(), "hashsum", "SHA-256", remote,
                "--config", config.rcloneConfig()),
            deadline.remainingSeconds());
    String[] lines = output.strip().split("\\R");
    if (lines.length != 1
        || !lines[0].matches("(?i)[0-9a-f]{64}\\s+\\*?" +
            Pattern.quote(Path.of(remote.substring(remote.lastIndexOf('/') + 1)).toString())))
      throw new IOException("REMOTE_VERIFY_FAILED");
    return lines[0].substring(0, 64);
  }

  private boolean remoteExists(String remote, Deadline deadline) throws Exception {
    int timeout = deadline.remainingSeconds();
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
    if (result.exitCode == 0) return !result.output.isBlank();
    if (result.exitCode == 3 || result.exitCode == 4) return false;
    throw new IOException("RCLONE_COMMAND_FAILED");
  }

  private void safeDelete(String remote, Deadline deadline) {
    int timeout = deadline.cleanupSeconds();
    if (timeout <= 0) return;
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

  private <T> T retry(Deadline deadline, TimedOperation<T> operation) throws Exception {
    Exception last = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      int timeout = deadline.remainingSeconds();
      try {
        return operation.run(timeout);
      } catch (Exception failure) {
        last = normalizeInterruption(failure);
        if (terminal(last) || attempt == MAX_ATTEMPTS) throw last;
        long pauseMillis = Math.min(1000L, 250L * attempt);
        if (!deadline.canPause(pauseMillis)) throw new IOException("RCLONE_UPLOAD_TIMEOUT", last);
        try {
          Thread.sleep(pauseMillis);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("RCLONE_UPLOAD_INTERRUPTED", interrupted);
        }
      }
    }
    throw last == null ? new IOException("REMOTE_UPLOAD_FAILED") : last;
  }

  private String run(List<String> arguments, int timeoutSeconds) throws Exception {
    ProcessResult result = runAllowFailure(arguments, timeoutSeconds);
    if (result.exitCode != 0) throw new IOException("RCLONE_COMMAND_FAILED");
    return result.output;
  }

  private String run(
      List<String> arguments, int timeoutSeconds, Consumer<String> outputLine) throws Exception {
    ProcessResult result = runAllowFailure(arguments, timeoutSeconds, outputLine);
    if (result.exitCode != 0) throw new IOException("RCLONE_COMMAND_FAILED");
    return result.output;
  }

  private ProcessResult runAllowFailure(List<String> arguments, int timeoutSeconds) throws Exception {
    return runAllowFailure(arguments, timeoutSeconds, ignored -> {});
  }

  private ProcessResult runAllowFailure(
      List<String> arguments, int timeoutSeconds, Consumer<String> outputLine) throws Exception {
    if (arguments.isEmpty() || !arguments.get(0).equals(config.rcloneExecutable()))
      throw new SecurityException("RCLONE_EXECUTABLE_MISMATCH");
    if (timeoutSeconds < 1) throw new IOException("RCLONE_UPLOAD_TIMEOUT");
    try {
      ProcessResult result =
          commandRunner.run(List.copyOf(arguments), timeoutSeconds, outputLine);
      return new ProcessResult(result.exitCode, redact(result.output));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("RCLONE_UPLOAD_INTERRUPTED", interrupted);
    }
  }

  private ProcessResult executeProcess(
      List<String> arguments, int timeoutSeconds, Consumer<String> outputLine) throws Exception {
    Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
    CompletableFuture<String> output = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder tail = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                  try {
                    outputLine.accept(line);
                  } catch (RuntimeException ignored) {
                    // A failed observer must not terminate the bounded rclone subprocess.
                  }
                  appendTail(tail, line);
                }
                output.complete(tail.toString());
              } catch (Exception e) {
                output.completeExceptionally(e);
              }
            });
    try {
      if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("RCLONE_COMMAND_TIMEOUT");
      }
      String text = output.get(Math.min(5, timeoutSeconds), TimeUnit.SECONDS);
      return new ProcessResult(process.exitValue(), text);
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  static Optional<TransferProgress> parseProgressLine(String line, long totalBytes) {
    if (line == null || totalBytes <= 0) return Optional.empty();
    Matcher transferred = TRANSFERRED_BYTES.matcher(line);
    if (!transferred.find()) return Optional.empty();
    long bytes = parseByteSize(transferred.group(1), transferred.group(2));
    Matcher percent = TRANSFER_PERCENT.matcher(line.substring(transferred.end()));
    if (percent.find() && Integer.parseInt(percent.group(1)) >= 100) bytes = totalBytes;
    bytes = Math.max(0L, Math.min(totalBytes, bytes));
    long bytesPerSecond = 0L;
    Matcher speed = TRANSFER_SPEED.matcher(line);
    if (speed.find()) bytesPerSecond = parseByteSize(speed.group(1), speed.group(2));
    return Optional.of(new TransferProgress(bytes, totalBytes, Math.max(0L, bytesPerSecond)));
  }

  private static long parseByteSize(String amount, String unit) {
    double value;
    try {
      value = Double.parseDouble(amount);
    } catch (NumberFormatException ignored) {
      return 0L;
    }
    String normalized = unit.toUpperCase(Locale.ROOT);
    int exponent =
        switch (normalized.charAt(0)) {
          case 'K' -> 1;
          case 'M' -> 2;
          case 'G' -> 3;
          case 'T' -> 4;
          case 'P' -> 5;
          case 'E' -> 6;
          default -> 0;
        };
    double base = normalized.contains("I") ? 1024.0d : 1000.0d;
    double bytes = value * Math.pow(base, exponent);
    if (!Double.isFinite(bytes) || bytes >= Long.MAX_VALUE) return Long.MAX_VALUE;
    return Math.max(0L, Math.round(bytes));
  }

  private static void appendTail(StringBuilder tail, String line) {
    tail.append(line).append('\n');
    if (tail.length() > OUTPUT_LIMIT) tail.delete(0, tail.length() - OUTPUT_LIMIT);
  }

  private String remoteRoot() {
    return config.rcloneRemote().replaceAll("/+$", "");
  }

  private String safeRemoteLabel() {
    String value = remoteRoot();
    return value.length() <= 220 ? value : value.substring(0, 220);
  }

  private void requireConfigured(boolean checkHostFiles) throws IOException {
    if (!configured()) throw new IllegalStateException("RCLONE_UNAVAILABLE");
    if (!"/usr/bin/rclone".equals(config.rcloneExecutable()))
      throw new IllegalStateException("RCLONE_CONFIG_INVALID");
    if (config.rcloneConfig() == null) throw new IllegalStateException("RCLONE_CONFIG_INVALID");
    Path path = Path.of(config.rcloneConfig());
    if (!path.isAbsolute()) throw new IllegalStateException("RCLONE_CONFIG_INVALID");
    if (!checkHostFiles) return;

    Path executable = Path.of(config.rcloneExecutable());
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(executable)
        || !Files.isExecutable(executable)) throw new IOException("RCLONE_EXECUTABLE_UNAVAILABLE");
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(path)
        || !Files.isReadable(path)) throw new IOException("RCLONE_CONFIG_UNREADABLE");
  }

  private static void validateCanonical(String filename) {
    if (filename == null || !filename.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.zip"))
      throw new IllegalArgumentException("Invalid canonical restore-point filename");
  }

  private static Exception normalizeInterruption(Exception failure) {
    if (failure instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      return new IOException("RCLONE_UPLOAD_INTERRUPTED", failure);
    }
    return failure;
  }

  private static boolean terminal(Exception failure) {
    String message = failure.getMessage();
    return Thread.currentThread().isInterrupted()
        || "RCLONE_UPLOAD_TIMEOUT".equals(message)
        || "RCLONE_UPLOAD_INTERRUPTED".equals(message)
        || "RCLONE_CONFIG_INVALID".equals(message)
        || "RCLONE_EXECUTABLE_UNAVAILABLE".equals(message)
        || "RCLONE_CONFIG_UNREADABLE".equals(message);
  }

  private static String redact(String text) {
    if (text == null) return "";
    String cleaned =
        text.replaceAll(
            "(?i)(token|secret|password|client_secret)[^\\r\\n]{0,256}", "$1=[REDACTED]");
    return cleaned.length() > OUTPUT_LIMIT ? cleaned.substring(0, OUTPUT_LIMIT) : cleaned;
  }

  private interface TimedOperation<T> {
    T run(int timeoutSeconds) throws Exception;
  }

  private static final class Deadline {
    private final long expiresAtNanos;

    private Deadline(long expiresAtNanos) {
      this.expiresAtNanos = expiresAtNanos;
    }

    static Deadline after(int timeoutSeconds) {
      long now = System.nanoTime();
      long duration = TimeUnit.SECONDS.toNanos(timeoutSeconds);
      long expires;
      try {
        expires = Math.addExact(now, duration);
      } catch (ArithmeticException overflow) {
        expires = Long.MAX_VALUE;
      }
      return new Deadline(expires);
    }

    int remainingSeconds() throws IOException {
      long remaining = expiresAtNanos - System.nanoTime();
      if (remaining <= 0) throw new IOException("RCLONE_UPLOAD_TIMEOUT");
      long seconds = TimeUnit.NANOSECONDS.toSeconds(remaining);
      if (seconds == 0) return 1;
      return (int) Math.min(Integer.MAX_VALUE, seconds);
    }

    boolean canPause(long millis) {
      long remaining = expiresAtNanos - System.nanoTime();
      return remaining > TimeUnit.MILLISECONDS.toNanos(millis);
    }

    int cleanupSeconds() {
      long remaining = expiresAtNanos - System.nanoTime();
      if (remaining <= 0) return 0;
      long seconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining));
      return (int) Math.min(5, seconds);
    }
  }
}
