package com.alonediamond.playercontrolpp.mixin;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

/**
 * Mixin 配置插件：把「可选联动模组在不在」的判断提前到 Mixin 的类变换阶段做掉。
 *
 * <p>所有 {@code mixin/compat.*} 下的 mixin 都只在本插件放行时才会注入：联动模组在场，
 * integration 里 stub 的默认空实现被覆写成直连实现；模组不在，mixin 根本不会进入 JVM。
 * 最终进运行时的字节码里只有被选中的那一套实现，联动调用既没有反射也没有运行时检查。
 *
 * <p>判定在 {@link #onLoad}（mixin 配置加载期，早于一切类变换）里一次性算好并缓存，
 * {@link #shouldApplyMixin} 只做包段字符串分发。判定结果同时是 {@code InitHandler}
 * 启动自检的基准。
 */
public class PlayercontrolppMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlayercontrolppMixinPlugin.class);

    /**
     * {@code setScreen} 的归属类版本分叉：26.2 起从 {@code Minecraft} 挪到 {@code Gui}。
     * 编译期常量——预处理器在构建期就知道目标版本，比运行时解析 MC 版本号可靠。
     * 两个 setScreen mixin 源码里都写字符串目标（跨版本都能编译），这里按版本只放行一个，
     * 没被放行的那个不会被 Mixin 解析目标，也就不会因为方法不存在而报错。
     */
    //#if MC >= 260200
    private static final boolean SCREEN_METHOD_ON_GUI = true;
    //#else
    //$$ private static final boolean SCREEN_METHOD_ON_GUI = false;
    //#endif

    private static boolean litematicaLoaded;
    private static boolean baritoneLoaded;
    private static boolean chestTrackerLoaded;
    private static boolean quickShulkerLoaded;
    private static boolean litematListLoaded;

    @Override
    public void onLoad(String mixinPackage) {
        litematicaLoaded = isLoaded("litematica");
        // Baritone 的几个常见 fork 只改了 ModID，API 与官方一致，一并识别。
        baritoneLoaded = isLoaded("baritone") || isLoaded("zbaritone") || isLoaded("baritone-meteor");
        chestTrackerLoaded = isLoaded("chesttracker");
        quickShulkerLoaded = isLoaded("quickshulker");
        litematListLoaded = isLoaded("litematlist");

        LOGGER.info("Compat mixins to apply: litematica={}, baritone={}, chesttracker={}, quickshulker={}, litematlist={}",
                litematicaLoaded, baritoneLoaded, chestTrackerLoaded, quickShulkerLoaded, litematListLoaded);
    }

    private static boolean isLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 前面补一个点，包段匹配对全限定名与 mixins.json 里的相对名都成立。
        String name = "." + mixinClassName;
        // setScreen 归属类的版本分叉（不是可选联动，但同样在类变换期做掉判断）。
        if (name.contains(".compat.screen.MinecraftSetScreenMixin")) return !SCREEN_METHOD_ON_GUI;
        if (name.contains(".compat.screen.GuiSetScreenMixin")) return SCREEN_METHOD_ON_GUI;
        if (name.contains(".compat.litematica.")) return litematicaLoaded;
        if (name.contains(".compat.baritone.")) return baritoneLoaded;
        if (name.contains(".compat.chesttracker.")) return chestTrackerLoaded;
        if (name.contains(".compat.quickshulker.")) return quickShulkerLoaded;
        if (name.contains(".compat.litematlist.")) return litematListLoaded;
        // 模组自身的 mixin（MixinLocalPlayer 等）不受联动开关控制。
        return true;
    }

    // ---- 供 InitHandler 自检用 ----

    public static boolean isLitematicaLoaded() { return litematicaLoaded; }

    public static boolean isBaritoneLoaded() { return baritoneLoaded; }

    public static boolean isChestTrackerLoaded() { return chestTrackerLoaded; }

    public static boolean isQuickShulkerLoaded() { return quickShulkerLoaded; }

    public static boolean isLitematListLoaded() { return litematListLoaded; }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public List<String> getMixins() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
