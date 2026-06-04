package com.alonediamond.playercontrolpp.record;

import com.google.gson.JsonObject;

/**
 * RLE-compressed recording segment. Represents a contiguous range of ticks
 * where all input fields were identical.
 */
public class RecordedSegment {
    public int duration;
    public float forward;
    public float sideways;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;
    public float yaw;
    public float pitch;
    public boolean attack;
    public boolean use;

    public RecordedSegment() {}

    public RecordedSegment(int duration, float forward, float sideways,
                           boolean jump, boolean sneak, boolean sprint,
                           float yaw, float pitch, boolean attack, boolean use) {
        this.duration = duration;
        this.forward = forward;
        this.sideways = sideways;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
        this.yaw = yaw;
        this.pitch = pitch;
        this.attack = attack;
        this.use = use;
    }

    public boolean matches(float fw, float sw, boolean j, boolean sn, boolean sp,
                           float y, float p, boolean at, boolean us) {
        return forward == fw && sideways == sw
                && jump == j && sneak == sn && sprint == sp
                && yaw == y && pitch == p
                && attack == at && use == us;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("d", duration);
        obj.addProperty("fw", forward);
        obj.addProperty("sw", sideways);
        obj.addProperty("j", jump);
        obj.addProperty("sn", sneak);
        obj.addProperty("sp", sprint);
        obj.addProperty("y", yaw);
        obj.addProperty("p", pitch);
        obj.addProperty("at", attack);
        obj.addProperty("us", use);
        return obj;
    }

    public static RecordedSegment fromJson(JsonObject obj) {
        return new RecordedSegment(
                obj.has("d") ? obj.get("d").getAsInt() : 1,
                obj.has("fw") ? obj.get("fw").getAsFloat() : 0,
                obj.has("sw") ? obj.get("sw").getAsFloat() : 0,
                obj.has("j") && obj.get("j").getAsBoolean(),
                obj.has("sn") && obj.get("sn").getAsBoolean(),
                obj.has("sp") && obj.get("sp").getAsBoolean(),
                obj.has("y") ? obj.get("y").getAsFloat() : 0,
                obj.has("p") ? obj.get("p").getAsFloat() : 0,
                obj.has("at") && obj.get("at").getAsBoolean(),
                obj.has("us") && obj.get("us").getAsBoolean());
    }
}
