package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jetbrains.annotations.Nullable;

/**
 * Cross-version access to the currently open {@link Screen}.
 *
 * <p>Minecraft 26.2 moved screen ownership out of {@code Minecraft} and into
 * {@code Minecraft.gui}:
 * <ul>
 *   <li>{@code mc.screen}          &rarr; {@code mc.gui.screen()}</li>
 *   <li>{@code mc.setScreen(s)}    &rarr; {@code mc.gui.setScreen(s)}</li>
 * </ul>
 * Every call site in this mod goes through here so the version split lives in
 * exactly one place.
 */
public final class ScreenCompat {

    private ScreenCompat() {}

    /** @return the currently open screen, or {@code null} if none is open. */
    @Nullable
    public static Screen getScreen(Minecraft mc) {
        //#if MC >= 260200
        return mc.gui.screen();
        //#else
        //$$ return mc.screen;
        //#endif
    }

    /** Opens {@code screen}, or closes the current screen when passed {@code null}. */
    public static void setScreen(Minecraft mc, @Nullable Screen screen) {
        //#if MC >= 260200
        mc.gui.setScreen(screen);
        //#else
        //$$ mc.setScreen(screen);
        //#endif
    }
}
