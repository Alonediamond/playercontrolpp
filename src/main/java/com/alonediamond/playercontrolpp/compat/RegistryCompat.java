package com.alonediamond.playercontrolpp.compat;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * 按注册名取物品。
 *
 * <p>1.21.2 的注册表改版把 {@code Registry.get(id)} 的返回值从"物品本身（可能为 null）"
 * 换成了 {@code Optional<Holder.Reference<T>>}，方法名和参数都没变，源码重映射器桥接不了。
 * 本项目最低支持 1.21.1，所以这一处差异收在这里。
 */
public final class RegistryCompat {

    private RegistryCompat() {}

    /** @return 该注册名对应的物品；不存在或名字非法时 {@code null}。 */
    @Nullable
    public static Item item(Identifier id) {
        if (id == null) return null;
        //#if MC >= 12102
        return BuiltInRegistries.ITEM.get(id).map(Holder::value).orElse(null);
        //#else
        //$$ return BuiltInRegistries.ITEM.get(id);
        //#endif
    }
}
