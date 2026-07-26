package com.alonediamond.playercontrolpp.util;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Crash-safe file writing.
 *
 * <p>Opening a {@code FileOutputStream} on the real target truncates it immediately, so an
 * exception halfway through serialization — or a crash — leaves the file empty and the data
 * gone. Everything here writes a sibling {@code .tmp} first and only then replaces the target,
 * so a reader ever sees either the old contents or the new ones, never a half-written file.
 */
public final class AtomicFiles {

    private static final String TMP_SUFFIX = ".tmp";

    private AtomicFiles() {}

    /** A writer that is allowed to throw {@link IOException}. */
    public interface IoSink<T> {
        void accept(T target) throws IOException;
    }

    /** Write UTF-8 text, replacing {@code target} atomically. */
    public static void writeString(Path target, String content) throws IOException {
        writeVia(target, tmp -> {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write(content);
            }
        });
    }

    /** Stream bytes, replacing {@code target} atomically. */
    public static void writeStream(Path target, IoSink<OutputStream> body) throws IOException {
        writeVia(target, tmp -> {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                body.accept(out);
            }
        });
    }

    /**
     * Hand {@code body} a temporary path to write, then move it onto {@code target}.
     * Use this for APIs that insist on writing a {@link Path} themselves, such as
     * {@code NbtIo.writeCompressed}.
     */
    public static void writeVia(Path target, IoSink<Path> body) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = target.resolveSibling(target.getFileName() + TMP_SUFFIX);
        try {
            body.accept(tmp);
            move(tmp, target);
        } finally {
            // A failed write must not leave debris behind for the next attempt to trip over.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Nothing useful to do; the next write overwrites it anyway.
            }
        }
    }

    /**
     * ATOMIC_MOVE works on NTFS and ext4 within one volume, which covers every realistic
     * config directory. Fall back to a plain replace when the filesystem refuses — still far
     * better than truncate-then-write, because the data is already fully on disk.
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Move a file that failed to parse out of the way, so the next save cannot silently
     * overwrite data the user might still want to recover by hand.
     *
     * @return the path the file was moved to, or {@code null} if it could not be moved
     */
    public static Path quarantine(Path file) {
        for (int n = 1; n <= 100; n++) {
            Path candidate = file.resolveSibling(file.getFileName() + ".corrupt-" + n);
            if (Files.exists(candidate)) continue;
            try {
                Files.move(file, candidate);
                return candidate;
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }
}
