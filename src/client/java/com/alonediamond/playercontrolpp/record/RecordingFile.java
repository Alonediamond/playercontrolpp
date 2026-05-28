package com.alonediamond.playercontrolpp.record;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RecordingFile {
    private String id;
    private String name;
    private double startX, startY, startZ;
    private float startYaw, startPitch;
    private String dimension;
    private boolean highPrecision;
    private List<RecordedFrame> frames = new ArrayList<>();

    public RecordingFile() {
        this.id = UUID.randomUUID().toString();
        this.name = "Unnamed Recording";
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

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

    public String getDimension() { return dimension; }
    public void setDimension(String v) { dimension = v; }

    public boolean isHighPrecision() { return highPrecision; }
    public void setHighPrecision(boolean v) { highPrecision = v; }

    public List<RecordedFrame> getFrames() { return frames; }
    public void setFrames(List<RecordedFrame> frames) { this.frames = frames; }
    public int getFrameCount() { return frames.size(); }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("startX", startX);
        obj.addProperty("startY", startY);
        obj.addProperty("startZ", startZ);
        obj.addProperty("startYaw", startYaw);
        obj.addProperty("startPitch", startPitch);
        obj.addProperty("dimension", dimension);
        obj.addProperty("highPrecision", highPrecision);
        JsonArray arr = new JsonArray();
        for (RecordedFrame f : frames) {
            arr.add(f.toJson());
        }
        obj.add("frames", arr);
        return obj;
    }

    public static RecordingFile fromJson(JsonObject obj) {
        RecordingFile rf = new RecordingFile();
        if (obj.has("id")) rf.setId(obj.get("id").getAsString());
        if (obj.has("name")) rf.setName(obj.get("name").getAsString());
        if (obj.has("startX")) rf.setStartX(obj.get("startX").getAsDouble());
        if (obj.has("startY")) rf.setStartY(obj.get("startY").getAsDouble());
        if (obj.has("startZ")) rf.setStartZ(obj.get("startZ").getAsDouble());
        if (obj.has("startYaw")) rf.setStartYaw(obj.get("startYaw").getAsFloat());
        if (obj.has("startPitch")) rf.setStartPitch(obj.get("startPitch").getAsFloat());
        if (obj.has("dimension")) rf.setDimension(obj.get("dimension").getAsString());
        if (obj.has("highPrecision")) rf.setHighPrecision(obj.get("highPrecision").getAsBoolean());
        if (obj.has("frames")) {
            JsonArray arr = obj.getAsJsonArray("frames");
            for (int i = 0; i < arr.size(); i++) {
                rf.frames.add(RecordedFrame.fromJson(arr.get(i).getAsJsonObject()));
            }
        }
        return rf;
    }
}
