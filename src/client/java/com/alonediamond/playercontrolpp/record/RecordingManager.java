package com.alonediamond.playercontrolpp.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;
import fi.dy.masa.malilib.util.FileUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages recording persistence with a lightweight index for fast GUI loading.
 *
 * Storage layout (under config/playercontrolpp/recordings/):
 *   index.json       — recording metadata only (name, duration, HP flag, dimension)
 *   record_001.json  — full recording data (segments + keyframes), loaded on demand
 *   record_002.json  — ...
 *
 * The GUI reads only index.json. Full recording data is loaded only when the
 * user clicks Play. Deleting a recording removes its individual file and
 * updates the index without touching other recordings.
 */
public class RecordingManager {
    private static final RecordingManager INSTANCE = new RecordingManager();
    private static final String RECORDINGS_DIR = "playercontrolpp/recordings";
    private static final String INDEX_FILE = "index.json";

    private final List<RecordingFile> recordings = new ArrayList<>();
    private final InputRecorder recorder = new InputRecorder();
    private final InputPlayer player = new InputPlayer();
    private boolean loaded;

    private RecordingManager() {}

    public static RecordingManager getInstance() { return INSTANCE; }

    public List<RecordingFile> getRecordings() { return Collections.unmodifiableList(recordings); }
    public InputRecorder getRecorder() { return recorder; }
    public InputPlayer getPlayer() { return player; }

    // --- Directory helpers ---

    private Path getRecordingsDir() {
        return FileUtils.getConfigDirectoryAsPath().resolve(RECORDINGS_DIR);
    }

    private Path getIndexFile() {
        return getRecordingsDir().resolve(INDEX_FILE);
    }

    private Path getRecordingFile(String id) {
        return getRecordingsDir().resolve(id + ".json");
    }

    // --- Index loading (GUI only — no segment data) ---

    public void loadRecordings() {
        if (loaded) return;
        loaded = true;

        Path dir = getRecordingsDir();
        Path indexFile = getIndexFile();
        if (!Files.exists(indexFile) || Files.isDirectory(indexFile)) return;

        try (Reader reader = new InputStreamReader(
                new FileInputStream(indexFile.toFile()), StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            if (root.has("recordings")) {
                JsonArray arr = root.getAsJsonArray("recordings");
                for (int i = 0; i < arr.size(); i++) {
                    recordings.add(RecordingFile.fromIndexJson(arr.get(i).getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            System.err.println("[PlayerControl++] Failed to load recording index: " + e.getMessage());
        }
    }

    private void saveIndex() {
        Path dir = getRecordingsDir();
        try { Files.createDirectories(dir); } catch (Exception ignored) { return; }

        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (RecordingFile rf : recordings) {
            arr.add(rf.toIndexJson());
        }
        root.add("recordings", arr);

        Path file = getIndexFile();
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
        } catch (Exception e) {
            System.err.println("[PlayerControl++] Failed to save recording index: " + e.getMessage());
        }
    }

    // --- Add / Remove ---

    public void addRecording(RecordingFile rec) {
        // Generate sequential ID
        int maxId = 0;
        for (RecordingFile r : recordings) {
            String rid = r.getId();
            if (rid != null && rid.startsWith("record_")) {
                try { maxId = Math.max(maxId, Integer.parseInt(rid.substring(7))); }
                catch (NumberFormatException ignored) {}
            }
        }
        rec.setId(String.format("record_%03d", maxId + 1));

        recordings.add(rec);
        saveRecordingFile(rec);
        saveIndex();
    }

    public void removeRecording(RecordingFile rec) {
        recordings.remove(rec);
        try {
            Files.deleteIfExists(getRecordingFile(rec.getId()));
        } catch (Exception ignored) {}
        saveIndex();
    }

    // --- Individual file I/O ---

    public void saveRecordingFile(RecordingFile rec) {
        Path dir = getRecordingsDir();
        try { Files.createDirectories(dir); } catch (Exception ignored) { return; }

        Path file = getRecordingFile(rec.getId());
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(file.toFile()), StandardCharsets.UTF_8)) {
            new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                    .toJson(rec.toFullJson(), writer);
        } catch (Exception e) {
            System.err.println("[PlayerControl++] Failed to save recording: " + e.getMessage());
        }
    }

    /**
     * Load full recording data (segments + keyframes) for playback.
     * Called on demand when the user clicks Play.
     */
    public RecordingFile loadRecordingFile(String id) {
        Path file = getRecordingFile(id);
        if (!Files.exists(file) || Files.isDirectory(file)) return null;

        try (Reader reader = new InputStreamReader(
                new FileInputStream(file.toFile()), StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) return null;
            return RecordingFile.fromFullJson(element.getAsJsonObject());
        } catch (Exception e) {
            System.err.println("[PlayerControl++] Failed to load recording: " + e.getMessage());
            return null;
        }
    }

    // Backward-compatible save for RecordingListGui close
    public void saveRecordings() {
        saveIndex();
    }

    // --- Tick ---

    public void onClientTick(net.minecraft.client.MinecraftClient client) {
        recorder.tick(client);
        player.tick(client);
    }
}
