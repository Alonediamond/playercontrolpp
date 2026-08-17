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

    private List<RecordedSegment> segments = new ArrayList<>();
    private List<PositionKeyframe> keyframes = new ArrayList<>();

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

    public List<RecordedSegment> getSegments() { return segments; }
    public void setSegments(List<RecordedSegment> segments) { this.segments = segments; }

    public List<PositionKeyframe> getKeyframes() { return keyframes; }
    public void setKeyframes(List<PositionKeyframe> keyframes) { this.keyframes = keyframes; }

    // ---- 索引 JSON（轻量，供 index.json 用）----

    public JsonObject toIndexJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("durationTicks", durationTicks);
        obj.addProperty("dimension", dimension);
        return obj;
    }

    public static RecordingFile fromIndexJson(JsonObject obj) {
        RecordingFile rf = new RecordingFile();
        if (obj.has("id")) rf.setId(obj.get("id").getAsString());
        if (obj.has("name")) rf.setName(obj.get("name").getAsString());
        if (obj.has("durationTicks")) rf.setDurationTicks(obj.get("durationTicks").getAsInt());
        if (obj.has("dimension")) rf.setDimension(obj.get("dimension").getAsString());
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

        return rf;
    }
}
