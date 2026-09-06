package io.github.zpkdxgames.plexonpanel.files;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** All components are opened relative to directory handles with NOFOLLOW_LINKS. */
public final class PathPolicy {
  public static final Set<String> EDITABLE =
      Set.of("yml", "yaml", "json", "properties", "conf", "toml", "txt", "md");
  private final Map<String, Path> roots;
  private final List<Path> protectedPaths;
  private final Map<String, Object> rootKeys;

  public PathPolicy(Map<String, Path> roots, List<Path> protectedPaths) throws IOException {
    Map<String, Path> result = new TreeMap<>();
    for (var entry : roots.entrySet()) {
      if (!entry.getKey().matches("[a-z][a-z0-9_-]{0,31}"))
        throw new IOException("Invalid file root name");
      Path path = entry.getValue().toAbsolutePath().normalize();
      if (path.getNameCount() < 2
          || List.of("etc", "proc", "sys", "root", "home", "dev")
              .contains(path.getName(0).toString())) throw new IOException("Unsafe file root");
      if (!path.equals(path.toRealPath()) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("File root must be a real directory");
      result.put(entry.getKey(), path);
    }
    this.roots = Map.copyOf(result);
    Map<String, Object> keys = new HashMap<>();
    for (var entry : result.entrySet()) {
      Object key =
          Files.readAttributes(
                  entry.getValue(),
                  java.nio.file.attribute.BasicFileAttributes.class,
                  LinkOption.NOFOLLOW_LINKS)
              .fileKey();
      if (key == null) throw new IOException("File root identity unavailable");
      keys.put(entry.getKey(), key);
    }
    this.rootKeys = Map.copyOf(keys);
    this.protectedPaths = protectedPaths.stream().map(p -> p.toAbsolutePath().normalize()).toList();
  }

  public Set<String> roots() {
    return roots.keySet();
  }

  public Path root(String name) {
    Path root = roots.get(name);
    if (root == null) throw new SecurityException("UNKNOWN_ROOT");
    return root;
  }

  public List<String> parts(String rootName, String relative, boolean directory) {
    Path root = root(rootName);
    if (relative == null
        || relative.length() > 512
        || relative.startsWith("/")
        || relative.indexOf('\\') >= 0
        || relative.indexOf('%') >= 0
        || relative.chars().anyMatch(Character::isISOControl))
      throw new SecurityException("PATH_DENIED");
    if (directory && relative.isEmpty()) return List.of();
    List<String> parts = List.of(relative.split("/", -1));
    for (String part : parts) {
      String lower = part.toLowerCase(Locale.ROOT);
      if (part.isBlank()
          || part.equals(".")
          || part.equals("..")
          || part.length() > 128
          || part.contains(":")
          || lower.startsWith(".")
          || Set.of(
                  "plexonpanel",
                  "plexonpanel-host",
                  "logs",
                  "identity",
                  "audit",
                  "rclone.conf",
                  "access.json",
                  "ops.json",
                  "usercache.json")
              .contains(lower)
          || lower.contains("secret")
          || lower.contains("credential")
          || lower.contains("private-key")
          || lower.endsWith(".key")
          || lower.endsWith(".pem")
          || lower.equals("server.properties")) throw new SecurityException("PATH_DENIED");
    }
    Path target = root.resolve(String.join("/", parts)).normalize();
    if (!target.startsWith(root) || protectedPaths.stream().anyMatch(target::startsWith))
      throw new SecurityException("PATH_DENIED");
    return parts;
  }

  public boolean editable(String name) {
    int dot = name.lastIndexOf('.');
    return dot >= 0 && EDITABLE.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  public Handle parent(String rootName, String relative) throws IOException {
    List<String> parts = parts(rootName, relative, false);
    return new Handle(
        openDirectory(rootName, parts.subList(0, parts.size() - 1)), Path.of(parts.getLast()));
  }

  public SecureDirectoryStream<Path> directory(String rootName, String relative)
      throws IOException {
    return openDirectory(rootName, parts(rootName, relative, true));
  }

  private SecureDirectoryStream<Path> openDirectory(String name, List<String> parts)
      throws IOException {
    Path configured = root(name);
    // Walk from the filesystem root without following even an ancestor symlink.
    DirectoryStream<Path> raw = Files.newDirectoryStream(configured.getRoot());
    if (!(raw instanceof SecureDirectoryStream<Path> current)) {
      raw.close();
      throw new IOException("Secure directory handles unavailable on this filesystem");
    }
    try {
      for (Path part : configured) {
        SecureDirectoryStream<Path> next =
            current.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS);
        current.close();
        current = next;
      }
      Object key =
          current
              .getFileAttributeView(java.nio.file.attribute.BasicFileAttributeView.class)
              .readAttributes()
              .fileKey();
      if (!rootKeys.get(name).equals(key))
        throw new IOException("Configured root was replaced; reload local configuration");
      for (String part : parts) {
        SecureDirectoryStream<Path> next =
            current.newDirectoryStream(Path.of(part), LinkOption.NOFOLLOW_LINKS);
        current.close();
        current = next;
      }
      return current;
    } catch (Exception e) {
      current.close();
      throw e;
    }
  }

  public record Handle(SecureDirectoryStream<Path> directory, Path name) implements AutoCloseable {
    public void close() throws IOException {
      directory.close();
    }
  }
}
