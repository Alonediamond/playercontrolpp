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
 * 崩溃安全的文件写入。
 *
 * <p>直接对目标文件开流会立刻把它截断，序列化中途抛异常或游戏崩溃就只剩一个空文件，数据没了。
 * 这里一律先写同目录的 {@code .tmp} 再替换目标，读者看到的永远是完整的旧内容或完整的新内容。
 */
public final class AtomicFiles {

    private static final String TMP_SUFFIX = ".tmp";

    private AtomicFiles() {}

    /** 允许抛 {@link IOException} 的写入回调。 */
    public interface IoSink<T> {
        void accept(T target) throws IOException;
    }

    /** 写 UTF-8 文本，原子替换 {@code target}。 */
    public static void writeString(Path target, String content) throws IOException {
        writeVia(target, tmp -> {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                writer.write(content);
            }
        });
    }

    /** 写字节流，原子替换 {@code target}。 */
    public static void writeStream(Path target, IoSink<OutputStream> body) throws IOException {
        writeVia(target, tmp -> {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                body.accept(out);
            }
        });
    }

    /**
     * 把临时路径交给 {@code body} 自己写，写完再挪到 {@code target}。
     * 给那些坚持自己拿 {@link Path} 写的 API 用，比如 {@code NbtIo.writeCompressed}。
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
            // 写失败不要留下残骸，否则下次写入会踩到它。
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // 没什么可做的，下次写入反正会覆盖。
            }
        }
    }

    /**
     * ATOMIC_MOVE 在同卷的 NTFS / ext4 上都可用，覆盖所有现实中的配置目录。
     * 文件系统拒绝时退化为普通替换——数据已经完整落盘，仍然远好于"先截断再写"。
     */
    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 把解析失败的文件挪走，避免下一次保存把用户还想手工抢救的数据静默覆盖掉。
     *
     * @return 挪到的新路径；挪不动时返回 {@code null}
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
