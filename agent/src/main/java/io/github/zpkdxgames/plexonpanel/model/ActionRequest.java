package io.github.zpkdxgames.plexonpanel.model;

import com.google.gson.JsonObject;

public record ActionRequest(
    String requestId,
    String action,
    String actorId,
    String actorDisplayName,
    JsonObject parameters
) {
}
