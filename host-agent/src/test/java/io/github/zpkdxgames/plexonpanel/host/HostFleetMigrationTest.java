package io.github.zpkdxgames.plexonpanel.host;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostFleetMigrationTest {
  @TempDir Path root;
  private JsonObject prepared() throws Exception {
    var fixture = new HostConfigTest(); fixture.root = root;
    var raw = fixture.config(); var layout = new InstanceLayout("plexoncraft");
    raw.addProperty("schemaVersion", 4);
    raw.addProperty("serverRoot", layout.serverRoot().toString());
    raw.addProperty("dataDirectory", layout.stateDirectory().toString());
    raw.addProperty("accessRegistry", layout.accessRegistry().toString());
    raw.addProperty("serviceName", layout.minecraftUnit());
    raw.getAsJsonObject("backups").addProperty("directory", layout.backupsDirectory().toString());
    raw.addProperty("operatorNote", "preserved-local-metadata");
    return raw;
  }
  @Test void planDoesNotMutateSourceOrPublishPrivateConfigurationAndMigrationIsIdempotent() throws Exception {
    var raw = prepared(); var before = raw.deepCopy(); var node = UUID.randomUUID();
    var plan = HostConfigMigration.planFleet(raw, node, "plexoncraft");
    assertEquals(before, raw); assertEquals(4, plan.sourceSchema()); assertTrue(plan.changesRequired());
    assertFalse(new Gson().toJson(plan).contains("relayPublicKey"));
    assertFalse(new Gson().toJson(plan).contains("operatorNote"));
    var target = HostConfigMigration.fleetCandidate(raw, node, "plexoncraft");
    assertEquals(raw.get("serverId"), target.get("serverId")); assertEquals(raw.get("operatorNote"), target.get("operatorNote"));
    Path path = root.resolve("host-config.json"); Files.writeString(path, raw.toString());
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r-----"));
    HostConfigMigration.publishFleet(path, target, io.github.zpkdxgames.plexonpanel.util.AtomicFiles::writeUtf8);
    assertEquals(5, HostConfigMigration.schemaVersion(path));
    assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)));
    assertEquals(raw.toString(), Files.readString(root.resolve("host-config.json.pre-v5-backup")));
    assertFalse(HostConfigMigration.planFleet(target, node, "plexoncraft").changesRequired());
    String migrated = Files.readString(path); HostConfigMigration.migrate(path); assertEquals(migrated, Files.readString(path));
  }
  @Test void failedPublicationPreservesOriginalAndFirstBackupAndRetrySucceeds() throws Exception {
    var raw = prepared(); var node = UUID.randomUUID(); var target = HostConfigMigration.fleetCandidate(raw, node, "plexoncraft");
    Path path = root.resolve("host-config.json"); Files.writeString(path, raw.toString());
    assertThrows(IOException.class, () -> HostConfigMigration.publishFleet(path, target, (p, text) -> { throw new IOException("SIMULATED_PUBLICATION_FAILURE"); }));
    assertEquals(raw.toString(), Files.readString(path));
    HostConfigMigration.publishFleet(path, target, io.github.zpkdxgames.plexonpanel.util.AtomicFiles::writeUtf8);
    assertEquals(raw.toString(), Files.readString(root.resolve("host-config.json.pre-v5-backup")));
  }
  @Test void rejectsCrossInstancePathsRconAndRemoteNamespacesAndWrappedOrFutureSchemaBeforeWriting() throws Exception {
    var node = UUID.randomUUID(); var raw = prepared();
    raw.addProperty("serverRoot", new InstanceLayout("survival").serverRoot().toString());
    assertThrows(IllegalArgumentException.class, () -> HostConfigMigration.fleetCandidate(raw, node, "plexoncraft"));
    var valid = HostConfigMigration.fleetCandidate(prepared(), node, "plexoncraft");
    valid.addProperty("schemaVersion", new java.math.BigInteger("4294967301"));
    assertThrows(IllegalArgumentException.class, () -> HostConfig.fromJson(valid));
    valid.addProperty("schemaVersion", 5);
    var channel = new JsonObject(); channel.addProperty("enabled", true); channel.addProperty("host", "127.0.0.1");
    channel.addProperty("port", 25575); channel.addProperty("secretFile", new InstanceLayout("survival").rconSecret().toString());
    channel.addProperty("commandTimeoutMillis", 5000); channel.addProperty("readinessTimeoutSeconds", 180); valid.add("commandChannel", channel);
    assertThrows(IllegalArgumentException.class, () -> HostConfig.fromJson(valid));
    valid.remove("commandChannel");
    var backups = valid.getAsJsonObject("backups"); backups.addProperty("rcloneRemote", "offsite:instances/unbound");
    backups.addProperty("rcloneConfig", new InstanceLayout("plexoncraft").rcloneConfig().toString());
    assertThrows(IllegalArgumentException.class, () -> HostConfig.fromJson(valid));
    backups.addProperty("rcloneRemote", "offsite:instances/"+valid.get("serverId").getAsString());
    assertDoesNotThrow(() -> HostConfig.fromJson(valid));
    assertThrows(IllegalArgumentException.class, () -> HostConfigMigration.fleetCandidate(valid, UUID.randomUUID(), "plexoncraft"));
  }
  @Test void CLIErrorBoundaryNeverPrintsInvalidPrivateConfigurationOrItsCause() throws Exception {
    Path path = root.resolve("private-config.json"); Files.writeString(path, "not-json-private-sentinel");
    String cp = java.util.stream.Stream.of(HostMain.class, io.github.zpkdxgames.plexonpanel.identity.NodeIdentity.class, Gson.class)
        .map(type -> { try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
          catch (Exception failure) { throw new RuntimeException(failure); } }).distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java").toString(), "-cp", cp, HostMain.class.getName(), path.toString(), "--migrate-config").redirectErrorStream(true).start();
    assertTrue(child.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
    String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(1, child.exitValue()); assertFalse(output.contains("private-sentinel")); assertFalse(output.contains("Caused by:"));
    assertFalse(output.contains("at io.github")); assertTrue(output.contains("HOST_STARTUP_OR_LOCAL_OPERATION_FAILED"));
  }
}
