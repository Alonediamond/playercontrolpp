package com.alonediamond.playercontrolpp.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * Reading values out of a {@link CompoundTag}.
 *
 * <p>Minecraft 1.21.5 turned every {@code CompoundTag} getter into an
 * {@code Optional}-returning method:
 * <pre>
 *   int    getInt(String)                &rarr; Optional&lt;Integer&gt; getInt(String)
 *   String getString(String)             &rarr; Optional&lt;String&gt;  getString(String)
 *   ListTag getList(String, int type)    &rarr; Optional&lt;ListTag&gt; getList(String)
 * </pre>
 * Older versions returned a zero/empty default directly and had no way to tell
 * "absent" from "present but zero", so every accessor here takes an explicit default
 * and both branches behave identically.
 *
 * <p>The 1.21.4 branch passes the numeric NBT tag id to {@code getList} — {@code 10}
 * is {@code TAG_COMPOUND}, which is the only list element type this mod stores.
 */
public final class NbtCompat {

    /** NBT tag id for {@code TAG_COMPOUND}, needed by the pre-1.21.5 {@code getList} overload. */
    private static final int TAG_COMPOUND = 10;

    private NbtCompat() {}

    public static String getString(CompoundTag tag, String key, String fallback) {
        //#if MC >= 12105
        return tag.getString(key).orElse(fallback);
        //#else
        //$$ return tag.contains(key) ? tag.getString(key) : fallback;
        //#endif
    }

    public static int getInt(CompoundTag tag, String key, int fallback) {
        //#if MC >= 12105
        return tag.getInt(key).orElse(fallback);
        //#else
        //$$ return tag.contains(key) ? tag.getInt(key) : fallback;
        //#endif
    }

    public static double getDouble(CompoundTag tag, String key, double fallback) {
        //#if MC >= 12105
        return tag.getDouble(key).orElse(fallback);
        //#else
        //$$ return tag.contains(key) ? tag.getDouble(key) : fallback;
        //#endif
    }

    public static float getFloat(CompoundTag tag, String key, float fallback) {
        //#if MC >= 12105
        return tag.getFloat(key).orElse(fallback);
        //#else
        //$$ return tag.contains(key) ? tag.getFloat(key) : fallback;
        //#endif
    }

    public static boolean getBoolean(CompoundTag tag, String key, boolean fallback) {
        //#if MC >= 12105
        return tag.getBoolean(key).orElse(fallback);
        //#else
        //$$ return tag.contains(key) ? tag.getBoolean(key) : fallback;
        //#endif
    }

    /** @return the list stored under {@code key}, or an empty list when absent. */
    public static ListTag getCompoundList(CompoundTag tag, String key) {
        //#if MC >= 12105
        return tag.getList(key).orElse(new ListTag());
        //#else
        //$$ return tag.contains(key) ? tag.getList(key, TAG_COMPOUND) : new ListTag();
        //#endif
    }

    /** @return element {@code index} of {@code list} as a compound, or {@code null} if it is not one. */
    public static CompoundTag getCompoundAt(ListTag list, int index) {
        //#if MC >= 12105
        return list.getCompound(index).orElse(null);
        //#else
        //$$ return list.getCompound(index);
        //#endif
    }
}
