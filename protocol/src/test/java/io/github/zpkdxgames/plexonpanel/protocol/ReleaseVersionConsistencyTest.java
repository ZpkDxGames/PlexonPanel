package io.github.zpkdxgames.plexonpanel.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zpkdxgames.plexonpanel.identity.FleetContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReleaseVersionConsistencyTest {
  @Test
  void stableArtifactNamesAgreeWithRootVersionWithoutRewritingHistoricalAcceptance() throws Exception {
    Path root = repositoryRoot();
    String build = read(root, "build.gradle.kts");
    var matcher = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"\\s*$").matcher(build);
    assertTrue(matcher.find(), "root Gradle version is missing");
    String version = matcher.group(1);
    assertTrue(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?"), "Invalid release version");

    assertTrue(read(root, "agent/src/main/resources/plugin.yml").contains("version: \"" + version + "\""));

    String packager = read(root, "scripts/package-release.py");
    assertTrue(packager.contains("PlexonPanel-{version}.jar"));
    assertTrue(packager.contains("plexonpanel-host-{version}.jar"));
    assertTrue(packager.contains("PlexonPanel-{version}-examples.zip"));

    var gate = releaseGate(root, "docs/release-gates-" + version + ".json");
    assertEquals(version, gate.get("version").getAsString());
    assertEquals(ProtocolCodec.VERSION, gate.get("protocolVersion").getAsInt());
    assertEquals(FleetContract.ID, gate.get("fleetContract").getAsString());
    var schemas = gate.getAsJsonObject("configurationSchemas");
    assertEquals(FleetContract.PAPER_SCHEMA_VERSION, schemas.get("paper").getAsInt());
    assertEquals(FleetContract.HOST_SCHEMA_VERSION, schemas.get("host").getAsInt());
    assertTrue(read(root, "agent/src/main/resources/config.yml")
        .contains("schema-version: " + FleetContract.PAPER_SCHEMA_VERSION));
  }

  @Test
  void historicalReleaseGatesKeepTheirOwnVersions() throws Exception {
    Path root = repositoryRoot();
    assertEquals("5.0.0", releaseGate(root, "docs/release-gates-5.0.0.json").get("version").getAsString());
    assertEquals("4.0.0", releaseGate(root, "docs/release-gates-4.0.0.json").get("version").getAsString());
    assertEquals("3.1.0", releaseGate(root, "docs/release-gates.json").get("version").getAsString());
  }

  private static JsonObject releaseGate(Path root, String relative) throws Exception {
    return JsonParser.parseString(read(root, relative)).getAsJsonObject();
  }

  private static Path repositoryRoot() {
    Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (candidate != null) {
      if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))
          && Files.isDirectory(candidate.resolve("agent"))) return candidate;
      candidate = candidate.getParent();
    }
    throw new IllegalStateException("Cannot locate PlexonPanel repository root");
  }

  private static String read(Path root, String relative) throws Exception {
    return Files.readString(root.resolve(relative));
  }
}
