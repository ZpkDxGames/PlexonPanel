package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.AccessDeniedException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BackupSourceDiagnosticsTest {
  @Test void exposesOnlyValidatedRelativeSourcePath() {
    var source = new FullBackupSource.SourceFailure("BACKUP_SOURCE_UNREADABLE",
        Path.of("plugins", "Example", "data.db"), new AccessDeniedException("/private/server/plugins/Example/data.db"));
    var failure = FullBackupPreflight.translate(source, "BACKUP_SOURCE_UNREADABLE", "source");
    assertEquals("BACKUP_SOURCE_UNREADABLE", failure.code());
    assertEquals("plugins/Example/data.db", failure.safeRelativePath());
    assertFalse(failure.safeData().toString().contains("/private/server"));
  }
  @Test void absoluteTraversalAndControlCharactersAreNeverPublished() {
    for (String path : new String[]{"/private/server/secret", "../secret", "plugins/evil\nname"}) {
      var source = new FullBackupSource.SourceFailure("BACKUP_SOURCE_UNREADABLE", Path.of(path), null);
      assertNull(source.relativePath());
      assertNull(FullBackupPreflight.translate(source, "BACKUP_SOURCE_UNREADABLE", "source").safeRelativePath());
    }
  }
}
