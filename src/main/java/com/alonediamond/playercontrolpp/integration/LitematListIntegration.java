package com.alonediamond.playercontrolpp.integration;

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
}
