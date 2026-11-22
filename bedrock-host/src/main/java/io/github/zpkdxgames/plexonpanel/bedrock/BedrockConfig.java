package io.github.zpkdxgames.plexonpanel.bedrock;

import io.github.zpkdxgames.plexonpanel.identity.DeviceIdentity;
import io.github.zpkdxgames.plexonpanel.identity.KeyCodec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

final class BedrockConfig {
  static final String INSTANCE = "tonimbedrock";
  static final String HOST_USER = "pph-tonimbedrock";
  static final Path BROKER_SOCKET = Path.of("/run/plexonpanel-bedrock/tonimbedrock.sock");
  static final Path DOCKER_SOCKET = Path.of("/run/docker.sock");
  private static final Set<String> BINDINGS = Set.of("serverId", "nodeId");
  private static final Set<String> HOST_PROPERTIES =
      Set.of("serverId", "nodeId", "serverName", "relayUrl", "relayPublicKey", "publicKey", "privateKey");
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,63}");

  private BedrockConfig() {}

  record Binding(UUID serverId, UUID nodeId) {
    Binding {
      uuid(serverId.toString());
      uuid(nodeId.toString());
      if (serverId.equals(nodeId)) throw new IllegalArgumentException("Server and node IDs must differ");
    }
  }

  record Host(Binding binding, String serverName, URI relayUrl, PublicKey relayKey, DeviceIdentity identity) {
    Host {
      if (!SERVER_NAME.matcher(serverName).matches()) throw new IllegalArgumentException("Invalid server name");
      validateRelay(relayUrl);
      if (!binding.serverId().equals(identity.serverId()))
        throw new IllegalArgumentException("Mismatched host identity");
      byte[] proof = "plexonpanel-bedrock-host-key-check".getBytes(StandardCharsets.UTF_8);
      if (!DeviceIdentity.verify(identity.keyPair().getPublic(), proof, identity.signBase64Url(proof)))
        throw new IllegalArgumentException("Host public key does not match its private key");
      if (relayKey.equals(identity.keyPair().getPublic()))
        throw new IllegalArgumentException("Host and relay keys must differ");
    }
  }

  static Binding loadBroker(Path file) throws IOException {
    validateFile(file, true);
    return binding(load(file, BINDINGS));
  }

  static Host loadHost(Path file, boolean localFixture) throws IOException {
    validateFile(file, !localFixture);
    Properties values = load(file, HOST_PROPERTIES);
    Binding binding = binding(values);
    URI relayUrl = URI.create(values.getProperty("relayUrl"));
    if (localFixture != "ws".equals(relayUrl.getScheme()))
      throw new IllegalArgumentException("Fixture needs loopback ws and host needs wss");
    KeyPair pair =
        new KeyPair(
            KeyCodec.decodePublic(values.getProperty("publicKey")),
            KeyCodec.decodePrivate(values.getProperty("privateKey")));
    return new Host(
        binding,
        values.getProperty("serverName"),
        relayUrl,
        KeyCodec.decodePublic(values.getProperty("relayPublicKey")),
        new DeviceIdentity(binding.serverId(), Instant.EPOCH, pair));
  }

  static DeviceIdentity initialize(UUID nodeId, URI relayUrl, String relayPublicKey, Path file)
      throws Exception {
    Binding binding = new Binding(UUID.randomUUID(), nodeId);
    DeviceIdentity identity =
        new DeviceIdentity(binding.serverId(), Instant.now(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    String canonicalRelayKey =
        Base64.getEncoder().encodeToString(KeyCodec.decodePublic(relayPublicKey).getEncoded());
    validateRelay(relayUrl);
    Set<StandardOpenOption> options = Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    var permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
    String value =
        "serverId=" + binding.serverId() + "\n"
            + "nodeId=" + binding.nodeId() + "\nserverName=TonimBedrock\n"
            + "relayUrl=" + relayUrl + "\nrelayPublicKey=" + canonicalRelayKey + "\n"
            + "publicKey=" + identity.publicKeyBase64() + "\n"
            + "privateKey=" + Base64.getEncoder().encodeToString(identity.keyPair().getPrivate().getEncoded()) + "\n";
    try (var channel = Files.newByteChannel(file, options, permissions)) {
      var bytes = StandardCharsets.US_ASCII.encode(value);
      while (bytes.hasRemaining()) channel.write(bytes);
    }
    return identity;
  }

  static UUID uuid(String input) {
    if (input == null || !UUID_PATTERN.matcher(input).matches())
      throw new IllegalArgumentException("Identity IDs must be canonical UUIDs");
    return UUID.fromString(input);
  }

  private static Binding binding(Properties values) {
    return new Binding(uuid(values.getProperty("serverId")), uuid(values.getProperty("nodeId")));
  }

  private static Properties load(Path file, Set<String> allowed) throws IOException {
    Properties values = new Properties() {
      @Override
      public synchronized Object put(Object key, Object value) {
        if (containsKey(key)) throw new IllegalArgumentException("Duplicate configuration entry");
        return super.put(key, value);
      }
    };
    try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] bytes = input.readNBytes(8193);
      if (bytes.length > 8192) throw new IOException("Configuration is too large");
      try (var reader = new java.io.StringReader(new String(bytes, StandardCharsets.UTF_8))) {
        values.load(reader);
      }
    }
    if (!values.stringPropertyNames().equals(allowed)
        || allowed.stream().anyMatch(key -> values.getProperty(key).isBlank()))
      throw new IllegalArgumentException("Configuration fields are missing or unknown");
    return values;
  }

  private static void validateFile(Path file, boolean rootRequired) throws IOException {
    var attrs = Files.readAttributes(file, java.nio.file.attribute.PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    var permissions = attrs.permissions();
    if (!attrs.isRegularFile()
        || rootRequired && !attrs.owner().getName().equals("root")
        || permissions.stream().anyMatch(p -> p.name().startsWith("OTHERS_"))
        || permissions.contains(PosixFilePermission.GROUP_WRITE))
      throw new IOException("Configuration must be private and operator-owned");
  }

  private static void validateRelay(URI relayUrl) {
    String scheme = relayUrl.getScheme();
    String host = relayUrl.getHost();
    boolean loopback = Set.of("127.0.0.1", "[::1]").contains(host == null ? "" : host);
    if (!("wss".equals(scheme) || "ws".equals(scheme) && loopback)
        || host == null
        || relayUrl.getUserInfo() != null
        || relayUrl.getRawQuery() != null
        || relayUrl.getRawFragment() != null
        || !"/v1/agent".equals(relayUrl.getRawPath())) {
      throw new IllegalArgumentException("Relay must use wss and /v1/agent, or loopback ws for a fixture");
    }
  }
}
