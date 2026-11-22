package io.github.zpkdxgames.plexonpanel.bedrock;

import com.google.gson.JsonObject;
import io.github.zpkdxgames.plexonpanel.identity.FleetContract;
import io.github.zpkdxgames.plexonpanel.protocol.AgentSession;
import io.github.zpkdxgames.plexonpanel.protocol.ProtocolCodec;
import io.github.zpkdxgames.plexonpanel.protocol.ReplayGuard;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Opt-in Protocol 3 extension, using the existing envelope and session/replay guards. */
final class BedrockRelayProtocol {
  static final String CONTRACT = "bedrock-host-v1";
  static final String KIND = "BEDROCK_HOST";
  static final List<String> CAPABILITIES = List.of("bedrock.status.view", "bedrock.logs.view");
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{22,128}");

  private enum Phase { NEW, HELLO, CHALLENGED, AUTHENTICATED }

  private final BedrockConfig.Host config;
  private final ProtocolCodec codec = new ProtocolCodec();
  private final ReplayGuard replay = new ReplayGuard(Duration.ofSeconds(30), 8192);
  private final AgentSession session = new AgentSession();
  private Phase phase = Phase.NEW;
  private String nonce;

  BedrockRelayProtocol(BedrockConfig.Host config) {
    this.config = config;
  }

  boolean authenticated() {
    return phase == Phase.AUTHENTICATED;
  }

  String hello() {
    require(phase == Phase.NEW);
    JsonObject body = identity(new JsonObject());
    body.addProperty("agentKind", KIND);
    body.addProperty("agentName", "PlexonPanel Bedrock Host");
    var version = BedrockRelayProtocol.class.getPackage().getImplementationVersion();
    body.addProperty("pluginVersion", version == null ? "development" : version);
    body.addProperty("protocolVersion", ProtocolCodec.VERSION);
    body.addProperty("fleetContract", FleetContract.ID);
    body.addProperty("serverName", config.serverName());
    body.addProperty("publicKey", config.identity().publicKeyBase64());
    body.addProperty("publicKeyFingerprint", config.identity().fingerprint());
    body.add("capabilities", UnixFrames.JSON.toJsonTree(CAPABILITIES));
    phase = Phase.HELLO;
    return signed("agent.hello", body);
  }

  /** Returns a challenge response when required. No inbound type can request an operation. */
  String receive(String wire) {
    var message = codec.decode(wire);
    var envelope = message.envelope();
    JsonObject body = message.body();
    require(envelope.serverId().equals(config.binding().serverId().toString()));
    require(codec.verify(envelope, config.relayKey()));
    require(CONTRACT.equals(string(body, "bedrockContract")));
    require(session.nonce().equals(string(body, "_session")));
    require(config.binding().nodeId().toString().equals(string(body, "nodeId")));
    require(BedrockConfig.INSTANCE.equals(string(body, "instanceKey")));
    require(replay.accept(codec.messageId(envelope), codec.timestamp(envelope)));
    return switch (envelope.type()) {
      case "gateway.challenge" -> {
        require(phase == Phase.HELLO);
        nonce = string(body, "nonce");
        require(nonce != null && NONCE.matcher(nonce).matches());
        byte[] value = ("challenge:" + nonce).getBytes(StandardCharsets.UTF_8);
        phase = Phase.CHALLENGED;
        yield signed(
            "agent.challenge_response",
            identity(UnixFrames.JSON.toJsonTree(
                Map.of("nonce", nonce, "proof", config.identity().signBase64Url(value))).getAsJsonObject()));
      }
      case "gateway.authenticated" -> {
        require(phase == Phase.CHALLENGED && nonce.equals(string(body, "nonce")));
        var version = body.get("protocolVersion");
        require(version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isNumber()
            && Integer.toString(ProtocolCodec.VERSION).equals(version.getAsString()));
        phase = Phase.AUTHENTICATED;
        yield null;
      }
      case "gateway.snapshot_request" -> {
        require(authenticated());
        // Fixed-cadence snapshots will supply the latest readings without amplifying requests.
        yield null;
      }
      default -> throw new SecurityException("Unsupported Bedrock relay message");
    };
  }

  String reading(BedrockBroker.Operation operation, JsonObject data) {
    require(authenticated());
    String type = switch (operation) {
      case STATUS -> "bedrock.status";
      case LOGS -> "bedrock.logs";
    };
    return signed(type, identity(data.deepCopy()));
  }

  private JsonObject identity(JsonObject body) {
    body.addProperty("bedrockContract", CONTRACT);
    body.addProperty("nodeId", config.binding().nodeId().toString());
    body.addProperty("instanceKey", BedrockConfig.INSTANCE);
    body.addProperty("edition", "BEDROCK");
    body.addProperty("runtime", "DOCKER");
    return body;
  }

  private String signed(String type, JsonObject body) {
    JsonObject binding = session.stamp(Map.of());
    binding.entrySet().forEach(field -> body.add(field.getKey(), field.getValue()));
    return codec.encodeSigned(type, body, config.identity());
  }

  private static String string(JsonObject body, String name) {
    var value = body.get(name);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : null;
  }

  private static void require(boolean valid) {
    if (!valid) throw new SecurityException("Bedrock relay authentication or session check failed");
  }
}
