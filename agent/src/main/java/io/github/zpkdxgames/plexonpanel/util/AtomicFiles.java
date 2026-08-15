package io.github.zpkdxgames.plexonpanel.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public final class AtomicFiles {
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
    );

    private AtomicFiles() {
    }

    public static void writeUtf8(Path destination, String content) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temporary;
        try {
            temporary = Files.createTempFile(
                parent,
                destination.getFileName().toString(),
                ".tmp",
                PosixFilePermissions.asFileAttribute(OWNER_ONLY)
            );
        } catch (UnsupportedOperationException error) {
            temporary = Files.createTempFile(parent, destination.getFileName().toString(), ".tmp");
        }
        boolean moved = false;
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
