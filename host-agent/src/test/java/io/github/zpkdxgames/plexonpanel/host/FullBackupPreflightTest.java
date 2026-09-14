package io.github.zpkdxgames.plexonpanel.host;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FullBackupPreflightTest {
  @Test
  void requiredSpaceIncludesSourceSizedHeadroomForSmallBackups() {
    assertEquals(2_000_000L, FullBackupPreflight.requiredSpace(1_000_000L));
  }

  @Test
  void requiredSpaceCapsHeadroomAtTwoGiB() {
    long source = 10L * 1024 * 1024 * 1024;
    assertEquals(source + 2L * 1024 * 1024 * 1024, FullBackupPreflight.requiredSpace(source));
  }

  @Test
  void requiredSpaceFailsClosedOnOverflow() {
    assertEquals(Long.MAX_VALUE, FullBackupPreflight.requiredSpace(Long.MAX_VALUE - 1));
  }
}
