package com.alonediamond.playercontrolpp.mixin.client;

import net.minecraft.client.KeyMapping;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 往 {@code KeyMapping} 里注入一次点击脉冲。
 *
 * <p>vanilla 的实体攻击与挖掘起手只认 {@code clickCount} 计数
 * （{@code while (consumeClick()) startAttack()}），光把 {@code isDown} 拉真只会触发
 * 持续分支（挖掘延续、按住使用），永远不会打出第一下。回放时在"松开→按下"的
 * 上升沿补一个脉冲，才能让录下来的攻击/使用真的发生。
 *
 * <p>不注入 {@code click(Key) 静态方法}：它按当前键位映射分发（会波及绑了同一个键的
 * 其他映射），而且键盘重复事件也会调它；直接增减计数最精准。
 * 字段映射名在 yarn（1.21.x）与 mojmap（26.x）里都叫 {@code clickCount}，
 * 一个 accessor 全版本通用，无需按节点分支。
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("clickCount")
    int playercontrolpp$getClickCount();

    @Accessor("clickCount")
    void playercontrolpp$setClickCount(int value);
}
