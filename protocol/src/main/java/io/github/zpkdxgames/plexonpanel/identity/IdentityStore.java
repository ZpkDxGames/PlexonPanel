package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

public final class IdentityStore {
  private static final Set<PosixFilePermission> OWNER_ONLY =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final Path identityDirectory;
  private final Path metadataPath;
  private final Path privateKeyPath;
  private final Clock clock;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

  public IdentityStore(Path dataDirectory) {
    this(dataDirectory, Clock.systemUTC());
  }

  IdentityStore(Path dataDirectory, Clock clock) {
    this.identityDirectory = dataDirectory.resolve("identity");
    this.metadataPath = identityDirectory.resolve("device.json");
    this.privateKeyPath = identityDirectory.resolve("device.key");
    this.clock = clock;
  }

  public synchronized DeviceIdentity loadOrCreate() throws IOException {
    return loadOrCreate(null);
  }

  /** Inspect existing public UUID without creating keys or changing file permissions. */
  public synchronized void validateRegisteredServerId(UUID requiredServerId) throws IOException {
    FleetIdentity.parseUuid(requiredServerId.toString(), "serverId");
    validatePaths();
    boolean metadataExists = Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS);
    boolean keyExists = Files.exists(privateKeyPath, LinkOption.NOFOLLOW_LINKS);
    if (metadataExists != keyExists) throw new IOException("IDENTITY_STATE_INCOMPLETE");
    if (!metadataExists) return;
    try {
      IdentityMetadata metadata = gson.fromJson(Files.readString(metadataPath), IdentityMetadata.class);
      if (metadata == null || !"Ed25519".equals(metadata.algorithm())
          || !requiredServerId.equals(FleetIdentity.parseUuid(metadata.serverId(), "serverId")))
        throw new IOException("REGISTERED_SERVER_IDENTITY_MISMATCH");
    } catch (RuntimeException invalid) { throw new IOException("IDENTITY_METADATA_INVALID"); }
  }

  /** A new Paper identity may use its pre-registered UUID; existing identity is never rewritten. */
  public synchronized DeviceIdentity loadOrCreate(UUID requiredServerId) throws IOException {
    if (requiredServerId != null) FleetIdentity.parseUuid(requiredServerId.toString(), "serverId");
    validatePaths();
    if (Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS) && Files.exists(privateKeyPath, LinkOption.NOFOLLOW_LINKS)) {
      DeviceIdentity existing = load();
      if (requiredServerId != null && !requiredServerId.equals(existing.serverId()))
        throw new IOException("REGISTERED_SERVER_IDENTITY_MISMATCH");
      return existing;
    }
    if (Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS) != Files.exists(privateKeyPath, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "Incomplete PlexonPanel identity; both device.json and device.key are required");
    }
    return create(requiredServerId);
  }

  public synchronized DeviceIdentity rotate() throws IOException {
    validatePaths();
    // Generate the replacement before overwriting the existing identity; do
    // not pre-delete a working key pair.
    return create(null);
  }

  private DeviceIdentity create(UUID requiredServerId) throws IOException {
    try {
      Files.createDirectories(identityDirectory);
      KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
      KeyPair keyPair = generator.generateKeyPair();
      DeviceIdentity identity = new DeviceIdentity(requiredServerId == null ? UUID.randomUUID() : requiredServerId, clock.instant(), keyPair);
      IdentityMetadata metadata =
          new IdentityMetadata(
              identity.serverId().toString(),
              identity.createdAt().toString(),
              "Ed25519",
              identity.publicKeyBase64(),
              identity.fingerprint());
      AtomicFiles.writeUtf8(metadataPath, gson.toJson(metadata) + System.lineSeparator());
      AtomicFiles.writeUtf8(
          privateKeyPath,
          Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())
              + System.lineSeparator());
      restrictPrivateKeyPermissions();
      return identity;
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      throw new IOException("Unable to generate PlexonPanel identity", error);
    }
  }

  private DeviceIdentity load() throws IOException {
    try {
      IdentityMetadata metadata =
          gson.fromJson(
              Files.readString(metadataPath, StandardCharsets.UTF_8), IdentityMetadata.class);
      if (metadata == null || !"Ed25519".equals(metadata.algorithm())) {
        throw new IOException("Unsupported or missing identity algorithm");
      }
      var publicKey = KeyCodec.decodePublic(metadata.publicKey());
      var privateKey =
          KeyCodec.decodePrivate(Files.readString(privateKeyPath, StandardCharsets.UTF_8));
      DeviceIdentity identity =
          new DeviceIdentity(
              FleetIdentity.parseUuid(metadata.serverId(), "serverId"),
              Instant.parse(metadata.createdAt()),
              new KeyPair(publicKey, privateKey));
      if (!identity.fingerprint().equals(metadata.fingerprint())) {
        throw new IOException("PlexonPanel identity fingerprint mismatch");
      }
      var probe = "plexonpanel-private-key-consistency".getBytes(StandardCharsets.UTF_8);
      var verifier = java.security.Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(probe);
      if (!verifier.verify(Base64.getUrlDecoder().decode(identity.signBase64Url(probe))))
        throw new IOException("PlexonPanel identity key pair mismatch");
      restrictPrivateKeyPermissions();
      return identity;
    } catch (IOException error) {
      throw error;
    } catch (Exception error) {
      // Parser/key exceptions may contain supplied private values. Keep diagnostics value-free.
      throw new IOException("Unable to load PlexonPanel identity");
    }
  }

  private void validatePaths() throws IOException {
    for (Path path = identityDirectory.toAbsolutePath(); path != null; path = path.getParent())
      if (Files.isSymbolicLink(path)) throw new IOException("Identity directory may not be a symlink");
    for (Path file : java.util.List.of(metadataPath, privateKeyPath))
      if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
          && (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
              || Files.size(file) > 8192))
        throw new IOException("Invalid identity state file");
  }

  private void restrictPrivateKeyPermissions() throws IOException {
    try {
      Files.setPosixFilePermissions(privateKeyPath, OWNER_ONLY);
    } catch (UnsupportedOperationException ignored) {
      // Windows and some hosted filesystems do not support POSIX permissions.
    }
  }

  private record IdentityMetadata(
      String serverId, String createdAt, String algorithm, String publicKey, String fingerprint) {}
}
