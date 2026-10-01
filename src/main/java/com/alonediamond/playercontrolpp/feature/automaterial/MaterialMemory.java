package com.alonediamond.playercontrolpp.feature.automaterial;

import net.minecraft.world.item.Item;

import java.util.HashSet;
import java.util.Set;

/**
 * 「本局游戏里备货功能见过的材料」记忆。
 *
 * <p>作者实测的缺口：自动存盒只认"投影清单 + 本次要收集 + 本轮追溯碰过"的物品，而这些集合每次启动都会重建。
 * 上一轮备货收集来的原材料（木板、原木、圆石……）如果没进盒子、留在了背包里，
 * 重新开关功能后它们既不在清单上、也不在本轮追溯记录里——于是"背包满了"却一个都存不进去，
 * 只能停在那儿提示（作者第三点反馈的场景）。
 *
 * <p>所以这里记一份"碰过的材料"：投影清单上的、追溯派出去收集/挖的、以及<b>玩家自己的材料盒里已经装着的</b>。
 * 存盒时把它一并当作可存物品。刻意只放在内存里（一局游戏有效）：写进配置的话，
 * 玩家关掉功能之后仍然会被搬东西。语义边界：不是"什么都存"——不认识的物品依然不动。
 */
public final class MaterialMemory {

    private static final Set<Item> KNOWN = new HashSet<>();

    private MaterialMemory() {}

    /** 记下一个材料（null 安全，重复无副作用）。 */
    public static void remember(Item item) {
        if (item != null) KNOWN.add(item);
    }

    /** @return 这个物品是不是本局游戏里备货功能见过的材料。 */
    public static boolean isKnown(Item item) {
        return item != null && KNOWN.contains(item);
    }
}
