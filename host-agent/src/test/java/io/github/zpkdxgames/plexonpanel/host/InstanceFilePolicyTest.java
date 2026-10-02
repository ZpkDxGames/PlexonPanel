package io.github.zpkdxgames.plexonpanel.host;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstanceFilePolicyTest {
  @Test void privateAuthorityRequiresAdministratorOwnerExactHostGroupAndPrivateMode() throws Exception {
    InstanceFilePolicy.requireMetadata("root", "pph-alpha", PosixFilePermissions.fromString("rw-r-----"), "root", "pph-alpha", "rw-r-----");
    for (String mode : List.of("rw-r--r--", "rw-rw----", "rw-r-----", "rwxr-x---")) {
      String owner = mode.equals("rw-r-----") ? "mc-alpha" : "root";
      assertThrows(IOException.class, () -> InstanceFilePolicy.requireMetadata(owner, "pph-alpha", PosixFilePermissions.fromString(mode), "root", "pph-alpha", "rw-r-----"));
    }
    assertThrows(IOException.class, () -> InstanceFilePolicy.requireMetadata("root", "pph-bravo", PosixFilePermissions.fromString("rw-r-----"), "root", "pph-alpha", "rw-r-----"));
  }
  @Test void namedAclCannotExposeSecretsToMinecraftAnotherHostOrGlobalGroups() throws Exception {
    String acl = "user::rw-\ngroup::r--\nother::---\n";
    InstanceFilePolicy.requireAcl(acl, "pph-alpha", false);
    InstanceFilePolicy.requireAcl(acl + "user:pph-alpha:r--\nmask::r--\n", "pph-alpha", false);
    for (String grant : List.of("user:mc-alpha:r--", "user:pph-bravo:r--", "group:adm:r--", "group:systemd-journal:r--", "mask::rw-", "default:user:mc-alpha:r--", "user:pph-alpha:rw-"))
      assertThrows(IOException.class, () -> InstanceFilePolicy.requireAcl(acl+grant+"\n", "pph-alpha", false));
    assertThrows(IOException.class, () -> InstanceFilePolicy.requireAcl("user::rw-\ngroup::r--\nother::r--\n", "pph-alpha", false));
    InstanceFilePolicy.requireAcl("user::rwx\ngroup::r-x\nother::---\n", "pph-alpha", true);
    assertThrows(IOException.class, () -> InstanceFilePolicy.requireAcl("user::rwx\ngroup::rwx\nother::---\n", "pph-alpha", true));
  }
  @Test void HostCannotInheritAnotherInstancesGroupsOrGlobalJournalOrRootAuthority() throws Exception {
    InstanceFilePolicy.requireGroups("pph-alpha", "pph-alpha");
    InstanceFilePolicy.requireGroups("pph-alpha plexonpanel-backup\n", "pph-alpha");
    for (String group : List.of("adm", "sudo", "root", "systemd-journal", "mc-alpha", "pph-bravo", "mc-bravo"))
      assertThrows(IOException.class, () -> InstanceFilePolicy.requireGroups("pph-alpha "+group, "pph-alpha"));
    assertThrows(IOException.class, () -> InstanceFilePolicy.requireGroups("", "pph-alpha"));
  }
}
