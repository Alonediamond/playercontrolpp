package com.alonediamond.playercontrolpp.feature;

import net.minecraft.client.Minecraft;

/**
 * 一个客户端功能的生命周期。
 *
 * <p>在 {@link FeatureRegistry} 注册后才会被 tick、才会收到世界切换通知。
 * 新增功能不必再改 {@code ClientEventHandler}，世界切换的广播也不会漏掉谁。
 *
 * <p>所有方法都有默认实现，功能只实现自己需要的部分。
 */
public interface ClientFeature {

    /** 有玩家存在时每客户端 tick 调用一次。 */
    default void onClientTick(Minecraft mc) {}

    /** 客户端世界被替换前调用：切维度、断开连接、加载世界。 */
    default void onWorldChange() {}

    /** @return 本功能当前是否处于用户会称之为"正在运行"的状态。 */
    default boolean isActive() {
        return false;
    }
}
