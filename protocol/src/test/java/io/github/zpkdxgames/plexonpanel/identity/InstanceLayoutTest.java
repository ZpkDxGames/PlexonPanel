package io.github.zpkdxgames.plexonpanel.identity;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class InstanceLayoutTest {
  @TempDir Path directory;
  @Test void distinctInstancesCannotSelectAnotherInstancePathsAccountsUnitsOrJournals() {
    var first = new InstanceLayout("plexoncraft"); var second = new InstanceLayout("survival");
    assertEquals("minecraft@plexoncraft.service", first.minecraftUnit());
    assertEquals("plexonpanel-plexoncraft", first.journalNamespace());
    assertEquals("pph-plexoncraft", first.hostUser());
    assertNotEquals(first.rconSecret(), second.rconSecret());
    assertNotEquals(first.stateDirectory(), second.stateDirectory());
    assertNotEquals(first.backupsDirectory(), second.backupsDirectory());
    assertThrows(IllegalArgumentException.class, () -> InstanceLayout.requirePath(second.serverRoot().toString(), first.serverRoot()));
    assertThrows(IllegalArgumentException.class, () -> InstanceLayout.requirePath(first.serverRoot()+"/../server", first.serverRoot()));
    assertThrows(IllegalArgumentException.class, () -> new InstanceLayout("../survival"));
    assertEquals(32, new InstanceLayout("a".repeat(28)).hostUser().length());
    assertThrows(IllegalArgumentException.class, () -> new InstanceLayout("a".repeat(29)));
  }
  @Test void symlinkedExistingAncestorsCannotRedirectAnOtherwiseCanonicalPath() throws Exception {
    var actual = Files.createDirectory(directory.resolve("actual"));
    var link = directory.resolve("redirect"); Files.createSymbolicLink(link, actual);
    assertThrows(IOException.class, () -> InstanceLayout.rejectSymlinks(link.resolve("not-created-yet")));
    InstanceLayout.rejectSymlinks(actual.resolve("not-created-yet"));
  }
}
