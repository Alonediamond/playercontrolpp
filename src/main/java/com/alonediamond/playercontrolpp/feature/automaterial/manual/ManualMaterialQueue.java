package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.MaLiLibCompat;
import com.alonediamond.playercontrolpp.compat.RegistryCompat;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.util.AtomicFiles;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 手工备货的批次定义和本次运行进度。定义写盘，基线及失败状态只在本次客户端会话中存在。
 * 每批在启动时以当时的自有持有量为基线，把保存的新增量变成备货系统要求的目标总量。
 */
public final class ManualMaterialQueue {
    public enum State { IDLE, RUNNING, PAUSED, COMPLETE }

    public record ActiveBatch(int index, String name, List<MaterialItemEntry> targets) {}

    private static final ManualMaterialQueue INSTANCE = new ManualMaterialQueue();
    private static final String FILE_NAME = "playercontrolpp_manual_materials.json";
    private final Path fileOverride;
    private final List<ManualMaterialBatch> batches = new ArrayList<>();
    private final List<ManualMaterialBatch> view = Collections.unmodifiableList(batches);
    /** 已完成批次的需求总量；后续批次仍须保留，避免合成把此前成果吃掉。 */
    private final Map<Item, Integer> completedTargets = new LinkedHashMap<>();
    private boolean loaded;
    private boolean loadFailed;
    private State state = State.IDLE;
    private int activeIndex = -1;
    private ActiveBatch activeBatch;
    private String failure;

    private ManualMaterialQueue() { this.fileOverride = null; }

    /** 回归使用独立文件，避免触碰玩家配置。 */
    ManualMaterialQueue(Path file) { this.fileOverride = file; }

    public static ManualMaterialQueue getInstance() { return INSTANCE; }

    public List<ManualMaterialBatch> batches() { load(); return view; }

    public State state() { return state; }

    public ActiveBatch activeBatch() { return activeBatch; }

    public int activeIndex() { return activeIndex; }

    public String failure() { return failure; }
    public Map<Item, Integer> completedTargets() { return Map.copyOf(completedTargets); }

    /** 只备原料时仅替换本次运行目标，文件中的成品定义始终保留。 */
    public void replaceActiveTargets(List<MaterialItemEntry> targets) {
        if (state != State.RUNNING || activeBatch == null) throw new IllegalStateException("manual queue is not running");
        activeBatch = new ActiveBatch(activeBatch.index(), activeBatch.name(), List.copyOf(targets));
    }

    public boolean isEditingAllowed() { return state != State.RUNNING && state != State.PAUSED; }

    public ManualMaterialBatch addBatch(String name) {
        requireEditing();
        ManualMaterialBatch batch = new ManualMaterialBatch(name);
        batches.add(batch);
        return batch;
    }

    public boolean removeBatch(ManualMaterialBatch batch) {
        requireEditing();
        return batches.remove(batch);
    }

    public boolean moveBatch(ManualMaterialBatch batch, int offset) {
        requireEditing();
        int from = batches.indexOf(batch);
        int to = from + offset;
        if (from < 0 || to < 0 || to >= batches.size()) return false;
        Collections.swap(batches, from, to);
        return true;
    }

