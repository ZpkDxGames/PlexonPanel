package io.github.zpkdxgames.plexonpanel.protocol;

import com.google.gson.*;
import java.util.UUID;

/** Signed body binding prevents replay across reconnects and keeps relay attachments bounded. */
public final class AgentSession {
  private final Gson json = new Gson();
  private String nonce = UUID.randomUUID().toString();
  private long sequence;

  public synchronized void reset() {
    nonce = UUID.randomUUID().toString();
    sequence = 0;
  }

  public synchronized String nonce() {
    return nonce;
  }

  public synchronized JsonObject stamp(Object value) {
    if (sequence >= 9007199254740991L)
      throw new IllegalStateException("Reconnect before sequence exhaustion");
    JsonObject body = json.toJsonTree(value).getAsJsonObject();
    body.addProperty("_session", nonce);
    body.addProperty("_sequence", ++sequence);
    return body;
  }
}
