package com.alonediamond.playercontrolpp;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用入口。客户端部分在 {@code PlayercontrolppClient} 里接线。
 */
public class Playercontrolpp implements ModInitializer {

    /**
     * 模组 id，用于配置注册键、翻译键前缀和资源路径。
     * 其他类一律引用这个常量而不是重复字面量——写错的话 malilib 会静默找不到配置，不会报错。
     */
    public static final String MOD_ID = "playercontrolpp";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 通用初始化（目前为空）
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
