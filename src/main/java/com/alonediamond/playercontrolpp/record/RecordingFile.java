package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.NbtCompat;
import com.alonediamond.playercontrolpp.util.AtomicFiles;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一条录制。
 *
 * <p>拆分存储：索引元数据（id、名称、时长、维度）在 index.json 里、常驻内存；
 * 段与关键帧在每条录制各自的 {@code .pcr} NBT 文件里，只有玩家点播放时才读。
 */
public class RecordingFile {
    private String id;
    private String name;
    private int durationTicks;
    private String dimension;
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    /** 开始录制时选中的快捷栏槽位；回放开始时恢复它，录制里的动作都假定手持那一格。 */
    private int startSlot;
    /** 循环回放开关。只是回放偏好，存在 index.json 里，不进 .pcr。 */
    private boolean loop;

    private List<RecordedSegment> segments = new ArrayList<>();
    private List<PositionKeyframe> keyframes = new ArrayList<>();
    private List<RecordedAction> actions = new ArrayList<>();

    public RecordingFile() {
        this.name = "Unnamed Recording";
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getDurationTicks() { return durationTicks; }
    public void setDurationTicks(int v) { durationTicks = v; }

    public String getDimension() { return dimension; }
    public void setDimension(String v) { dimension = v; }

    public double getStartX() { return startX; }
    public void setStartX(double v) { startX = v; }
    public double getStartY() { return startY; }
    public void setStartY(double v) { startY = v; }
    public double getStartZ() { return startZ; }
    public void setStartZ(double v) { startZ = v; }

    public float getStartYaw() { return startYaw; }
    public void setStartYaw(float v) { startYaw = v; }
    public float getStartPitch() { return startPitch; }
    public void setStartPitch(float v) { startPitch = v; }

    /** @return 开始录制时选中的快捷栏槽位，{@code 0..8}。 */
    public int getStartSlot() { return startSlot; }
    public void setStartSlot(int v) { startSlot = v; }

    /** @return 这条录制是否循环回放。 */
    public boolean isLoop() { return loop; }
    public void setLoop(boolean v) { loop = v; }

    public List<RecordedSegment> getSegments() { return segments; }
    public void setSegments(List<RecordedSegment> segments) { this.segments = segments; }

    public List<PositionKeyframe> getKeyframes() { return keyframes; }
    public void setKeyframes(List<PositionKeyframe> keyframes) { this.keyframes = keyframes; }

    /** 离散动作事件（丢、换手、发言、命令…），按 tick 升序；可为空。 */
    public List<RecordedAction> getActions() { return actions; }
    public void setActions(List<RecordedAction> actions) { this.actions = actions; }

    // ---- 索引 JSON（轻量，供 index.json 用）----

    public JsonObject toIndexJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("durationTicks", durationTicks);
        obj.addProperty("dimension", dimension);
        obj.addProperty("loop", loop);
        return obj;
    }

    public static RecordingFile fromIndexJson(JsonObject obj) {
        RecordingFile rf = new RecordingFile();
        if (obj.has("id")) rf.setId(obj.get("id").getAsString());
        if (obj.has("name")) rf.setName(obj.get("name").getAsString());
        if (obj.has("durationTicks")) rf.setDurationTicks(obj.get("durationTicks").getAsInt());
        if (obj.has("dimension")) rf.setDimension(obj.get("dimension").getAsString());
        if (obj.has("loop")) rf.setLoop(obj.get("loop").getAsBoolean());
        return rf;
    }

    // ---- NBT 二进制读写（每条录制各自的 .pcr）----

    /**
     * 把全部内容序列化成一棵独立的 NBT 树。
     *
     * <p>与写盘分开，好让调用方在客户端线程构造好标签，再把成品交给后台写入线程。
     * 把本对象直接传给别的线程会有数据竞争：{@code name} 与 {@code segments} 仍可变，且 GUI 能碰到。
     */
    public CompoundTag toNbt() {
        CompoundTag root = new CompoundTag();
        root.putString("name", name);
        root.putInt("durationTicks", durationTicks);
        root.putString("dimension", dimension);
        root.putDouble("startX", startX);
        root.putDouble("startY", startY);
        root.putDouble("startZ", startZ);
        root.putFloat("startYaw", startYaw);
        root.putFloat("startPitch", startPitch);
        root.putInt("startSlot", startSlot);

        ListTag segList = new ListTag();
        for (RecordedSegment seg : segments) {
            segList.add(seg.toNbt());
        }
        root.put("segments", segList);

        ListTag kfList = new ListTag();
        for (PositionKeyframe kf : keyframes) {
            kfList.add(kf.toNbt());
        }
        root.put("keyframes", kfList);

        ListTag actionList = new ListTag();
        for (RecordedAction action : actions) {
            actionList.add(action.toNbt());
        }
        root.put("actions", actionList);
        return root;
    }

    /** 把 {@code root} 写到 {@code path}，原子替换已有文件。 */
    public static void write(CompoundTag root, Path path) throws IOException {
        AtomicFiles.writeVia(path, tmp -> NbtIo.writeCompressed(root, tmp));
    }

    /** 读回一条完整录制。 */
    public static RecordingFile readFromFile(Path path) throws IOException {
        CompoundTag root = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
        RecordingFile rf = new RecordingFile();
        rf.name = NbtCompat.getString(root, "name", "Unnamed Recording");
        rf.durationTicks = NbtCompat.getInt(root, "durationTicks", 0);
        rf.dimension = NbtCompat.getString(root, "dimension", "");
        rf.startX = NbtCompat.getDouble(root, "startX", 0.0);
        rf.startY = NbtCompat.getDouble(root, "startY", 0.0);
        rf.startZ = NbtCompat.getDouble(root, "startZ", 0.0);
        rf.startYaw = NbtCompat.getFloat(root, "startYaw", 0.0f);
        rf.startPitch = NbtCompat.getFloat(root, "startPitch", 0.0f);
        rf.startSlot = NbtCompat.getInt(root, "startSlot", 0);

        ListTag segList = NbtCompat.getCompoundList(root, "segments");
        for (int i = 0; i < segList.size(); i++) {
            CompoundTag tag = NbtCompat.getCompoundAt(segList, i);
            if (tag != null) {
                rf.segments.add(RecordedSegment.fromNbt(tag));
            }
        }

        ListTag kfList = NbtCompat.getCompoundList(root, "keyframes");
        for (int i = 0; i < kfList.size(); i++) {
            CompoundTag tag = NbtCompat.getCompoundAt(kfList, i);
            if (tag != null) {
                rf.keyframes.add(PositionKeyframe.fromNbt(tag));
            }
        }

        ListTag actionList = NbtCompat.getCompoundList(root, "actions");
        for (int i = 0; i < actionList.size(); i++) {
            CompoundTag tag = NbtCompat.getCompoundAt(actionList, i);
            if (tag != null) {
                rf.actions.add(RecordedAction.fromNbt(tag));
            }
        }

        return rf;
    }
}
