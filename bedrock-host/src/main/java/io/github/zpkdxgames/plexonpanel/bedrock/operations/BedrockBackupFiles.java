package io.github.zpkdxgames.plexonpanel.bedrock.operations;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fixed Java NIO archive scope. No shell, external archiver, or user-provided include list. */
final class BedrockBackupFiles {
    private static final List<String> INCLUDES = List.of("worlds", "allowlist.json", "permissions.json");
    private static final int MAX_ENTRIES = 100_000;
    private static final int MAX_DEPTH = 32;
    private static final long MAX_SCAN_NANOS = Duration.ofMinutes(20).toNanos();

    private static final Pattern LEVEL_DB_CURRENT = Pattern.compile("worlds/[^/]+/db/CURRENT");

    private record Scan(long bytes, int entries, String rootKey) {}

    private BedrockBackupFiles() {}

    static String preflight(Path dataRoot, Path backupRoot, long limit) throws IOException {
        Scan scan = collect(dataRoot, limit, null);
        long required = scan.bytes() + scan.bytes() / 20 + scan.entries() * 1024L + 1024 * 1024;
        if (required > Files.getFileStore(backupRoot).getUsableSpace()) {
            throw new IOException("Insufficient backup storage");
        }
        return scan.rootKey();
    }

    static String archive(Path dataRoot, Path archive, long limit, String expectedRootKey) throws IOException {
        MessageDigest digest = sha256();
        try (var file = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW);
             var hashing = new DigestOutputStream(file, digest);
             var zip = new ZipOutputStream(hashing)) {
            if (!expectedRootKey.equals(collect(dataRoot, limit, zip).rootKey())) {
                throw new IOException("Bedrock data root changed during backup");
            }
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        force(archive);
        if (!hash.equals(hash(archive))) {
            throw new IOException("Backup checksum verification failed");
        }
        return hash;
    }

