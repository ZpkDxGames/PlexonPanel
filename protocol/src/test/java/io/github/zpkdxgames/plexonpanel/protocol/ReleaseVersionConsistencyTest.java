package io.github.zpkdxgames.plexonpanel.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ReleaseVersionConsistencyTest {
  private static final String EXPECTED_VERSION = "3.2.0-rc.1";

  @Test
  void phaseTwoRcArtifactNamesAgreeWithRootVersionWithoutRewritingHistoricalAcceptance() throws Exception {
    Path root = repositoryRoot();
    String build = read(root, "build.gradle.kts");
    var matcher = Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]+)\"\\s*$").matcher(build);
    assertTrue(matcher.find(), "root Gradle version is missing");
    String version = matcher.group(1);
    assertEquals(EXPECTED_VERSION, version);

    assertTrue(read(root, "agent/src/main/resources/plugin.yml").contains("version: \"" + version + "\""));
    assertTrue(read(root, ".github/workflows/build.yml").contains("PlexonPanel-" + version + "-${{ matrix.os }}"));

    String packager = read(root, "scripts/package-release.py");
    assertTrue(packager.contains("PlexonPanel-{version}.jar"));
    assertTrue(packager.contains("plexonpanel-host-{version}.jar"));
    assertTrue(packager.contains("PlexonPanel-{version}-examples.zip"));

    assertEquals(3, ProtocolCodec.VERSION);
    assertTrue(read(root, "docs/release-gates.json").contains("\"version\": \"3.1.0\""));
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
