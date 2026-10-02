package io.github.zpkdxgames.plexonpanel.host;
import static org.junit.jupiter.api.Assertions.*;
import io.github.zpkdxgames.plexonpanel.identity.NodeInstanceRegistry;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceConfigurationAuditTest {
  @TempDir Path root;
  @Test void actualPropertiesBindMinecraftAndRconPortsToTheRegisteredServiceAndSecretWithoutPrintingIt() throws Exception {
    Path secret = root.resolve("secret"); Files.writeString(secret, "synthetic-rcon-secret-unique\n");
    var channel = new HostConfig.CommandChannelConfig(true, "127.0.0.1", 25575, secret.toString(), 5000, 180);
    var entry = new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", 25565, 25575);
    var properties = new Properties(); properties.setProperty("enable-rcon", "true"); properties.setProperty("rcon.password", "synthetic-rcon-secret-unique");
    InstanceConfigurationAudit.validateProperties(properties, channel, entry);
    properties.setProperty("server-port", "25566");
    assertEquals("REGISTERED_MINECRAFT_PORT_MISMATCH", assertThrows(IOException.class, () -> InstanceConfigurationAudit.validateProperties(properties, channel, entry)).getMessage());
    properties.setProperty("server-port", "25565"); properties.setProperty("rcon.port", "25576");
    assertEquals("REGISTERED_RCON_PORT_MISMATCH", assertThrows(IOException.class, () -> InstanceConfigurationAudit.validateProperties(properties, channel, entry)).getMessage());
    properties.setProperty("rcon.port", "25575"); properties.setProperty("rcon.password", "private-invalid-sentinel");
    var denied = assertThrows(IOException.class, () -> InstanceConfigurationAudit.validateProperties(properties, channel, entry));
    assertEquals("RCON_SECRET_BINDING_MISMATCH", denied.getMessage()); assertFalse(denied.toString().contains("sentinel"));
    properties.setProperty("enable-rcon", "false");
    assertEquals("RCON_ENABLEMENT_MISMATCH", assertThrows(IOException.class, () -> InstanceConfigurationAudit.validateProperties(properties, channel, entry)).getMessage());
  }
  @Test void disabledRconDoesNotReadSecretsAndUnsafeCredentialFilesAreRejected() throws Exception {
    var entry = new NodeInstanceRegistry.Entry(UUID.randomUUID(), "alpha", 25565, null);
    InstanceConfigurationAudit.validateProperties(new Properties(), HostConfig.CommandChannelConfig.defaults(), entry);
    Path secret = root.resolve("secret"); Files.writeString(secret, "too-short");
    assertEquals("RCON_SECRET_TOO_SHORT", assertThrows(IOException.class, () -> InstanceConfigurationAudit.secret(secret)).getMessage());
    Files.writeString(secret, "long-secret-with\ninterior-newline");
    assertEquals("RCON_SECRET_INVALID", assertThrows(IOException.class, () -> InstanceConfigurationAudit.secret(secret)).getMessage());
    Path link = root.resolve("link"); Files.createSymbolicLink(link, secret);
    assertThrows(IOException.class, () -> InstanceConfigurationAudit.secret(link));
  }
  @Test void perInstanceRconCredentialsCannotBeReusedAcrossAllocations() throws Exception {
    Path a = root.resolve("a"); Path b = root.resolve("b");
    Files.writeString(a, "synthetic-private-secret-alpha"); Files.writeString(b, "synthetic-private-secret-bravo");
    InstanceConfigurationAudit.requireDistinctSecrets(List.of(a,b));
    Files.writeString(b, "synthetic-private-secret-alpha\n");
    var denied = assertThrows(IOException.class, () -> InstanceConfigurationAudit.requireDistinctSecrets(List.of(a,b)));
    assertEquals("RCON_SECRET_REUSE_DENIED", denied.getMessage()); assertFalse(denied.toString().contains("synthetic"));
  }
}
