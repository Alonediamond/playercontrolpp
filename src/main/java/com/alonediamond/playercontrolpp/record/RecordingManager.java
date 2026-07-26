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
 * Recording persistence: a small JSON index the GUI can read instantly, plus one NBT
 * {@code .pcr} file per recording holding the bulk data.
 *
 * <pre>
 * config/playercontrolpp/recordings/
 *   index.json      metadata only (id, name, duration, dimension)
 *   record_001.pcr  segments + keyframes, NBT binary, gzipped
 * </pre>
 *
 * <p>All disk work happens on a single background thread and every write is atomic, so a crash
 * mid-save cannot leave the index truncated. Both the recording and the playback tick run from
 * here as one {@link ClientFeature}.
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
     * One daemon thread for every read and write. Daemon so it cannot hold the game open on exit;
     * single so saves cannot interleave; reused so recording repeatedly does not spawn a thread
     * each time.
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

    // --- Index loading (GUI only — no segment data) ---

    /**
     * Read the index. Safe to call repeatedly; only a successful read is remembered, so a
     * transient failure does not lock the list empty for the rest of the session.
     */
    public void loadRecordings() {
        if (loaded) return;

        Path indexFile = getIndexFile();
        if (!Files.exists(indexFile) || Files.isDirectory(indexFile)) {
            loaded = true; // nothing to read is a valid, final answer
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
            // Do not mark loaded: the very next saveIndex() would overwrite a file the user may
            // still be able to repair. Move it aside instead so nothing is lost silently.
            Path quarantined = AtomicFiles.quarantine(indexFile);
            Playercontrolpp.LOGGER.warn("Failed to read the recording index; moved it to {}",
                    quarantined != null ? quarantined.getFileName() : "(move failed)", e);
            MessageUtil.sendActionBar(Minecraft.getInstance(), "playercontrolpp.message.recording.index_corrupt");
            return;
        }

        // Drop entries whose .pcr went missing, so the GUI never offers a recording that
        // cannot play.
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

        // Serialize on the client thread, write on the IO thread, and only add the index entry
        // once the data file is really there — otherwise a crash in between leaves an index
        // entry pointing at nothing, which shows up as "Play does nothing".
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

    /** @return the next free {@code record_NNN} id. */
    private String nextId() {
        int maxId = 0;
        for (RecordingFile r : recordings) {
            String rid = r.getId();
            if (rid != null && rid.startsWith("record_")) {
                try {
                    maxId = Math.max(maxId, Integer.parseInt(rid.substring("record_".length())));
                } catch (NumberFormatException ignored) {
                    // Hand-edited or foreign id; it just does not take part in numbering.
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
                // The index entry is already gone, so this leaves an orphan .pcr on disk.
                // loadRecordings() cannot clean it up (it only prunes the other direction), so
                // log it loudly enough that a user reporting "disk filling up" has an answer.
                Playercontrolpp.LOGGER.warn("Could not delete recording data file {}", file, e);
            }
        });
        saveIndex();
    }

    // --- Individual file I/O (NBT binary) ---

    /**
     * Load full recording data off-thread and hand it to {@code onLoaded} back on the client
     * thread.
     *
     * <p>{@code NbtIo.readCompressed} means gzip inflate plus a full NBT parse — for a ten-minute
     * recording that is thousands of segments. Doing it inline in the Play button handler stalled
     * the render thread for a visible hitch.
     *
     * @param onLoaded called on the client thread, with {@code null} if loading failed
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

    /** Persist the index after the GUI edited names or the recording list. */
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
