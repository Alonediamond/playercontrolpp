package com.alonediamond.playercontrolpp.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * 从 {@link CompoundTag} 读值。
 *
 * <p>1.21.5 把 {@code CompoundTag} 的全部 getter 改成返回 {@code Optional}：
 * <pre>
 *   int    getInt(String)             &rarr; Optional&lt;Integer&gt; getInt(String)
 *   String getString(String)          &rarr; Optional&lt;String&gt;  getString(String)
 *   ListTag getList(String, int type) &rarr; Optional&lt;ListTag&gt; getList(String)
 * </pre>
 * 老版本直接返回 0 / 空串，分不出"不存在"和"存在但是 0"，所以这里每个方法都要求显式传默认值，
 * 两个分支行为完全一致。
 *
 * <p>1.21.4 及更早的分支要给 {@code getList} 传 NBT 类型 id：{@code 10} 是 {@code TAG_COMPOUND}，
 * 也是本模组唯一会存的列表元素类型。
 */
public final class NbtCompat {

    /** {@code TAG_COMPOUND} 的 NBT 类型 id，1.21.5 之前的 {@code getList} 重载需要。 */
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

    /** @return {@code key} 下的列表；不存在时返回空列表。 */
    public static ListTag getCompoundList(CompoundTag tag, String key) {
        //#if MC >= 12105
        return tag.getList(key).orElse(new ListTag());
        //#else
        //$$ return tag.contains(key) ? tag.getList(key, TAG_COMPOUND) : new ListTag();
        //#endif
    }

    /** @return 把 {@code list} 的第 {@code index} 项当复合标签取出；不是复合标签则 {@code null}。 */
    public static CompoundTag getCompoundAt(ListTag list, int index) {
        //#if MC >= 12105
        return list.getCompound(index).orElse(null);
        //#else
        //$$ return list.getCompound(index);
        //#endif
    }
}
