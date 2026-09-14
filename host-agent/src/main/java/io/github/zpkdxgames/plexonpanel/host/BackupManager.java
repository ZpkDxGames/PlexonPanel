package io.github.zpkdxgames.plexonpanel.host;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/**
 * Compatibility home for the backup SHA-256 helper.
 *
 * <p>The former live-snapshot BackupManager runtime was retired in the manual-full-backup
 * architecture. This class intentionally has no scheduler, archive, Paper lease, restore, or
 * control-plane behavior.
 */
public final class BackupManager {
  private BackupManager() {}

  public static String fileHash(Path path) throws IOException {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
        byte[] buffer = new byte[65_536];
        int n;
        while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
