package io.github.zpkdxgames.plexonpanel.host;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.zpkdxgames.plexonpanel.util.AtomicFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;

/** Adds the Host configuration schema marker without rewriting identities or operator keys. */
final class HostConfigMigration {
  private HostConfigMigration() {}

  static boolean needsMigration(Path configPath) throws IOException {
    return schema(read(configPath)) < HostConfig.CURRENT_SCHEMA_VERSION;
  }

  static void migrate(Path configPath) throws IOException {
    JsonObject raw = read(configPath);
    int schema = schema(raw);
    if (schema == HostConfig.CURRENT_SCHEMA_VERSION) return;
    PosixFileAttributes original =
        Files.readAttributes(configPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

    Path backup = configPath.resolveSibling(configPath.getFileName() + ".pre-v4-backup");
    if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
      Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
    } else if (!Files.isRegularFile(backup, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(backup)) {
      throw new IOException("Refusing to replace an unsafe Host config migration backup");
    }

    raw.addProperty("schemaVersion", HostConfig.CURRENT_SCHEMA_VERSION);
    AtomicFiles.writeUtf8(
        configPath, new GsonBuilder().setPrettyPrinting().create().toJson(raw) + "\n");
    Files.setOwner(configPath, original.owner());
    PosixFileAttributeView attributes =
        Files.getFileAttributeView(configPath, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    attributes.setGroup(original.group());
    attributes.setPermissions(original.permissions());
  }

  private static JsonObject read(Path configPath) throws IOException {
    if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(configPath))
      throw new IOException("Host config must be a regular non-symlink file");
    if (Files.size(configPath) > 65_536L) throw new IOException("Host config exceeds limit");

    JsonObject raw;
    try {
      raw = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
    } catch (RuntimeException error) {
      throw new IOException("Host config is not a valid JSON object", error);
    }
    schema(raw);
    return raw;
  }

  private static int schema(JsonObject raw) {
    if (raw.has("schemaVersion")
        && (!raw.get("schemaVersion").isJsonPrimitive()
            || !raw.get("schemaVersion").getAsJsonPrimitive().isNumber()
            || raw.get("schemaVersion").getAsBigDecimal().stripTrailingZeros().scale() > 0))
      throw new IllegalArgumentException("Host configuration schemaVersion must be an integer");
    int schema = raw.has("schemaVersion") ? raw.get("schemaVersion").getAsInt() : 0;
    if (schema < 0 || schema > HostConfig.CURRENT_SCHEMA_VERSION)
      throw new IllegalArgumentException("Unsupported Host configuration schemaVersion " + schema);
    return schema;
  }
}
