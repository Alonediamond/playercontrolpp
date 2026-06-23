package com.alonediamond.playercontrolpp.record;

import net.minecraft.nbt.NbtCompound;

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

    public NbtCompound toNbt() {
        NbtCompound tag = new NbtCompound();
        tag.putInt("d", duration);
        tag.putFloat("fw", forward);
        tag.putFloat("sw", sideways);
        tag.putBoolean("j", jump);
        tag.putBoolean("sn", sneak);
        tag.putBoolean("sp", sprint);
        tag.putFloat("y", yaw);
        tag.putFloat("p", pitch);
        tag.putBoolean("at", attack);
        tag.putBoolean("us", use);
        return tag;
    }

    public static RecordedSegment fromNbt(NbtCompound tag) {
        return new RecordedSegment(
            tag.getInt("d"),
            tag.getFloat("fw"),
            tag.getFloat("sw"),
            tag.getBoolean("j"),
            tag.getBoolean("sn"),
            tag.getBoolean("sp"),
            tag.getFloat("y"),
            tag.getFloat("p"),
            tag.getBoolean("at"),
            tag.getBoolean("us"));
    }
}
