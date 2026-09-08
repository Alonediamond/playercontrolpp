package com.alonediamond.playercontrolpp.mixin.compat.litematlist;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.MaterialEntry;

import com.litematlist.api.LitematListAPI;
import com.litematlist.api.LitematListAPI.MaterialItem;

import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

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
}
