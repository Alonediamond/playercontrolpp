package com.alonediamond.playercontrolpp.mixin.compat.litematlist;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.MaterialEntry;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;

import com.litematlist.LitematicReader;
import com.litematlist.RawMaterialTreeAnalyzer;
import com.litematlist.RecipeTreeNode;
import com.litematlist.api.LitematListAPI;
import com.litematlist.api.LitematListAPI.MaterialItem;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link LitematListIntegration} 的直连实现，由 MixinPlugin 在 LitematList 在场时注入。
 * 1.21.10（1.6.9）与 26.1.2（1.6.2）两个版本的 API 完全一致，无需版本分支。
 */
@Mixin(LitematListIntegration.class)
public abstract class LitematListIntegrationImpl {

    @Overwrite(remap = false)
    public boolean isLoaded() {
        return true;
    }

    /**
     * 读取 LitematList 上传区域的材料列表（已含替换、忽略与项目聚合处理）。
     *
     * @return 条目列表；模组不在、上传区域为空或读取失败时为空列表
     */
    @Overwrite(remap = false)
    public List<MaterialEntry> getMaterialEntries() {
        try {
            List<MaterialItem> items = LitematListAPI.getMaterialList();
            if (items == null || items.isEmpty()) return List.of();

            List<MaterialEntry> result = new ArrayList<>(items.size());
            for (MaterialItem item : items) {
                ItemStack stack = item.itemStack();
                if (stack == null || stack.isEmpty()) continue;
                result.add(new MaterialEntry(stack, item.totalCount(), item.missingCount()));
            }
            return result;
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to read the LitematList material list", e);
            return List.of();
        }
    }

    @Overwrite(remap = false)
    public boolean isMaterialListAvailable() {
        try {
            return LitematListAPI.isAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 读某个物品的原材料配方树。
     *
     * <p>{@code RawMaterialTreeAnalyzer} 不在 LitematList 的 api 包里，但它是 public 且
     * 1.6.9 → 1.7.2 全版本签名一致；它内部读 {@code RawMaterialConfig} 的溯源白名单与
     * 配方优先级，所以玩家在 LitematList 里做的配置天然生效。
     */
    @Overwrite(remap = false)
    public RawNode getRawMaterialTree(Item item, int count, int maxDepth) {
        try {
            if (item == null || count <= 0) return null;

            RawMaterialTreeAnalyzer analyzer = new RawMaterialTreeAnalyzer();
            if (maxDepth >= 0) {
                analyzer.setMaxDepth(maxDepth);
            }
            List<LitematicReader.MaterialEntry> entries =
                    List.of(new LitematicReader.MaterialEntry(item, "", count));
            RawMaterialTreeAnalyzer.TreeResult result = analyzer.analyze(entries);
            if (result == null || result.roots().isEmpty()) return null;

            return toNode(result.roots().get(0));
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to trace raw materials for {}", item, e);
            return null;
        }
    }

    @Unique
    private static RawNode toNode(RecipeTreeNode node) {
        List<RawNode> children = new ArrayList<>(node.subMaterials.size());
        for (RecipeTreeNode child : node.subMaterials) {
            children.add(toNode(child));
        }
        return new RawNode(node.itemId, node.count, node.recipeType, children);
    }
}
