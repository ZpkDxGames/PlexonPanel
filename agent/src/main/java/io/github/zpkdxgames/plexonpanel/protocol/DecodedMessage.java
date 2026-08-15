package io.github.zpkdxgames.plexonpanel.protocol;

import com.google.gson.JsonObject;

public record DecodedMessage(ProtocolEnvelope envelope, JsonObject body) {
}
