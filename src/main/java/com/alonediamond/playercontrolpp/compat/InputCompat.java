package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.player.LocalPlayer;

/**
 * Reading the player's raw movement key state.
 *
 * <p>Minecraft 1.21.2 replaced the loose boolean fields on {@code Input} with a
 * {@code PlayerInput} record exposed as {@code input.keyPresses}:
 * <table border="1">
 *   <tr><th>&le; 1.21.1</th><th>&ge; 1.21.2</th></tr>
 *   <tr><td>{@code input.jumping}</td><td>{@code input.keyPresses.jump()}</td></tr>
 *   <tr><td>{@code input.shiftKeyDown}</td><td>{@code input.keyPresses.shift()}</td></tr>
 *   <tr><td>(no sprint field)</td><td>{@code input.keyPresses.sprint()}</td></tr>
 * </table>
 *
 * <p>The version nodes in this project are 1.21.1 and 1.21.4, so the {@code 12102}
 * threshold only ever selects between those two branches.
 *
 * <p><b>Sprint on 1.21.1 is not equivalent.</b> There is no sprint key state on
 * {@code Input} before 1.21.2, so the pre-1.21.2 branch falls back to the entity's
 * sprint <em>state</em> ({@code player.isSprinting()}). That is what the standalone
 * 1.21.1 build of this mod already did, so recordings behave the same as before — but
 * a recording made on 1.21.1 stores "was sprinting", not "held the sprint key".
 */
public final class InputCompat {

    private InputCompat() {}

    /** @return whether the jump key is held this tick. */
    public static boolean isJumping(LocalPlayer player) {
        //#if MC >= 12102
        return player.input.keyPresses.jump();
        //#else
        //$$ return player.input.jumping;
        //#endif
    }

    /** @return whether the sneak key is held this tick. */
    public static boolean isSneaking(LocalPlayer player) {
        //#if MC >= 12102
        return player.input.keyPresses.shift();
        //#else
        //$$ return player.input.shiftKeyDown;
        //#endif
    }

    /**
     * @return whether the sprint key is held this tick — or, on 1.21.1, whether the
     *         player is currently sprinting (see the class javadoc).
     */
    public static boolean isSprinting(LocalPlayer player) {
        //#if MC >= 12102
        return player.input.keyPresses.sprint();
        //#else
        //$$ return player.isSprinting();
        //#endif
    }
}
