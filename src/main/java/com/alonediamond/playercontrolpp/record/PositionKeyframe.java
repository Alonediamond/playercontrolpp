package com.alonediamond.playercontrolpp.record;

import com.alonediamond.playercontrolpp.compat.NbtCompat;

import net.minecraft.nbt.CompoundTag;

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

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("t", tick);
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        return tag;
    }

    public static PositionKeyframe fromNbt(CompoundTag tag) {
        return new PositionKeyframe(
            NbtCompat.getInt(tag, "t", 0),
            NbtCompat.getDouble(tag, "x", 0.0),
            NbtCompat.getDouble(tag, "y", 0.0),
            NbtCompat.getDouble(tag, "z", 0.0));
    }
}
