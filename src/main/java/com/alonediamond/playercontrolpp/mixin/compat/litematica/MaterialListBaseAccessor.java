package com.alonediamond.playercontrolpp.mixin.compat.litematica;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

/**
 * 直读 Litematica 材料清单的「已忽略条目」集合。
 *
 * <p>该字段从 0.19.60（1.21.1）到 0.28.4（26.2）一直声明在 {@code MaterialListBase} 上
 * 且是 {@code protected}，外部包访问不到，只能用 accessor mixin 把一个 public getter
 * 注进去。字段名与集合类型全版本一致，无需按版本分支。
 */
@Mixin(targets = "fi.dy.masa.litematica.materials.MaterialListBase", remap = false)
public interface MaterialListBaseAccessor {

    @Accessor(value = "ignored", remap = false)
    Set<?> litematica$getIgnoredSet();
}
