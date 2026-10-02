package io.github.zpkdxgames.plexonpanel.host;

import io.github.zpkdxgames.plexonpanel.identity.InstanceLayout;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Checks existing schema-5 authority; it never chmods/chowns or prints private file contents. */
final class InstanceFilePolicy {
  private InstanceFilePolicy() {}
  static void validate(HostConfig config, Path configPath) throws IOException, InterruptedException {
    var layout = new InstanceLayout(config.fleet().instanceKey());
    InstanceLayout.requirePath(configPath.toString(), layout.hostConfig());
    requireFile(configPath, layout.hostUser());
    for (String suffix : List.of(".pre-v5-backup", ".pre-v4-backup")) {
      Path backup = configPath.resolveSibling(configPath.getFileName() + suffix);
      if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) requireFile(backup, layout.hostUser());
    }
    requireDirectory(layout.configDirectory(), "root", layout.hostUser(), "rwxr-x---");
    requireDirectory(layout.hostLauncher(), "root", layout.hostUser(), "rwxr-x---");
    requireDirectory(layout.stateDirectory(), layout.hostUser(), layout.hostUser(), "rwx------");
    requireDirectory(layout.backupsDirectory(), layout.hostUser(), layout.hostUser(), "rwx------");
    if (config.commandChannel().enabled()) requireOwnerOnlyFile(layout.rconSecret(), layout.hostUser());
    if (!config.backups().rcloneRemote().isBlank()) requireFile(layout.rcloneConfig(), layout.hostUser());
    requireGroups(command(List.of("/usr/bin/id", "--groups", "--name", "--", layout.hostUser())), layout.hostUser());
    if (Files.isWritable(layout.serverRoot()) && ProcessHandle.current().info().user().orElse("").equals(layout.hostUser()))
      throw new IOException("HOST_SERVER_WRITE_AUTHORITY_DENIED");
  }
  static void requireFile(Path path, String hostUser) throws IOException, InterruptedException {
    InstanceLayout.rejectSymlinks(path);
    var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile()) throw new IOException("INSTANCE_PRIVATE_FILE_INVALID");
    requireMetadata(attrs.owner().getName(), attrs.group().getName(), attrs.permissions(), "root", hostUser, "rw-r-----");
    requireRootParents(path.getParent());
    requireAcl(command(List.of("/usr/bin/getfacl", "--omit-header", "--absolute-names", "--", path.toString())), hostUser, false);
  }
  static void requireOwnerOnlyFile(Path path, String hostUser) throws IOException {
    InstanceLayout.rejectSymlinks(path);
    var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isRegularFile()) throw new IOException("INSTANCE_PRIVATE_FILE_INVALID");
    requireMetadata(attrs.owner().getName(), attrs.group().getName(), attrs.permissions(), hostUser, hostUser, "rw-------");
    requireRootParents(path.getParent());
  }
  private static void requireDirectory(Path path, String owner, String group, String mode) throws IOException, InterruptedException {
    InstanceLayout.rejectSymlinks(path);
    var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attrs.isDirectory()) throw new IOException("INSTANCE_DIRECTORY_INVALID");
    requireMetadata(attrs.owner().getName(), attrs.group().getName(), attrs.permissions(), owner, group, mode);
    requireRootParents(path.getParent());
    if (owner.equals("root")) requireAcl(command(List.of("/usr/bin/getfacl", "--omit-header", "--absolute-names", "--", path.toString())), group, true);
  }
  static void requireMetadata(String owner, String group, Set<PosixFilePermission> permissions, String requiredOwner, String requiredGroup, String mode) throws IOException {
    if (!owner.equals(requiredOwner) || !group.equals(requiredGroup) || !permissions.equals(PosixFilePermissions.fromString(mode)))
      throw new IOException("INSTANCE_FILE_AUTHORITY_INVALID");
  }
  static void requireAcl(String text, String hostUser, boolean directory) throws IOException {
    var expected = new HashMap<String, String>();
    expected.put("user:", directory ? "rwx" : "rw-");
    expected.put("group:", directory ? "r-x" : "r--");
    expected.put("other:", "---");
    Set<String> seen = new HashSet<>();
    for (String raw : text.split("\\R")) {
      String line = raw.split("#", 2)[0].strip(); if (line.isEmpty()) continue;
      String[] pieces = line.split(":", -1);
      if (pieces.length != 3 || !seen.add(pieces[0]+":"+pieces[1])) throw new IOException("INSTANCE_PRIVATE_ACL_INVALID");
      String selector = pieces[0]+":"+pieces[1];
      String required = expected.get(selector);
      if (required == null && (selector.equals("mask:") || selector.equals("user:"+hostUser) || selector.equals("group:"+hostUser)))
        required = directory ? "r-x" : "r--";
      if (required == null || !pieces[2].equals(required)) throw new IOException("INSTANCE_PRIVATE_ACL_INVALID");
    }
    if (!seen.containsAll(expected.keySet())) throw new IOException("INSTANCE_PRIVATE_ACL_INVALID");
  }
  static void requireGroups(String names, String hostUser) throws IOException {
    var groups = new HashSet<>(Arrays.asList(names.strip().split("\\s+")));
    if (!groups.contains(hostUser) || !Set.of(hostUser, "plexonpanel-backup").containsAll(groups))
      throw new IOException("INSTANCE_GROUP_AUTHORITY_INVALID");
  }
  private static void requireRootParents(Path parent) throws IOException {
    for (Path path = parent; path != null; path = path.getParent()) {
      var attrs = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attrs.isDirectory() || !attrs.owner().getName().equals("root") || attrs.permissions().contains(PosixFilePermission.GROUP_WRITE)
          || attrs.permissions().contains(PosixFilePermission.OTHERS_WRITE)) throw new IOException("INSTANCE_PARENT_AUTHORITY_INVALID");
    }
  }
  private static String command(List<String> arguments) throws IOException, InterruptedException {
    Process child = new ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      if (!child.waitFor(5, TimeUnit.SECONDS) || child.exitValue() != 0) throw new IOException("INSTANCE_AUTHORITY_CHECK_UNAVAILABLE");
      byte[] bytes = child.getInputStream().readNBytes(8193);
      if (bytes.length > 8192) throw new IOException("INSTANCE_AUTHORITY_CHECK_UNAVAILABLE");
      return new String(bytes, StandardCharsets.UTF_8);
    } finally { if (child.isAlive()) child.destroyForcibly(); }
  }
}
