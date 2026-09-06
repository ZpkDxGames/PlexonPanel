package io.github.zpkdxgames.plexonpanel.files;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;

public final class SafeFiles {
  public static final int MAX_TEXT_BYTES = 24576;
  public static final int CHUNK_BYTES = 16384;
  public static final long MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
  private final PathPolicy policy;
  private final Set<String> writableRoots;

  public SafeFiles(PathPolicy policy) {
    this(policy, policy.roots());
  }

  public SafeFiles(PathPolicy policy, Set<String> writableRoots) {
    this.policy = policy;
    this.writableRoots = Set.copyOf(writableRoots);
  }

  private void requireWritable(String root) {
    if (!writableRoots.contains(root)) throw new SecurityException("ROOT_READ_ONLY");
  }

  public PathPolicy policy() {
    return policy;
  }

  public Map<String, Object> list(String root, String path, int page) throws IOException {
    if (page < 0 || page > 100) throw new IllegalArgumentException("Invalid directory page");
    List<Map<String, Object>> entries = new ArrayList<>();
    int scanned = 0;
    try (var dir = policy.directory(root, path)) {
      for (Path entry : dir) {
        if (++scanned > 10000)
          throw new IOException("Directory exceeds 10000 entries; narrow the root");
        String name = entry.getFileName().toString();
        try {
          policy.parts(root, path.isEmpty() ? name : path + "/" + name, false);
        } catch (SecurityException denied) {
          continue;
        }
        var attr =
            dir.getFileAttributeView(
                    Path.of(name), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
        if (!attr.isDirectory() && !attr.isRegularFile()) continue;
        entries.add(
            Map.of(
                "name",
                name,
                "directory",
                attr.isDirectory(),
                "size",
                attr.size(),
                "modifiedAt",
                attr.lastModifiedTime().toMillis(),
                "editable",
                attr.isRegularFile() && policy.editable(name) && attr.size() <= MAX_TEXT_BYTES));
      }
    }
    entries.sort(
        Comparator.comparing(e -> e.get("name").toString(), String.CASE_INSENSITIVE_ORDER));
    int start = Math.min(entries.size(), page * 100), end = Math.min(entries.size(), start + 100);
    return Map.of(
        "entries",
        entries.subList(start, end),
        "page",
        page,
        "hasMore",
        end < entries.size(),
        "roots",
        new TreeSet<>(policy.roots()));
  }

  public Map<String, Object> read(String root, String path) throws IOException {
    if (!policy.editable(path) && !path.toLowerCase(Locale.ROOT).endsWith(".sql"))
      throw new SecurityException("BINARY_EDIT_DENIED");
    try (var h = policy.parent(root, path)) {
      byte[] bytes = readBytes(h, MAX_TEXT_BYTES);
      return Map.of(
          "content",
          text(bytes),
          "sha256",
          hash(bytes),
          "bytes",
          bytes.length,
          "editable",
          policy.editable(path));
    }
  }

  public synchronized Map<String, Object> write(
      String root, String path, String content, String expectedHash, boolean create)
      throws IOException {
    requireWritable(root);
    if (!policy.editable(path)) throw new SecurityException("EXTENSION_DENIED");
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_TEXT_BYTES || content.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Text file exceeds 24 KiB or contains NUL");
    if (path.toLowerCase(Locale.ROOT).endsWith(".json")) {
      var reader = new com.google.gson.stream.JsonReader(new StringReader(content));
      reader.setStrictness(com.google.gson.Strictness.STRICT);
      com.google.gson.JsonParser.parseReader(reader);
      if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT)
        throw new IllegalArgumentException("Invalid JSON");
    }
    try (var h = policy.parent(root, path)) {
      String oldHash = "";
      if (!create) {
        oldHash = hash(readBytes(h, MAX_TEXT_BYTES));
        if (expectedHash == null || !oldHash.equals(expectedHash)) throw new ConflictException();
      } else {
        try {
          h.directory()
              .getFileAttributeView(
                  h.name(), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
              .readAttributes();
          throw new ConflictException();
        } catch (NoSuchFileException expected) {
          /* Creation precondition holds. */
        }
      }
      Path temporary = Path.of(".plexonpanel-write-" + UUID.randomUUID());
      boolean moved = false;
      try {
        try (var channel =
            h.directory()
                .newByteChannel(
                    temporary,
                    Set.of(
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rw-------")))) {
          ByteBuffer buffer = ByteBuffer.wrap(bytes);
          while (buffer.hasRemaining()) channel.write(buffer);
          if (channel instanceof FileChannel file) file.force(true);
        }
        if (!create && !oldHash.equals(hash(readBytes(h, MAX_TEXT_BYTES))))
          throw new ConflictException();
        if (create) {
          // A no-replace hard-link commit is unavailable in SecureDirectoryStream. Reserve the
          // destination
          // with CREATE_NEW so concurrent panel creates cannot overwrite an existing file.
          try (var ignored =
              h.directory()
                  .newByteChannel(
                      h.name(),
                      Set.of(
                          StandardOpenOption.CREATE_NEW,
                          StandardOpenOption.WRITE,
                          LinkOption.NOFOLLOW_LINKS),
                      PosixFilePermissions.asFileAttribute(
                          PosixFilePermissions.fromString("rw-------")))) {}
        } else {
          var permissions =
              h.directory()
                  .getFileAttributeView(
                      h.name(), PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                  .readAttributes()
                  .permissions();
          h.directory()
              .getFileAttributeView(
                  temporary, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
              .setPermissions(permissions);
        }
        h.directory().move(temporary, h.directory(), h.name());
        moved = true;
        return Map.of(
            "oldHash",
            oldHash,
            "sha256",
            hash(bytes),
            "bytes",
            bytes.length,
            "restartHint",
            "Reload the owning plugin or restart Paper if required.");
      } finally {
        if (!moved) h.directory().deleteFile(temporary);
      }
    }
  }

  public synchronized Map<String, Object> delete(String root, String path, String expectedHash)
      throws IOException {
    requireWritable(root);
    if (!policy.editable(path)) throw new SecurityException("EXTENSION_DENIED");
    try (var h = policy.parent(root, path)) {
      String hash = hash(readBytes(h, MAX_TEXT_BYTES));
      if (!hash.equals(expectedHash)) throw new ConflictException();
      h.directory().deleteFile(h.name());
      return Map.of("oldHash", hash);
    }
  }

  public synchronized Map<String, Object> rename(
      String root, String path, String destination, String expectedHash) throws IOException {
    requireWritable(root);
    if (!policy.editable(path) || !policy.editable(destination))
      throw new SecurityException("EXTENSION_DENIED");
    // Restrict renames to the same directory; create a copy under an exclusive name before deleting
    // the source.
    try (var source = policy.parent(root, path);
        var target = policy.parent(root, destination)) {
      byte[] bytes = readBytes(source, MAX_TEXT_BYTES);
      if (!hash(bytes).equals(expectedHash)) throw new ConflictException();
      try (var channel =
          target
              .directory()
              .newByteChannel(
                  target.name(),
                  Set.of(
                      StandardOpenOption.CREATE_NEW,
                      StandardOpenOption.WRITE,
                      LinkOption.NOFOLLOW_LINKS),
                  PosixFilePermissions.asFileAttribute(
                      PosixFilePermissions.fromString("rw-------")))) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        if (channel instanceof FileChannel file) file.force(true);
      }
      if (!hash(readBytes(source, MAX_TEXT_BYTES)).equals(expectedHash))
        throw new ConflictException();
      source.directory().deleteFile(source.name());
      return Map.of("sha256", hash(bytes), "destination", destination);
    }
  }

  public byte[] download(String root, String path) throws IOException {
    try (var h = policy.parent(root, path)) {
      return readBytes(h, (int) MAX_DOWNLOAD_BYTES);
    }
  }

  private static byte[] readBytes(PathPolicy.Handle h, int maximum) throws IOException {
    var attrs =
        h.directory()
            .getFileAttributeView(h.name(), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            .readAttributes();
    if (!attrs.isRegularFile() || attrs.size() > maximum)
      throw new IOException("File is not regular or exceeds size limit");
    try (var channel =
        h.directory()
            .newByteChannel(h.name(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      while (channel.read(buffer) >= 0) {
        buffer.flip();
        if (bytes.size() + buffer.remaining() > maximum)
          throw new IOException("File grew beyond size limit");
        bytes.write(buffer.array(), 0, buffer.remaining());
        buffer.clear();
      }
      return bytes.toByteArray();
    }
  }

  private static String text(byte[] bytes) throws IOException {
    try {
      String s =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      if (s.indexOf('\0') >= 0) throw new IOException("Binary content");
      return s;
    } catch (CharacterCodingException e) {
      throw new IOException("File is not UTF-8 text");
    }
  }

  public static String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static final class ConflictException extends IOException {
    public ConflictException() {
      super("File changed since it was opened. Reload before saving.");
    }
  }
}
