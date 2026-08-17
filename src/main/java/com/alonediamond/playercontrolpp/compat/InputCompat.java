package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.player.LocalPlayer;

/**
 * 读玩家移动键的原始按下状态。
 *
 * <p>1.21.2 把 {@code Input} 上散装的 boolean 字段换成了 {@code PlayerInput} record，
 * 通过 {@code input.keyPresses} 暴露：
 * <table border="1">
 *   <tr><th>&le; 1.21.1</th><th>&ge; 1.21.2</th></tr>
 *   <tr><td>{@code input.jumping}</td><td>{@code input.keyPresses.jump()}</td></tr>
 *   <tr><td>{@code input.shiftKeyDown}</td><td>{@code input.keyPresses.shift()}</td></tr>
 *   <tr><td>（没有疾跑字段）</td><td>{@code input.keyPresses.sprint()}</td></tr>
 * </table>
 * 本项目最老的版本节点是 1.21.1，所以 {@code 12102} 这道门槛实际上只把 1.21.1 单独分出去。
 *
 * <p><b>1.21.1 的疾跑语义不等价。</b>1.21.2 之前 {@code Input} 上没有疾跑<em>按键</em>状态，
 * 所以那条分支退化为读实体的疾跑<em>状态</em>（{@code player.isSprinting()}）。
 * 这与本模组早先的 1.21.1 独立版行为一致，录制文件表现不变——但 1.21.1 上录到的是
 * "当时在疾跑"，不是"按住了疾跑键"。
 */
public final class InputCompat {

    private InputCompat() {}

    /** @return 本 tick 是否按住跳跃键。 */
    public static boolean isJumping(LocalPlayer player) {
        //#if MC >= 12102
        return player.input.keyPresses.jump();
        //#else
        //$$ return player.input.jumping;
        //#endif
    }

    /** @return 本 tick 是否按住潜行键。 */
    public static boolean isSneaking(LocalPlayer player) {
        //#if MC >= 12102
        return player.input.keyPresses.shift();
        //#else
        //$$ return player.input.shiftKeyDown;
        //#endif
    }

    /** @return 本 tick 是否按住疾跑键；1.21.1 上是"玩家是否正在疾跑"（见类注释）。 */
    public static boolean isSprinting(LocalPlayer player) {
        //#if MC >= 12102
        return player.input.keyPresses.sprint();
        //#else
        //$$ return player.isSprinting();
        //#endif
    }
}
