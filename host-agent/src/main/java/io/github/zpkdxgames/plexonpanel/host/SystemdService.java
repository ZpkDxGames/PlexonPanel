package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.control.OperationFailure;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongPredicate;

public final class SystemdService {
  private final String service;

  private static final class CommandFailure extends IOException {
    final int exitCode;
    final String output;

    CommandFailure(int exitCode, String output) {
      super("Configured host operation failed");
      this.exitCode = exitCode;
      this.output = output == null ? "" : output;
    }
  }

  public SystemdService(String service) {
    if (!service.matches("[A-Za-z0-9][A-Za-z0-9_.@-]{0,90}\\.service"))
      throw new IllegalArgumentException("Invalid service");
    this.service = service;
  }

  public Map<String, Object> status() throws Exception {
    String output =
        run(
            List.of(
                "/usr/bin/systemctl",
                "show",
                "--no-pager",
                "--property=ActiveState,SubState,MainPID",
                service),
            10);
    Map<String, String> values = new HashMap<>();
    for (String line : output.split("\n")) {
      int index = line.indexOf('=');
      if (index > 0) values.put(line.substring(0, index), line.substring(index + 1));
    }
    String state = values.getOrDefault("ActiveState", "unknown");
    long pid;
    try {
      pid = Long.parseLong(values.getOrDefault("MainPID", "0"));
    } catch (NumberFormatException e) {
      pid = 0;
    }
    return Map.of(
        "state",
        state,
        "subState",
        values.getOrDefault("SubState", "unknown"),
        "pid",
        pid,
        "service",
        service,
        "hostConnected",
        true);
  }

  /**
   * A cold-backup-safe stop proof. The unit must be inactive/failed and systemd's MainPID must be
   * zero or no longer alive. A stale but still-live PID fails closed.
   */
  public boolean stopped() throws Exception {
    return stopped(status(), SystemdService::processAlive);
  }

  static boolean stopped(Map<String, Object> status, LongPredicate pidAlive) {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(pidAlive, "pidAlive");
    String state = String.valueOf(status.getOrDefault("state", "unknown"));
    if (!state.equals("inactive") && !state.equals("failed")) return false;
    Object rawPid = status.getOrDefault("pid", 0L);
    long pid = rawPid instanceof Number number ? number.longValue() : 0L;
    return pid <= 0 || !pidAlive.test(pid);
  }

  private static boolean processAlive(long pid) {
    return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
  }

  public void action(String action) throws Exception {
    if (!Set.of("start", "stop", "restart").contains(action))
      throw new SecurityException("UNKNOWN_ACTION");
    try {
      run(List.of("/usr/bin/systemctl", action, "--no-ask-password", service), 180);
    } catch (CommandFailure failure) {
      String output = failure.output.toLowerCase(Locale.ROOT);
      if (output.contains("access denied")
          || output.contains("not authorized")
          || output.contains("authentication is required")
          || output.contains("interactive authentication required")
          || output.contains("permission denied"))
        throw new OperationFailure(
            "SYSTEMD_PERMISSION_DENIED",
            "SYSTEMD_CONTROL",
            "The Host is not authorized to control the configured Paper service.",
            false);
      throw new OperationFailure(
          "SYSTEMD_OPERATION_FAILED",
          "SYSTEMD_CONTROL",
          "The configured Paper service operation failed on the Host.",
          true);
    }
  }

  static String run(List<String> arguments, int seconds) throws Exception {
    Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
    CompletableFuture<String> reader = new CompletableFuture<>();
    Thread.ofVirtual()
        .start(
            () -> {
              try (var input = process.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                  if (out.size() + count > 16384) {
                    process.destroyForcibly();
                    throw new IOException("Child output limit");
                  }
                  out.write(buffer, 0, count);
                }
                reader.complete(out.toString(StandardCharsets.UTF_8));
              } catch (Exception e) {
                reader.completeExceptionally(e);
              }
            });
    try {
      if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException("Configured host operation timed out");
      }
      String output = reader.get(5, TimeUnit.SECONDS);
      if (process.exitValue() != 0) throw new CommandFailure(process.exitValue(), output);
      return output;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
