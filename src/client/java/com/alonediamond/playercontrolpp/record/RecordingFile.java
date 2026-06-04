package com.alonediamond.playercontrolpp.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Recording data model. Index metadata (id, name, highPrecision, durationTicks, dimension)
 * is stored in index.json and always loaded. Segments and keyframes are stored in individual
 * recording files and only loaded on demand for playback.
 */
public class RecordingFile {
    private String id;
    private String name;
    private boolean highPrecision;
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

    public boolean isHighPrecision() { return highPrecision; }
    public void setHighPrecision(boolean v) { highPrecision = v; }

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
        obj.addProperty("highPrecision", highPrecision);
        obj.addProperty("durationTicks", durationTicks);
        obj.addProperty("dimension", dimension);
        return obj;
    }

    public static RecordingFile fromIndexJson(JsonObject obj) {
        RecordingFile rf = new RecordingFile();
        if (obj.has("id")) rf.setId(obj.get("id").getAsString());
        if (obj.has("name")) rf.setName(obj.get("name").getAsString());
        if (obj.has("highPrecision")) rf.setHighPrecision(obj.get("highPrecision").getAsBoolean());
        if (obj.has("durationTicks")) rf.setDurationTicks(obj.get("durationTicks").getAsInt());
        if (obj.has("dimension")) rf.setDimension(obj.get("dimension").getAsString());
        return rf;
    }

    // --- Full JSON (for individual recording files) ---

    public JsonObject toFullJson() {
        JsonObject obj = toIndexJson();
        obj.addProperty("startX", startX);
        obj.addProperty("startY", startY);
        obj.addProperty("startZ", startZ);
        obj.addProperty("startYaw", startYaw);
        obj.addProperty("startPitch", startPitch);

        JsonArray segArr = new JsonArray();
        for (RecordedSegment seg : segments) {
            segArr.add(seg.toJson());
        }
        obj.add("segments", segArr);

        if (highPrecision && !keyframes.isEmpty()) {
            JsonArray kfArr = new JsonArray();
            for (PositionKeyframe kf : keyframes) {
                kfArr.add(kf.toJson());
            }
            obj.add("keyframes", kfArr);
        }
        return obj;
    }

    public static RecordingFile fromFullJson(JsonObject obj) {
        RecordingFile rf = fromIndexJson(obj);
        if (obj.has("startX")) rf.setStartX(obj.get("startX").getAsDouble());
        if (obj.has("startY")) rf.setStartY(obj.get("startY").getAsDouble());
        if (obj.has("startZ")) rf.setStartZ(obj.get("startZ").getAsDouble());
        if (obj.has("startYaw")) rf.setStartYaw(obj.get("startYaw").getAsFloat());
        if (obj.has("startPitch")) rf.setStartPitch(obj.get("startPitch").getAsFloat());

        if (obj.has("segments")) {
            JsonArray arr = obj.getAsJsonArray("segments");
            for (int i = 0; i < arr.size(); i++) {
                rf.segments.add(RecordedSegment.fromJson(arr.get(i).getAsJsonObject()));
            }
        }
        if (obj.has("keyframes")) {
            JsonArray arr = obj.getAsJsonArray("keyframes");
            for (int i = 0; i < arr.size(); i++) {
                rf.keyframes.add(PositionKeyframe.fromJson(arr.get(i).getAsJsonObject()));
            }
        }
        return rf;
    }
}
