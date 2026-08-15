package io.github.zpkdxgames.plexonpanel.model;

import java.util.List;

public record ActionResult(
    String requestId,
    String action,
    boolean success,
    String code,
    String message,
    List<String> output,
    String completedAt
) {
    public static ActionResult success(String requestId, String action, String message, List<String> output) {
        return new ActionResult(requestId, action, true, "OK", message, List.copyOf(output), java.time.Instant.now().toString());
    }

    public static ActionResult failure(String requestId, String action, String code, String message) {
        return new ActionResult(requestId, action, false, code, message, List.of(), java.time.Instant.now().toString());
    }
}
