package com.alonediamond.playercontrolpp.record;

import com.google.gson.JsonObject;

public class RecordedFrame {
    public float movementForward;
    public float movementSideways;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;
    public boolean leftClick;
    public boolean rightClick;
    public float yaw;
    public float pitch;
    // High-precision: player position
    public double posX, posY, posZ;

    public RecordedFrame() {}

    public RecordedFrame(float mf, float ms, boolean jump, boolean sneak, boolean sprint,
                         boolean leftClick, boolean rightClick, float yaw, float pitch) {
        this.movementForward = mf;
        this.movementSideways = ms;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
        this.leftClick = leftClick;
        this.rightClick = rightClick;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("mf", movementForward);
        obj.addProperty("ms", movementSideways);
        obj.addProperty("j", jump);
        obj.addProperty("s", sneak);
        obj.addProperty("sp", sprint);
        obj.addProperty("lc", leftClick);
        obj.addProperty("rc", rightClick);
        obj.addProperty("y", yaw);
        obj.addProperty("p", pitch);
        if (posX != 0 || posY != 0 || posZ != 0) {
            obj.addProperty("px", posX);
            obj.addProperty("py", posY);
            obj.addProperty("pz", posZ);
        }
        return obj;
    }

    public static RecordedFrame fromJson(JsonObject obj) {
        RecordedFrame f = new RecordedFrame(
                obj.has("mf") ? obj.get("mf").getAsFloat() : 0,
                obj.has("ms") ? obj.get("ms").getAsFloat() : 0,
                obj.has("j") && obj.get("j").getAsBoolean(),
                obj.has("s") && obj.get("s").getAsBoolean(),
                obj.has("sp") && obj.get("sp").getAsBoolean(),
                obj.has("lc") && obj.get("lc").getAsBoolean(),
                obj.has("rc") && obj.get("rc").getAsBoolean(),
                obj.has("y") ? obj.get("y").getAsFloat() : 0,
                obj.has("p") ? obj.get("p").getAsFloat() : 0);
        if (obj.has("px")) f.posX = obj.get("px").getAsDouble();
        if (obj.has("py")) f.posY = obj.get("py").getAsDouble();
        if (obj.has("pz")) f.posZ = obj.get("pz").getAsDouble();
        return f;
    }
}
