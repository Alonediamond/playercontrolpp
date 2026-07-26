package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.NbtCompat;

import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Recording data model. Index metadata (id, name, durationTicks, dimension)
 * is stored in index.json and always loaded. Segments and keyframes are stored
 * in individual .pcr files (NBT binary) and only loaded on demand for playback.
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

    /** Always returns true — all recordings are now precision mode. */
    public boolean isHighPrecision() { return true; }
    /** No-op — kept for backward compatibility. */
    public void setHighPrecision(boolean v) { /* always true */ }

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

    /** Number of segments (RLE-compressed units). */
    public int getSegmentCount() { return segments.size(); }

    // --- Index JSON (lightweight, for index.json) ---

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

    // --- NBT binary I/O (for individual .pcr files) ---

    /** Write full recording to NBT file. */
    public void writeToFile(Path path) throws IOException {
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

        NbtIo.writeCompressed(root, path);
    }

    /** Read full recording from NBT file. */
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