    /** 可重入；损坏文件隔离后保留内存中的定义，避免意外覆盖。 */
    public void load() {
        if (loaded) return;
        Path file = file();
        if (!Files.exists(file)) {
            loaded = true;
            return;
        }
        if (Files.isDirectory(file)) {
            loaded = true;
            loadFailed = true;
            Playercontrolpp.LOGGER.warn("Manual materials path is a directory: {}", file);
            return;
        }
        List<ManualMaterialBatch> parsed = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            if (root.get("format").getAsInt() != 1) throw new IOException("unsupported manual queue format");
            JsonArray array = root.getAsJsonArray("batches");
            if (array == null) throw new IOException("missing batches");
            for (JsonElement batchElement : array) {
                JsonObject object = batchElement.getAsJsonObject();
                ManualMaterialBatch batch = new ManualMaterialBatch(object.get("name").getAsString());
                JsonArray items = object.getAsJsonArray("items");
                if (items == null) throw new IOException("missing items");
                for (JsonElement itemElement : items) {
                    JsonObject row = itemElement.getAsJsonObject();
                    String id = row.get("id").getAsString();
                    Identifier.parse(id);
                    int amount = row.get("add").getAsInt();
                    if (batch.amount(id) != 0) throw new IOException("duplicate item: " + id);
                    batch.setAmount(id, amount);
                }
                parsed.add(batch);
            }
        } catch (Exception e) {
            Path quarantined = AtomicFiles.quarantine(file);
            Playercontrolpp.LOGGER.warn("Failed to read manual materials; moved file to {}",
                    quarantined == null ? "(move failed)" : quarantined.getFileName(), e);
            // 无法隔离时禁止后续保存覆盖原文件；隔离成功则允许创建新的定义文件。
            loadFailed = quarantined == null;
            loaded = true;
            return;
        }
        batches.clear();
        batches.addAll(parsed);
        loaded = true;
    }

    /** 只保存队列定义，绝不保存运行基线。 */
    public boolean save() {
        load();
        if (loadFailed) return false;
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        JsonArray array = new JsonArray();
        for (ManualMaterialBatch batch : batches) {
            JsonObject object = new JsonObject();
            object.addProperty("name", batch.name());
            JsonArray items = new JsonArray();
            for (Map.Entry<String, Integer> entry : batch.amounts().entrySet()) {
                JsonObject row = new JsonObject();
                row.addProperty("id", entry.getKey());
                row.addProperty("add", entry.getValue());
                items.add(row);
            }
            object.add("items", items);
            array.add(object);
        }
        root.add("batches", array);
        try {
            AtomicFiles.writeString(file(), new GsonBuilder().setPrettyPrinting().create().toJson(root));
            return true;
        } catch (IOException e) {
            Playercontrolpp.LOGGER.warn("Failed to save manual materials", e);
            return false;
        }
    }

    /** 从第一批开始。空批次跳过；无可执行批次则返回 false。 */
    public boolean start(ToIntFunction<Item> countHeld) {
        load();
        if (state == State.RUNNING || state == State.PAUSED) return false;
        state = State.IDLE;
        activeIndex = -1;
        activeBatch = null;
        failure = null;
        completedTargets.clear();
        return activateNext(0, countHeld) && state == State.RUNNING;
    }

    /** 失败后停在原批次，保留原基线；完成时才重新快照下一批。 */
    public boolean resume(ToIntFunction<Item> countHeld) {
        if (state != State.PAUSED) return false;
        if (activeBatch == null) return activateNext(activeIndex, countHeld);
        state = State.RUNNING;
        failure = null;
        return true;
    }

    /** 返回 true 表示本批已完成；下一批目标会在同一调用中建立。 */
    public boolean completeActiveBatch(ToIntFunction<Item> countHeld) {
        if (state != State.RUNNING || activeBatch == null) return false;
        for (MaterialItemEntry entry : activeBatch.targets()) {
            if (countHeld.applyAsInt(entry.item) < entry.neededCount) return false;
        }
        for (MaterialItemEntry entry : activeBatch.targets()) {
            completedTargets.merge(entry.item, entry.neededCount, Math::max);
        }
        return activateNext(activeIndex + 1, countHeld);
    }

    public void failActiveBatch(String reason) {
        if (state != State.RUNNING) return;
        state = State.PAUSED;
        failure = reason == null ? "failed" : reason;
    }

    /** 停止或切换世界时调用；不会丢失编辑好的批次。 */
    public void stop() {
        state = State.IDLE;
        activeIndex = -1;
        activeBatch = null;
        failure = null;
        completedTargets.clear();
    }

    private boolean activateNext(int from, ToIntFunction<Item> countHeld) {
        for (int i = from; i < batches.size(); i++) {
            ManualMaterialBatch batch = batches.get(i);
            if (batch.isEmpty()) continue;
            Map<Item, Integer> totals = new LinkedHashMap<>(completedTargets);
            for (Map.Entry<String, Integer> row : batch.amounts().entrySet()) {
                Item item;
                try { item = RegistryCompat.item(Identifier.parse(row.getKey())); }
                catch (RuntimeException e) { item = null; }
                if (item == null || !BuiltInRegistries.ITEM.getKey(item).toString().equals(row.getKey())) {
                    activeIndex = i;
                    activeBatch = null;
                    state = State.PAUSED;
                    failure = "unknown_item: " + row.getKey();
                    return false;
                }
                int held = Math.max(completedTargets.getOrDefault(item, 0), Math.max(0, countHeld.applyAsInt(item)));
                if (held > Integer.MAX_VALUE - row.getValue()) {
                    activeIndex = i;
                    activeBatch = null;
                    state = State.PAUSED;
                    failure = "target_overflow: " + row.getKey();
                    return false;
                }
                int target = (int) Math.min(Integer.MAX_VALUE, (long) held + row.getValue());
                totals.merge(item, target, Math::max);
            }
            List<MaterialItemEntry> targets = new ArrayList<>();
            for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
                Item item = entry.getKey();
                targets.add(new MaterialItemEntry(item, entry.getValue(), item.getDefaultMaxStackSize()));
            }
            activeIndex = i;
            activeBatch = new ActiveBatch(i, batch.name(), List.copyOf(targets));
            state = State.RUNNING;
            failure = null;
            return true;
        }
        activeIndex = batches.size();
        activeBatch = null;
        state = State.COMPLETE;
        failure = null;
        return true;
    }

    private void requireEditing() {
        load();
        if (!isEditingAllowed()) throw new IllegalStateException("manual queue is active");
    }

    private Path file() {
        return fileOverride == null ? MaLiLibCompat.configDirectory().resolve(FILE_NAME) : fileOverride;
    }
}
