package io.github.zpkdxgames.plexonpanel.host;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Applies the configured top-level include boundary to every full-backup source walk. */
final class FullBackupSource {
  private static final Set<String> FIXED_EXCLUDED_PREFIXES =
      Set.of(".plexonpanel-restore-", ".plexonpanel-rollback-");

  private FullBackupSource() {}

  interface Visitor {
    default void visitDirectory(Path directory, Path relative) throws IOException {}

    void visitFile(Path file, Path relative, BasicFileAttributes attributes) throws IOException;
  }

  static void walk(
      Path root,
      List<String> includes,
      MaintenanceSettings.FullRestorePoint settings,
      Visitor visitor)
      throws IOException {
    Objects.requireNonNull(root);
    Objects.requireNonNull(includes);
    Objects.requireNonNull(settings);
    Objects.requireNonNull(visitor);

    LinkedHashSet<String> unique = new LinkedHashSet<>(includes);
    if (unique.isEmpty() || unique.size() != includes.size())
      throw new IOException("BACKUP_INCLUDE_INVALID");

    for (String include : unique) {
      if (include == null || !include.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}"))
        throw new IOException("BACKUP_INCLUDE_INVALID");
      Path source = root.resolve(include).normalize();
      if (!root.equals(source.getParent())) throw new IOException("BACKUP_INCLUDE_INVALID");
      Path sourceRelative = root.relativize(source);
      if (excluded(sourceRelative, settings)) continue;
      try {
        Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (NoSuchFileException missing) {
        throw new IOException("BACKUP_SOURCE_MISSING", missing);
      } catch (AccessDeniedException denied) {
        throw new IOException("BACKUP_SOURCE_UNREADABLE", denied);
      }
      Files.walkFileTree(
          source,
          EnumSet.noneOf(FileVisitOption.class),
          96,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                throws IOException {
              Path relative = root.relativize(directory);
              if (excluded(relative, settings)) return FileVisitResult.SKIP_SUBTREE;
              if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory))
                throw new IOException("BACKUP_SYMLINK_REJECTED");
              if (!Files.isReadable(directory))
                throw new IOException("BACKUP_SOURCE_UNREADABLE");
              visitor.visitDirectory(directory, relative);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              Path relative = root.relativize(file);
              if (excluded(relative, settings)) return FileVisitResult.CONTINUE;
              if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(file))
                throw new IOException("BACKUP_SYMLINK_REJECTED");
              if (!Files.isReadable(file)) throw new IOException("BACKUP_SOURCE_UNREADABLE");
              visitor.visitFile(file, relative, attributes);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure)
                throws IOException {
              // FileTreeWalker may need to open a directory before preVisitDirectory can prune it.
              // Mandatory private/noise exclusions are deliberately unreadable to the Host, so an
              // access failure for an already-excluded path is the expected security boundary, not
              // a failed backup source. Never apply this exception to an included path.
              if (excluded(root.relativize(file), settings)) return FileVisitResult.CONTINUE;
              if (failure instanceof NoSuchFileException)
                throw new IOException("BACKUP_SOURCE_CHANGED", failure);
              if (failure instanceof AccessDeniedException)
                throw new IOException("BACKUP_SOURCE_UNREADABLE", failure);
              throw failure;
            }
          });
    }
  }

  static boolean excluded(Path relative, MaintenanceSettings.FullRestorePoint settings) {
    String name = relative.toString().replace(File.separatorChar, '/');
    if (name.isEmpty()) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    if (FIXED_EXCLUDED_PREFIXES.stream().anyMatch(lower::startsWith)
        || lower.endsWith(".partial")) return true;
    for (String configured : settings.excludes()) {
      String normalized = configured.replace('\\', '/');
      if (name.equals(normalized) || name.startsWith(normalized + "/")) return true;
    }
    return false;
  }
}