    static void force(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            channel.force(true);
        }
    }

    static String hash(Path archive) throws IOException {
        MessageDigest digest = sha256();
        try (var source = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS);
             var hashing = new DigestInputStream(source, digest)) {
            byte[] buffer = new byte[64 * 1024];
            long started = System.nanoTime();
            while (hashing.read(buffer) != -1) {
                checkDeadline(started);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static Scan collect(Path dataRoot, long limit, ZipOutputStream archive) throws IOException {
        if (!dataRoot.equals(dataRoot.toRealPath())) {
            throw new IOException("Bedrock data root is not canonical");
        }
        Walk walk = new Walk(limit, archive);
        String rootKey;
        try (DirectoryStream<Path> opened = Files.newDirectoryStream(dataRoot)) {
            if (!(opened instanceof SecureDirectoryStream<Path> source)) {
                throw new IOException("Secure Linux directory traversal is required");
            }
            rootKey = identity(source);
            for (String include : INCLUDES) {
                Path name = dataRoot.getFileSystem().getPath(include);
                BasicFileAttributes attributes;
                try {
                    attributes = attributes(source, name);
                } catch (java.nio.file.NoSuchFileException absent) {
                    if (include.equals("worlds")) {
                        throw new IOException("Bedrock worlds directory is missing");
                    }
                    continue;
                }
                if (attributes.isDirectory() != include.equals("worlds")) {
                    throw new IOException("Unexpected Bedrock backup entry type");
                }
                walk.visit(source, name, include, attributes, 1);
            }
        }
        if (!walk.levelDb) {
            throw new IOException("No Bedrock LevelDB world was found");
        }
        return new Scan(walk.bytes, walk.entries, rootKey);
    }

    static String rootIdentity(Path path) throws IOException {
        if (!path.equals(path.toRealPath())) {
            throw new IOException("Bedrock data root is not canonical");
        }
        try (var directory = Files.newDirectoryStream(path)) {
            if (!(directory instanceof SecureDirectoryStream<Path> secure)) {
                throw new IOException("Secure Linux directory traversal is required");
            }
            return identity(secure);
        }
    }

    private static String identity(SecureDirectoryStream<Path> directory) throws IOException {
        Object fileKey = directory.getFileAttributeView(BasicFileAttributeView.class).readAttributes().fileKey();
        if (fileKey == null || !validIdentity(fileKey.toString())) {
            throw new IOException("Bedrock filesystem identity is unavailable");
        }
        return fileKey.toString();
    }

    static boolean validIdentity(String value) {
        return value.matches("\\(dev=[0-9a-f]{1,16},ino=-?[0-9]{1,20}\\)");
    }

    private static BasicFileAttributes attributes(SecureDirectoryStream<Path> directory, Path name) throws IOException {
        return directory.getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                .readAttributes();
    }

    private static void checkDeadline(long started) throws IOException {
        if (System.nanoTime() - started >= MAX_SCAN_NANOS || Thread.currentThread().isInterrupted()) {
            throw new IOException("Archive operation deadline exceeded");
        }
    }

    private static final class Walk {
        private final long limit;
        private final ZipOutputStream archive;
        private final byte[] buffer;
        private final long started = System.nanoTime();
        private long bytes;
        private int entries;
        private boolean levelDb;

        private Walk(long limit, ZipOutputStream archive) {
            this.limit = limit;
            this.archive = archive;
            this.buffer = archive == null ? null : new byte[64 * 1024];
        }

        private void visit(SecureDirectoryStream<Path> parent, Path name, String relative,
                           BasicFileAttributes before, int depth) throws IOException {
            checkDeadline(started);
            if (++entries > MAX_ENTRIES || depth > MAX_DEPTH || relative.length() > 1024) {
                throw new IOException("Bedrock backup tree exceeds bounded traversal policy");
            }
            if (before.isDirectory()) {
                writeDirectory(parent, name, relative, depth);
                return;
            }
            if (!before.isRegularFile() || before.size() > limit - bytes) {
                throw new IOException("Unsafe or oversized Bedrock backup entry");
            }
            bytes += before.size();
            if (LEVEL_DB_CURRENT.matcher(relative).matches() && before.size() > 0) {
                levelDb = true;
            }
            if (archive != null) {
                writeFile(parent, name, relative, before);
            }
        }

        private void writeDirectory(SecureDirectoryStream<Path> parent, Path name, String relative,
                                    int depth) throws IOException {
            if (archive != null) {
                archive.putNextEntry(new ZipEntry(relative + "/"));
                archive.closeEntry();
            }
            try (var child = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path path : child) {
                    Path childName = path.getFileName();
                    String segment = childName.toString();
                    if (segment.equals(".") || segment.equals("..") || segment.contains("\\")
                            || segment.contains(":") || segment.codePoints().anyMatch(Character::isISOControl)) {
                        throw new IOException("Unsafe Bedrock backup entry name");
                    }
                    visit(child, childName, relative + "/" + segment, attributes(child, childName), depth + 1);
                }
            } catch (DirectoryIteratorException failed) {
                throw failed.getCause();
            }
        }

        private void writeFile(SecureDirectoryStream<Path> parent, Path name, String relative,
                               BasicFileAttributes before) throws IOException {
            archive.putNextEntry(new ZipEntry(relative));
            try (var channel = parent.newByteChannel(name, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream input = Channels.newInputStream(channel)) {
                long copied = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    checkDeadline(started);
                    copied += count;
                    if (copied > before.size()) {
                        throw new IOException("Bedrock file changed during snapshot");
                    }
                    archive.write(buffer, 0, count);
                }
                BasicFileAttributes after = attributes(parent, name);
                if (copied != before.size() || after.size() != before.size()
                        || !Objects.equals(after.fileKey(), before.fileKey())
                        || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
                    throw new IOException("Bedrock file changed during snapshot");
                }
            }
            archive.closeEntry();
        }
    }
}
