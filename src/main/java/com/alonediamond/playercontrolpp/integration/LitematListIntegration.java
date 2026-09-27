package com.alonediamond.playercontrolpp.integration;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.List;

/**
 * LitematList 联动的默认实现（stub）。真正的方法体在
 * {@code mixin/compat/litematlist/LitematListIntegrationImpl} 里，机制同 {@link LitematicaIntegration}。
 *
 * <p>只读它的公开 API {@code com.litematlist.api.LitematListAPI}：返回的是上传区域里
 * 已经处理完替换、忽略与项目聚合的清单，因此这里不需要再关心它的替换/忽略规则。
 * LitematList 目前只发布了 1.21.10 与 26.1.2 版，其他 MC 版本上 MixinPlugin 自然不会
 * 放行注入，本类按默认值静默降级；等它适配更多版本后无需再改这里。
 */
public class LitematListIntegration {

    private static final LitematListIntegration INSTANCE = new LitematListIntegration();

    private LitematListIntegration() {}

    public static LitematListIntegration getInstance() { return INSTANCE; }

    /** Mixin 注入成功时覆写为 {@code true}；未注入即联动未生效。 */
    public boolean isLoaded() { return false; }

    /**
     * LitematList 上传清单里的一条：物品堆、需求总量，以及按它的算法算出的还缺多少。
     */
    public record MaterialEntry(ItemStack stack, int totalCount, int missingCount) {}

    /**
     * 读取 LitematList 上传区域的材料列表（已含替换、忽略与项目聚合处理）。
     *
     * @return 条目列表；模组不在、上传区域为空或读取失败时为空列表
     */
    public List<MaterialEntry> getMaterialEntries() { return Collections.emptyList(); }

    /** @return LitematList 当前是否上传了材料清单（原材料溯源的前提之一）。 */
    public boolean isMaterialListAvailable() { return false; }

    /**
     * 原材料配方树的一个节点。
     *
     * <p>刻意用字符串物品 id 而不是 {@link ItemStack}/{@link net.minecraft.world.item.Item}：
     * stub 里不能出现 LitematList 的任何类型，而它的树节点正是用注册名表达的。
     *
     * @param itemId     物品注册名，如 {@code minecraft:oak_planks}
     * @param count      这个节点一共需要多少个（已按根节点数量展开，含向上取整的余量）
     * @param recipeType 这一层用的配方类型：{@code crafting_shaped} / {@code crafting_shapeless} /
     *                   {@code stonecutting} / {@code smelting} / {@code smithing} / {@code base}（叶子）
     * @param children   下一层材料；空表示这是溯源终点（叶子）
     */
    public record RawNode(String itemId, int count, String recipeType, List<RawNode> children) {
        public boolean isLeaf() { return this.children.isEmpty(); }
    }

    /**
     * 读某个物品的原材料配方树。
     *
     * <p>树的形状完全由 LitematList 决定：它内部读的是玩家在那边配置的溯源白名单与配方优先级，
     * 所以本模组不需要（也不应该）自己再实现一套配方选择规则。
     *
     * @param item     要分解的物品
     * @param count    需要多少个（树里的所有数量按这个基数展开）
     * @param maxDepth 溯源层数，0-10；负值表示用分析器自己的默认值
     * @return 树根；模组不在、读取失败或该物品没有可用配方时返回 {@code null}
     */
    public RawNode getRawMaterialTree(Item item, int count, int maxDepth) { return null; }

    /**
     * LitematList 原材料界面当前使用的显示层数（由 {@code RawMaterialScreenMixin} 观察写入）。
     *
     * <p>该字段在 LitematList 里是 GUI 的私有局部变量，没有配置项也没有 getter，
     * 只能在它的界面构建树时顺手读一份出来。玩家本次游戏没打开过那个界面时保持 -1，
     * 调用方按 LitematList 自己的默认值（5）处理。
     *
     * @return 观察到的层数；从未观察到时为 -1
     */
    public static int getObservedPlayerDepth() { return observedPlayerDepth; }

    /** 供 compat mixin 写入观察到的层数。 */
    public static void observePlayerDepth(int depth) { observedPlayerDepth = depth; }

    private static volatile int observedPlayerDepth = -1;
}
