package com.alonediamond.playercontrolpp.record;

import net.minecraft.nbt.NbtCompound;

/**
 * HP mode position keyframe — recorded every 20 ticks during recording.
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

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("t", tick);
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        return tag;
    }

    public static PositionKeyframe fromNbt(NbtCompound tag) {
        return new PositionKeyframe(
            tag.getInt("t"),
            tag.getDouble("x"),
            tag.getDouble("y"),
            tag.getDouble("z"));
    }
}
