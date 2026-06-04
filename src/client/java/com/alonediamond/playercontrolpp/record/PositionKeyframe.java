package com.alonediamond.playercontrolpp.record;

import com.google.gson.JsonObject;

/**
 * HP mode position keyframe — recorded every 20 ticks during high-precision recording.
 * Used during playback to correct position drift.
 */
public class PositionKeyframe {
    public int tick;
    public double x;
    public double y;
    public double z;

    public PositionKeyframe() {}

    public PositionKeyframe(int tick, double x, double y, double z) {
        this.tick = tick;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("t", tick);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("z", z);
        return obj;
    }

    public static PositionKeyframe fromJson(JsonObject obj) {
        return new PositionKeyframe(
                obj.has("t") ? obj.get("t").getAsInt() : 0,
                obj.has("x") ? obj.get("x").getAsDouble() : 0,
                obj.has("y") ? obj.get("y").getAsDouble() : 0,
                obj.has("z") ? obj.get("z").getAsDouble() : 0);
    }
}
