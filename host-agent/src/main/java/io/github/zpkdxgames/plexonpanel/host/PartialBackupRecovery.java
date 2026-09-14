package io.github.zpkdxgames.plexonpanel.host;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/** Cleans bounded, unmistakable cold-backup staging artifacts after an interrupted Host process. */
final class PartialBackupRecovery {
  private PartialBackupRecovery() {}

  static int clean(Path staging) throws IOException {
    if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return 0;
    if (!Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(staging))
      throw new IOException("BACKUP_STAGING_UNAVAILABLE");
    List<Path> candidates;
    try (var stream = Files.list(staging)) {
      candidates =
          stream
              .filter(
                  path ->
                      path.getFileName()
                          .toString()
                          .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.partial"))
              .limit(1025)
              .toList();
    }
    if (candidates.size() > 1024) throw new IOException("BACKUP_PARTIAL_LIMIT");
    int cleaned = 0;
    for (Path candidate : candidates) {
      if (Files.isSymbolicLink(candidate)) {
        Files.delete(candidate);
        cleaned++;
        continue;
      }
      if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("BACKUP_PARTIAL_INVALID");
      Files.delete(candidate);
      cleaned++;
    }
    return cleaned;
  }
}
