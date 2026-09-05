package io.github.zpkdxgames.plexonpanel.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class SystemdService {
  private final String service;

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

  public boolean stopped() throws Exception {
    String state = (String) status().get("state");
    return state.equals("inactive") || state.equals("failed");
  }

  public void action(String action) throws Exception {
    if (!Set.of("start", "stop", "restart").contains(action))
      throw new SecurityException("UNKNOWN_ACTION");
    run(List.of("/usr/bin/systemctl", action, "--no-ask-password", service), 180);
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
      if (process.exitValue() != 0)
        throw new IOException(
            "Configured host operation failed with exit code " + process.exitValue());
      return output;
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
  }
}
