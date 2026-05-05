package ax.xz.mri.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file writer.
 *
 * <p>All writes go to a sibling {@code <name>.tmp} file first, then are
 * renamed onto the target with {@link StandardCopyOption#ATOMIC_MOVE}. If the
 * JVM dies mid-write, either the previous file remains intact or the new file
 * lands fully — never a half-written one.
 *
 * <p>Falls back to non-atomic replace if the filesystem doesn't support
 * atomic moves (rare on POSIX; possible across mount points or on certain
 * networked filesystems).
 */
public final class AtomicWriter {
    private AtomicWriter() {}

    public static void writeBytes(Path target, byte[] bytes) throws IOException {
        if (target.getParent() != null) Files.createDirectories(target.getParent());
        var tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void writeString(Path target, String content) throws IOException {
        writeBytes(target, content.getBytes(StandardCharsets.UTF_8));
    }
}
