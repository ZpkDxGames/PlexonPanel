package io.github.zpkdxgames.plexonpanel.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ProtocolCodec {
  public static final int VERSION = 3;
  public static final int MAX_ENVELOPE_BYTES = 131_072;
  public static final int MAX_BODY_BYTES = 65_536;
  private static final Pattern MESSAGE_TYPE = Pattern.compile("[a-z][a-z0-9_.-]{0,95}");
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of("protocolVersion", "type", "messageId", "serverId", "timestamp", "body", "signature");

  private final Gson gson;
  private final Clock clock;

  public ProtocolCodec() {
    this(Clock.systemUTC());
  }

  ProtocolCodec(Clock clock) {
    this.gson = new GsonBuilder().disableHtmlEscaping().create();
    this.clock = clock;
  }

  public String encodeSigned(String type, Object body, DeviceIdentity identity) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(body, "body");
    Objects.requireNonNull(identity, "identity");
    validateMessageType(type);

    byte[] bodyBytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
    if (bodyBytes.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("Protocol body exceeds " + MAX_BODY_BYTES + " bytes");
    }

    String messageId = UUID.randomUUID().toString();
    String timestamp = clock.instant().toString();
    String encodedBody = Base64.getUrlEncoder().withoutPadding().encodeToString(bodyBytes);
    String signable =
        signable(VERSION, type, messageId, identity.serverId().toString(), timestamp, encodedBody);
    String signature = identity.signBase64Url(signable.getBytes(StandardCharsets.UTF_8));
    ProtocolEnvelope envelope =
        new ProtocolEnvelope(
            VERSION,
            type,
            messageId,
            identity.serverId().toString(),
            timestamp,
            encodedBody,
            signature);
    return gson.toJson(envelope);
  }

  public DecodedMessage decode(String json) {
    Objects.requireNonNull(json, "json");
    if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Protocol envelope is too large");
    }

    JsonObject rawEnvelope = strictObject(json);
    if (!rawEnvelope.keySet().equals(ENVELOPE_FIELDS)) {
      throw new IllegalArgumentException(
          "Protocol envelope fields do not match version " + VERSION);
    }
    if (!rawEnvelope.get("protocolVersion").isJsonPrimitive()
        || !rawEnvelope.getAsJsonPrimitive("protocolVersion").isNumber()
        || rawEnvelope
                .get("protocolVersion")
                .getAsBigDecimal()
                .compareTo(java.math.BigDecimal.valueOf(VERSION))
            != 0) throw new IllegalArgumentException("Unsupported protocol version");
    for (String field : ENVELOPE_FIELDS)
      if (!field.equals("protocolVersion")
          && (!rawEnvelope.get(field).isJsonPrimitive()
              || !rawEnvelope.getAsJsonPrimitive(field).isString()))
        throw new IllegalArgumentException("Invalid protocol field type");
    ProtocolEnvelope envelope = gson.fromJson(rawEnvelope, ProtocolEnvelope.class);
    validate(envelope);
    byte[] decodedBody;
    try {
      decodedBody = Base64.getUrlDecoder().decode(envelope.body());
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Protocol body is not valid Base64URL", error);
    }
    if (decodedBody.length > MAX_BODY_BYTES) {
      throw new IllegalArgumentException("Decoded protocol body is too large");
    }
    JsonObject body;
    try {
      body =
          strictObject(
              StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                  .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                  .decode(java.nio.ByteBuffer.wrap(decodedBody))
                  .toString());
    } catch (java.nio.charset.CharacterCodingException error) {
      throw new IllegalArgumentException("Protocol body is not valid UTF-8", error);
    }
    return new DecodedMessage(envelope, body);
  }

  private static JsonObject strictObject(String text) {
    try (var reader = new com.google.gson.stream.JsonReader(new java.io.StringReader(text))) {
      reader.setStrictness(com.google.gson.Strictness.STRICT);
      var value = JsonParser.parseReader(reader);
      if (!value.isJsonObject() || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
        throw new IllegalArgumentException("Protocol value must be one JSON object");
      return value.getAsJsonObject();
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Protocol JSON is invalid", error);
    }
  }

  public boolean verify(ProtocolEnvelope envelope, PublicKey publicKey) {
    String signable =
        signable(
            envelope.protocolVersion(),
            envelope.type(),
            envelope.messageId(),
            envelope.serverId(),
            envelope.timestamp(),
            envelope.body());
    return DeviceIdentity.verify(
        publicKey, signable.getBytes(StandardCharsets.UTF_8), envelope.signature());
  }

  public Instant timestamp(ProtocolEnvelope envelope) {
    return Instant.parse(envelope.timestamp());
  }

  public UUID messageId(ProtocolEnvelope envelope) {
    return UUID.fromString(envelope.messageId());
  }

  private static String signable(
      int version, String type, String messageId, String serverId, String timestamp, String body) {
    return version + "\n" + type + "\n" + messageId + "\n" + serverId + "\n" + timestamp + "\n"
        + body;
  }

  private static void validate(ProtocolEnvelope envelope) {
    if (envelope == null) {
      throw new IllegalArgumentException("Protocol envelope is missing");
    }
    if (envelope.protocolVersion() != VERSION) {
      throw new IllegalArgumentException(
          "Unsupported protocol version: " + envelope.protocolVersion());
    }
    validateMessageType(envelope.type());
    requireText(envelope.messageId(), "messageId", 64);
    requireText(envelope.serverId(), "serverId", 64);
    requireText(envelope.timestamp(), "timestamp", 64);
    requireText(envelope.body(), "body", MAX_BODY_BYTES * 2);
    requireText(envelope.signature(), "signature", 256);
    String uuid = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    if (!envelope.messageId().matches(uuid)
        || !envelope.serverId().matches(uuid)
        || !envelope.body().matches("[A-Za-z0-9_-]+")
        || !envelope.signature().matches("[A-Za-z0-9_-]{86}"))
      throw new IllegalArgumentException("Invalid canonical protocol field");
    Instant.parse(envelope.timestamp());
  }

  private static void requireText(String value, String name, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException("Invalid protocol field: " + name);
    }
  }

  private static void validateMessageType(String type) {
    requireText(type, "type", 96);
    if (!MESSAGE_TYPE.matcher(type).matches()) {
      throw new IllegalArgumentException("Invalid protocol field: type");
    }
  }
}
