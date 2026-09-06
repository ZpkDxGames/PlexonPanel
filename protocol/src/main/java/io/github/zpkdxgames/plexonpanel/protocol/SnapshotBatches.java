package io.github.zpkdxgames.plexonpanel.protocol;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Inventory and console batches stay below signed-envelope limits, including JSON escaping. */
public final class SnapshotBatches {
  private static final Gson JSON = new Gson();

  private SnapshotBatches() {}

  public static List<Map<String, Object>> split(String field, List<?> values, int maximum) {
    String id = UUID.randomUUID().toString(), captured = Instant.now().toString();
    List<Map<String, Object>> result = new ArrayList<>();
    List<Object> batch = new ArrayList<>();
    int bytes = 0, offset = 0;
    boolean truncated = values.size() > maximum;
    for (Object item : values.subList(0, Math.min(maximum, values.size()))) {
      int size = JSON.toJson(item).getBytes(StandardCharsets.UTF_8).length + 1;
      if (size > 48000) {
        truncated = true;
        continue;
      }
      if (!batch.isEmpty() && (bytes + size > 48000 || batch.size() >= 100)) {
        result.add(body(field, batch, id, captured, offset, truncated));
        offset += batch.size();
        batch = new ArrayList<>();
        bytes = 0;
      }
      batch.add(item);
      bytes += size;
    }
    result.add(body(field, batch, id, captured, offset, truncated));
    result.getLast().put("complete", true);
    if (truncated) for (var row : result) row.put("truncated", true);
    return result;
  }

  private static Map<String, Object> body(
      String field,
      List<Object> values,
      String id,
      String captured,
      int offset,
      boolean truncated) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put(field, List.copyOf(values));
    body.put("snapshotId", id);
    body.put("capturedAt", captured);
    body.put("offset", offset);
    body.put("complete", false);
    body.put("truncated", truncated);
    return body;
  }
}
