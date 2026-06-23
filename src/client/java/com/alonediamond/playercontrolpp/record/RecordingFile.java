package com.alonediamond.playercontrolpp.record;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

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

    // --- NBT binary I/O (for individual .pcr files) ---

    /** Write full recording to NBT file. */
    public void writeToFile(Path path) throws IOException {
        NbtCompound root = new NbtCompound();
        root.putString("name", name);
        root.putInt("durationTicks", durationTicks);
        root.putString("dimension", dimension);
        root.putDouble("startX", startX);
        root.putDouble("startY", startY);
        root.putDouble("startZ", startZ);
        root.putFloat("startYaw", startYaw);
        root.putFloat("startPitch", startPitch);

        NbtList segList = new NbtList();
        for (RecordedSegment seg : segments) {
            segList.add(seg.toNbt());
        }
        root.put("segments", segList);

        NbtList kfList = new NbtList();
        for (PositionKeyframe kf : keyframes) {
            kfList.add(kf.toNbt());
        }
        root.put("keyframes", kfList);

        NbtIo.writeCompressed(root, path);
    }

    /** Read full recording from NBT file. */
    public static RecordingFile readFromFile(Path path) throws IOException {
        NbtCompound root = NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        RecordingFile rf = new RecordingFile();
        rf.name = root.getString("name");
        rf.durationTicks = root.getInt("durationTicks");
        rf.dimension = root.getString("dimension");
        rf.startX = root.getDouble("startX");
        rf.startY = root.getDouble("startY");
        rf.startZ = root.getDouble("startZ");
        rf.startYaw = root.getFloat("startYaw");
        rf.startPitch = root.getFloat("startPitch");

        NbtList segList = root.getList("segments", 10); // 10 = NbtCompound type
        for (int i = 0; i < segList.size(); i++) {
            rf.segments.add(RecordedSegment.fromNbt(segList.getCompound(i)));
        }

        NbtList kfList = root.getList("keyframes", 10);
        for (int i = 0; i < kfList.size(); i++) {
            rf.keyframes.add(PositionKeyframe.fromNbt(kfList.getCompound(i)));
        }

        return rf;
    }
}
