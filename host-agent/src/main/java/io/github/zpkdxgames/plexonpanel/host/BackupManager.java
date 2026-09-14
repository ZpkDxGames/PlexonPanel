package io.github.zpkdxgames.plexonpanel.host;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/**
 * Legacy class name retained only as a package-local SHA-256 helper for full restore-point
 * verification. Live snapshot creation, restore, retention, scheduling, and Paper save leases were
 * retired in Step 5.
 */
public final class BackupManager {
  private BackupManager() {}

  public static String fileHash(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[65_536];
        int count;
        while ((count = in.read(buffer)) >= 0) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
