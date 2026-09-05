package io.github.zpkdxgames.plexonpanel.control;

import java.time.Instant;
import java.util.Map;

public record OperationResult(
    String requestId,
    String action,
    String deviceId,
    String status,
    String code,
    String message,
    Map<String, Object> data,
    String completedAt) {
  public static OperationResult result(
      String id,
      String action,
      String device,
      String status,
      String code,
      String message,
      Map<String, Object> data) {
    return new OperationResult(
        id, action, device, status, code, message, data, Instant.now().toString());
  }
}
