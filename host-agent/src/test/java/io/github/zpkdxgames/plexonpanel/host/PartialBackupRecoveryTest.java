package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PartialBackupRecoveryTest {
  @TempDir Path temporary;

  @Test
  void onlyUuidPartialArchivesAreRemoved() throws Exception {
    Path staging = Files.createDirectory(temporary.resolve("staging"));
    Path partial = staging.resolve("11111111-1111-1111-1111-111111111111.partial");
    Path unrelated = staging.resolve("keep.tmp");
    Files.writeString(partial, "incomplete");
    Files.writeString(unrelated, "keep");

    assertEquals(1, PartialBackupRecovery.clean(staging));
    assertFalse(Files.exists(partial));
    assertTrue(Files.exists(unrelated));
  }

  @Test
  void symlinkPartialIsDeletedWithoutFollowingTarget() throws Exception {
    Path staging = Files.createDirectory(temporary.resolve("symlink-staging"));
    Path target = temporary.resolve("target.txt");
    Files.writeString(target, "do-not-delete");
    Path link = staging.resolve("22222222-2222-2222-2222-222222222222.partial");
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | FileSystemException unsupported) {
      return;
    }

    assertEquals(1, PartialBackupRecovery.clean(staging));
    assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
    assertTrue(Files.exists(target));
  }
}
