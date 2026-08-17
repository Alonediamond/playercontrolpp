package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.MaLiLibCompat;
import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.util.AtomicFiles;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 录制的持久化：一个 GUI 可以瞬间读完的小 JSON 索引，加上每条录制一个装主体数据的 NBT
 * {@code .pcr} 文件。
 *
 * <pre>
 * config/playercontrolpp/recordings/
 *   index.json      只有元数据（id、名称、时长、维度）
 *   record_001.pcr  段 + 关键帧，NBT 二进制，gzip 压缩
 * </pre>
 *
 * <p>所有磁盘操作都在同一个后台线程上，且每次写入都是原子的，保存中途崩溃不会把索引截断。
 * 录制与回放的 tick 都从这里走，对外是一个 {@link ClientFeature}。
 */
public class RecordingManager implements ClientFeature {
    private static final RecordingManager INSTANCE = new RecordingManager();
    private static final String RECORDINGS_DIR = "playercontrolpp/recordings";
    private static final String INDEX_FILE = "index.json";

    private final List<RecordingFile> recordings = new ArrayList<>();
    private final InputRecorder recorder = new InputRecorder();
    private final InputPlayer player = new InputPlayer();
    private boolean loaded;

    /**
     * 所有读写共用一个守护线程。守护 = 退出时不会拖着游戏不放；单线程 = 保存不会互相交错；
     * 复用 = 反复录制不会每次都开一个线程。
     */
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PCpp-RecordingIO");
        thread.setDaemon(true);
        return thread;
    });

    private RecordingManager() {}

    public static RecordingManager getInstance() { return INSTANCE; }

    public List<RecordingFile> getRecordings() { return Collections.unmodifiableList(recordings); }
    public InputRecorder getRecorder() { return recorder; }
    public InputPlayer getPlayer() { return player; }

    // --- Directory helpers ---

    private Path getRecordingsDir() {
        return MaLiLibCompat.configDirectory().resolve(RECORDINGS_DIR);
    }

    private Path getIndexFile() {
        return getRecordingsDir().resolve(INDEX_FILE);
    }

    private Path getRecordingFile(String id) {
        return getRecordingsDir().resolve(id + ".pcr");
    }

    // ---- 索引加载（只给 GUI 用，不含段数据）----

    /**
     * 读索引。可以反复调用；只有成功读取才会被记住，所以一次偶发失败不会让列表在整局里一直空着。
     */
    public void loadRecordings() {
        if (loaded) return;

        Path indexFile = getIndexFile();
        if (!Files.exists(indexFile) || Files.isDirectory(indexFile)) {
            loaded = true; // “没东西可读”也是一个有效的终结果
            return;
        }

        List<RecordingFile> parsed = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(indexFile, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                throw new IOException("index.json does not contain a JSON object");
            }
            JsonObject root = element.getAsJsonObject();
            if (root.has("recordings")) {
                JsonArray arr = root.getAsJsonArray("recordings");
                for (int i = 0; i < arr.size(); i++) {
                    parsed.add(RecordingFile.fromIndexJson(arr.get(i).getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            // 不要标记为已加载：紧接着的 saveIndex() 会覆盖掉用户可能还能修的文件。
            // 改为把它挪到一边，什么都不会被静默丢掉。
            Path quarantined = AtomicFiles.quarantine(indexFile);
            Playercontrolpp.LOGGER.warn("Failed to read the recording index; moved it to {}",
                    quarantined != null ? quarantined.getFileName() : "(move failed)", e);
            MessageUtil.sendActionBar(Minecraft.getInstance(), "playercontrolpp.message.recording.index_corrupt");
            return;
        }

        // 剔除 .pcr 已丢失的条目，GUI 不会列出根本播不了的录制。
        parsed.removeIf(rf -> {
            boolean missing = !Files.exists(getRecordingFile(rf.getId()));
            if (missing) {
                Playercontrolpp.LOGGER.warn("Recording {} has no data file; dropping it from the index",
                        rf.getId());
            }
            return missing;
        });

        recordings.addAll(parsed);
        loaded = true;
    }

    private void saveIndex() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (RecordingFile rf : recordings) {
            arr.add(rf.toIndexJson());
        }
        root.add("recordings", arr);

        String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
        try {
            AtomicFiles.writeString(getIndexFile(), json);
        } catch (IOException e) {
            Playercontrolpp.LOGGER.warn("Failed to save the recording index", e);
        }
    }

    // --- Add / Remove ---

    public void addRecording(RecordingFile rec) {
        rec.setId(nextId());
        recordings.add(rec);

        // 在客户端线程序列化，在 IO 线程写盘，且只有数据文件真的落地后才添加索引条目——
        // 否则中途崩溃会留下一条指向空文件的索引，表现为「点播放没反应」。
        CompoundTag data = rec.toNbt();
        Path file = getRecordingFile(rec.getId());
        io.execute(() -> {
            try {
                RecordingFile.write(data, file);
                Minecraft.getInstance().execute(this::saveIndex);
            } catch (IOException e) {
                Playercontrolpp.LOGGER.warn("Failed to save recording {}", rec.getId(), e);
                Minecraft.getInstance().execute(() -> {
                    recordings.remove(rec);
                    MessageUtil.sendActionBar(Minecraft.getInstance(),
                            "playercontrolpp.message.recording.save_failed");
                });
            }
        });
    }

    /** @return 下一个空闲的 {@code record_NNN} id。 */
    private String nextId() {
        int maxId = 0;
        for (RecordingFile r : recordings) {
            String rid = r.getId();
            if (rid != null && rid.startsWith("record_")) {
                try {
                    maxId = Math.max(maxId, Integer.parseInt(rid.substring("record_".length())));
                } catch (NumberFormatException ignored) {
                    // 手工改过或外来的 id；它只是不参与编号而已。
                }
            }
        }
        return String.format("record_%03d", maxId + 1);
    }

    public void removeRecording(RecordingFile rec) {
        recordings.remove(rec);
        Path file = getRecordingFile(rec.getId());
        io.execute(() -> {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                // 索引条目已经删掉了，所以这里会在磁盘上留下一个孤儿 .pcr。
                // loadRecordings() 清不掉它（它只清另一个方向），所以日志要打得够显眼，
                // 让「磁盘越来越满」的反馈有个说法。
                Playercontrolpp.LOGGER.warn("Could not delete recording data file {}", file, e);
            }
        });
        saveIndex();
    }

    // ---- 单个文件的读写（NBT 二进制）----

    /**
     * 在别的线程上加载完整录制数据，然后回到客户端线程交给 {@code onLoaded}。
     *
     * <p>{@code NbtIo.readCompressed} 意味着 gzip 解压加一次完整 NBT 解析——十分钟的录制就是几千个段。
     * 早先直接在播放按钮的回调里做，会让渲染线程卡出可见的一顿。
     *
     * @param onLoaded 在客户端线程回调；加载失败时参数为 {@code null}
     */
    public void loadRecordingFileAsync(String id, Consumer<RecordingFile> onLoaded) {
        Path file = getRecordingFile(id);
        io.execute(() -> {
            RecordingFile result = readOrNull(file);
            Minecraft.getInstance().execute(() -> onLoaded.accept(result));
        });
    }

    private RecordingFile readOrNull(Path file) {
        if (!Files.exists(file) || Files.isDirectory(file)) {
            Playercontrolpp.LOGGER.warn("Recording data file {} is missing", file);
            return null;
        }
        try {
            return RecordingFile.readFromFile(file);
        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Corrupt recording data file {}", file, e);
            return null;
        }
    }

    /** GUI 改了名字或录制列表之后把索引写回磁盘。 */
    public void saveRecordings() {
        saveIndex();
    }

    // --- Tick ---

    @Override
    public void onClientTick(Minecraft client) {
        recorder.tick(client);
        player.tick(client);
    }

    @Override
    public boolean isActive() {
        return recorder.isRecording() || player.isPlaying();
    }
}
