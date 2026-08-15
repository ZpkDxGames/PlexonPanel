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
    public static final int VERSION = 1;
    public static final int MAX_ENVELOPE_BYTES = 1_048_576;
    public static final int MAX_BODY_BYTES = 524_288;
    private static final Pattern MESSAGE_TYPE = Pattern.compile("[a-z][a-z0-9_.-]{0,95}");
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
        "protocolVersion", "type", "messageId", "serverId", "timestamp", "body", "signature"
    );

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
        String signable = signable(VERSION, type, messageId, identity.serverId().toString(), timestamp, encodedBody);
        String signature = identity.signBase64Url(signable.getBytes(StandardCharsets.UTF_8));
        ProtocolEnvelope envelope = new ProtocolEnvelope(
            VERSION,
            type,
            messageId,
            identity.serverId().toString(),
            timestamp,
            encodedBody,
            signature
        );
        return gson.toJson(envelope);
    }

    public DecodedMessage decode(String json) {
        Objects.requireNonNull(json, "json");
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("Protocol envelope is too large");
        }

        JsonObject rawEnvelope = JsonParser.parseString(json).getAsJsonObject();
        if (!rawEnvelope.keySet().equals(ENVELOPE_FIELDS)) {
            throw new IllegalArgumentException("Protocol envelope fields do not match version " + VERSION);
        }
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
        JsonObject body = JsonParser.parseString(new String(decodedBody, StandardCharsets.UTF_8)).getAsJsonObject();
        return new DecodedMessage(envelope, body);
    }

    public boolean verify(ProtocolEnvelope envelope, PublicKey publicKey) {
        String signable = signable(
            envelope.protocolVersion(),
            envelope.type(),
            envelope.messageId(),
            envelope.serverId(),
            envelope.timestamp(),
            envelope.body()
        );
        return DeviceIdentity.verify(
            publicKey,
            signable.getBytes(StandardCharsets.UTF_8),
            envelope.signature()
        );
    }

    public Instant timestamp(ProtocolEnvelope envelope) {
        return Instant.parse(envelope.timestamp());
    }

    public UUID messageId(ProtocolEnvelope envelope) {
        return UUID.fromString(envelope.messageId());
    }

    private static String signable(
        int version,
        String type,
        String messageId,
        String serverId,
        String timestamp,
        String body
    ) {
        return version + "\n" + type + "\n" + messageId + "\n" + serverId + "\n" + timestamp + "\n" + body;
    }

    private static void validate(ProtocolEnvelope envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Protocol envelope is missing");
        }
        if (envelope.protocolVersion() != VERSION) {
            throw new IllegalArgumentException("Unsupported protocol version: " + envelope.protocolVersion());
        }
        validateMessageType(envelope.type());
        requireText(envelope.messageId(), "messageId", 64);
        requireText(envelope.serverId(), "serverId", 64);
        requireText(envelope.timestamp(), "timestamp", 64);
        requireText(envelope.body(), "body", MAX_BODY_BYTES * 2);
        requireText(envelope.signature(), "signature", 256);
        UUID.fromString(envelope.messageId());
        UUID.fromString(envelope.serverId());
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
