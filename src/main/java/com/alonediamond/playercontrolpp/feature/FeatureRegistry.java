package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 tick 顺序排列的 {@link ClientFeature} 列表。
 *
 * <p>注册顺序就是 tick 顺序和世界切换的通知顺序，有意义——顺序与原因见 {@code InitHandler}。
 *
 * <p>某个功能抛异常只记日志并跳过，不让它掀掉整个 tick 回调（那会让它后面的功能全部静默失效）。
 */
public final class FeatureRegistry {

    private static final List<ClientFeature> FEATURES = new ArrayList<>();

    private FeatureRegistry() {}

    public static void register(ClientFeature feature) {
        FEATURES.add(feature);
    }

    public static void tickAll(Minecraft mc) {
        for (ClientFeature feature : FEATURES) {
            try {
                feature.onClientTick(mc);
            } catch (Exception e) {
                Playercontrolpp.LOGGER.error("功能 {} 在客户端 tick 中抛出异常",
                        feature.getClass().getName(), e);
            }
        }
    }

    public static void notifyWorldChange() {
        for (ClientFeature feature : FEATURES) {
            try {
                feature.onWorldChange();
            } catch (Exception e) {
                Playercontrolpp.LOGGER.error("功能 {} 在世界切换中抛出异常",
                        feature.getClass().getName(), e);
            }
        }
    }

    /** @return 是否有任一已注册功能自报正在运行。 */
    public static boolean anyActive() {
        for (ClientFeature feature : FEATURES) {
            if (feature.isActive()) return true;
        }
        return false;
    }
}
