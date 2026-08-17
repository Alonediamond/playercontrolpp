package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * 跨版本读写当前打开的 {@link Screen}。
 *
 * <p>26.2 把 screen 的归属从 {@code Minecraft} 挪进了 {@code Minecraft.gui}：
 * <ul>
 *   <li>{@code mc.screen}       &rarr; {@code mc.gui.screen()}</li>
 *   <li>{@code mc.setScreen(s)} &rarr; {@code mc.gui.setScreen(s)}</li>
 * </ul>
 * 全模组的调用都走这里，版本分叉只存在一处。
 */
public final class ScreenCompat {

    private ScreenCompat() {}

    /** @return 当前打开的界面；没有则 {@code null}。 */
    @Nullable
    public static Screen getScreen(Minecraft mc) {
        //#if MC >= 260200
        return mc.gui.screen();
        //#else
        //$$ return mc.screen;
        //#endif
    }

    /** 打开 {@code screen}；传 {@code null} 表示关闭当前界面。 */
    public static void setScreen(Minecraft mc, @Nullable Screen screen) {
        //#if MC >= 260200
        mc.gui.setScreen(screen);
        //#else
        //$$ mc.setScreen(screen);
        //#endif
    }
}
