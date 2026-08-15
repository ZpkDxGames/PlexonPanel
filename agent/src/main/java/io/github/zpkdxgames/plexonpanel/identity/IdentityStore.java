package io.github.zpkdxgames.plexonpanel.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

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
        if (Files.exists(metadataPath) && Files.exists(privateKeyPath)) {
            return load();
        }
        if (Files.exists(metadataPath) != Files.exists(privateKeyPath)) {
            throw new IOException("Incomplete PlexonPanel identity; both device.json and device.key are required");
        }
        return create();
    }

    public synchronized DeviceIdentity rotate() throws IOException {
        // Generate the replacement before overwriting the existing identity; do
        // not pre-delete a working key pair.
        return create();
    }

    private DeviceIdentity create() throws IOException {
        try {
            Files.createDirectories(identityDirectory);
            KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
            KeyPair keyPair = generator.generateKeyPair();
            DeviceIdentity identity = new DeviceIdentity(UUID.randomUUID(), clock.instant(), keyPair);
            IdentityMetadata metadata = new IdentityMetadata(
                identity.serverId().toString(),
                identity.createdAt().toString(),
                "Ed25519",
                identity.publicKeyBase64(),
                identity.fingerprint()
            );
            AtomicFiles.writeUtf8(metadataPath, gson.toJson(metadata) + System.lineSeparator());
            AtomicFiles.writeUtf8(privateKeyPath,
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()) + System.lineSeparator());
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
            IdentityMetadata metadata = gson.fromJson(Files.readString(metadataPath, StandardCharsets.UTF_8), IdentityMetadata.class);
            if (metadata == null || !"Ed25519".equals(metadata.algorithm())) {
                throw new IOException("Unsupported or missing identity algorithm");
            }
            var publicKey = KeyCodec.decodePublic(metadata.publicKey());
            var privateKey = KeyCodec.decodePrivate(Files.readString(privateKeyPath, StandardCharsets.UTF_8));
            DeviceIdentity identity = new DeviceIdentity(
                UUID.fromString(metadata.serverId()),
                Instant.parse(metadata.createdAt()),
                new KeyPair(publicKey, privateKey)
            );
            if (!identity.fingerprint().equals(metadata.fingerprint())) {
                throw new IOException("PlexonPanel identity fingerprint mismatch");
            }
            restrictPrivateKeyPermissions();
            return identity;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Unable to load PlexonPanel identity", error);
        }
    }

    private void restrictPrivateKeyPermissions() {
        try {
            Files.setPosixFilePermissions(privateKeyPath, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and some hosted filesystems do not support POSIX permissions.
        }
    }

    private record IdentityMetadata(
        String serverId,
        String createdAt,
        String algorithm,
        String publicKey,
        String fingerprint
    ) {
    }
}
