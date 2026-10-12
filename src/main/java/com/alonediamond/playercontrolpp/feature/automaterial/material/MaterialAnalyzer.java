package com.alonediamond.playercontrolpp.feature.automaterial.material;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.MaterialSource;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MaterialAnalyzer {

    private final LitematicaIntegration litematica;
    private final LitematListIntegration litematlist;
    private final GatherContext ctx;

    public MaterialAnalyzer(LitematicaIntegration litematica, LitematListIntegration litematlist, GatherContext ctx) {
        this.litematica = litematica;
        this.litematlist = litematlist;
        this.ctx = ctx;
    }

    public void analyze(AutoMaterialGatherer amg) {
        try {
            List<MaterialItemEntry> missing = readMissingMaterials(amg);
            if (missing == null) return;

            ctx.missingItems.clear();
            ctx.missingItems.addAll(missing);

            // 需求量最大的排前面：这些最可能需要整盒搬。
            ctx.missingItems.sort((a, b) -> Integer.compare(b.neededCount, a.neededCount));

            if (ctx.missingItems.isEmpty()) {
                MessageUtil.sendActionBar(amg.mc, "playercontrolpp.message.baritone.all_materials_ready");
                amg.setState(State.COMPLETED);
                return;
            }

            if (QuickShulkerIntegration.getInstance().isLoaded() && Configs.BaritoneSettings.AUTO_STORE_TO_SHULKER.getBooleanValue()) {
                int freeBox = -1;
                boolean hasNeededItems = false;
                boolean hasBox = false;
                for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
                    if (hasNeededItems && freeBox != -1) {
                        break;
                    }
                    ItemStack item = amg.mc.player.getInventory().getItem(i);
                    if (ItemUtil.isShulkerBox(item) && ItemUtil.contentsOf(item).isEmpty() && !hasBox) {
                        freeBox = i;
                        hasBox = true;
                    }else if (ctx.needItemTypes.contains(item.getItem())){
                        hasNeededItems = true;
                    }
                }
                if (hasNeededItems && freeBox != -1) {
                    QuickShulkerIntegration.getInstance().openShulkerBox(freeBox);
                    amg.setState(State.STORING);
                    return;
                }
            }

            if (isInventoryFull(amg.mc)) {
                //amg.onInventoryFull();
                amg.setState(State.FAILED);
                return;
            }

            //ctx.currentItemIndex = 0;
            /*amg.processingItems.clear();
            amg.processingItems.offer(amg.missingItems.getFirst().item);*/
            amg.setState(State.SEARCHING);

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Failed to analyse the material list", e);
            MessageUtil.sendActionBar(amg.mc,
                    "playercontrolpp.message.baritone.analyze_error", String.valueOf(e));
            amg.setState(State.STOPPED);
        }
    }

    /**
     * 按配置的数据来源读出所有还缺的材料。
     *
     * @return 缺失条目；{@code null} 表示读不到清单，已经报过原因并停机。
     */
    private List<MaterialItemEntry> readMissingMaterials(AutoMaterialGatherer amg) {
        if (Configs.BaritoneSettings.MATERIAL_LIST_SOURCE.getOptionListValue() == MaterialSource.LITEMATLIST) {
            return readFromLitematList(amg);
        }
        return readFromLitematica(amg);
    }

    /**
     * 跟随投影：读 Litematica 自己的材料清单。
     *
     * <p>LitematList 接管后 DataManager 里躺着的已是它的注入清单，投影自身的清单读不到，
     * 这条路与「跟随LitematList」也就无从区分——所以检测到接管时直接停机提示，
     * 让玩家在 LitematList 里取消接管，或把数据来源切换成跟随LitematList。
     */
    private List<MaterialItemEntry> readFromLitematica(AutoMaterialGatherer amg) {
        Object materialList = litematica.getMaterialList();
        if (materialList == null) {
            MessageUtil.sendActionBar(amg.mc, "playercontrolpp.message.baritone.no_material_list");
            amg.setState(State.STOPPED);
            return null;
        }

        // DataManager 里的清单被换成了非投影包名的实现（LitematList 的注入清单等）：
        // 投影自身的清单已被接管、无从读取。继续跑只会拿着接管数据冒充「跟随投影」，
        // 所以按玩家「跟随投影」的意图直接停机提示。
        if (!materialList.getClass().getName().startsWith("fi.dy.masa.litematica.")) {
            MessageUtil.sendActionBar(amg.mc,
                    "playercontrolpp.message.baritone.litematlist_took_over");
            amg.setState(State.STOPPED);
            return null;
        }

        // 投影只在自己的 HUD 开着时才维护原生清单。
        /*if (!litematica.isMaterialListHudVisible()) {
            MessageUtil.sendActionBar(amg.mc, "playercontrolpp.message.baritone.no_hud");
            amg.setState(State.STOPPED);
            return null;
        }*/

        Set<String> globalIgnoreSet = buildGlobalIgnoreSet();

        List<MaterialItemEntry> missing = new ArrayList<>();
        for (LitematicaIntegration.MaterialEntry entry : litematica.getMaterialListEntries(amg.mc.player)) {
            if (entry.ignored()) continue;
            ctx.needItemTypes.add(entry.stack().getItem());

            String itemId = BuiltInRegistries.ITEM.getKey(entry.stack().getItem()).toString();
            if (globalIgnoreSet.contains(itemId)) continue;

            // 目标量写总量 countMissing；countAvailable 只用来判断这条还缺不缺。
            if (entry.countAvailable() < entry.countMissing()) {
                missing.add(new MaterialItemEntry(entry.stack().getItem(), entry.countMissing(),
                        entry.stack().getMaxStackSize()));
            }
        }
        return missing;
    }

    /**
     * 跟随 LitematList：读它上传区域的清单。API 返回前已经完成了替换、忽略与项目聚合，
     * 所以 LitematList 里被忽略的项目天然不会出现在结果里。
     */
    private List<MaterialItemEntry> readFromLitematList(AutoMaterialGatherer amg) {
        if (!litematlist.isLoaded()) {
            // 配置还停在 LITEMATLIST 但模组不在（多半是装完配置后把它卸了）。
            MessageUtil.sendActionBar(amg.mc,
                    "playercontrolpp.message.baritone.litematlist_not_loaded");
            amg.setState(State.STOPPED);
            return null;
        }

        List<LitematListIntegration.MaterialEntry> uploaded = litematlist.getMaterialEntries();
        if (uploaded.isEmpty()) {
            MessageUtil.sendActionBar(amg.mc,
                    "playercontrolpp.message.baritone.litematlist_no_list");
            amg.setState(State.STOPPED);
            return null;
        }

        Set<String> globalIgnoreSet = buildGlobalIgnoreSet();
        List<MaterialItemEntry> missing = new ArrayList<>();
        for (LitematListIntegration.MaterialEntry entry : uploaded) {
            String itemId = BuiltInRegistries.ITEM.getKey(entry.stack().getItem()).toString();
            if (globalIgnoreSet.contains(itemId)) continue;
            ctx.needItemTypes.add(entry.stack().getItem());

            // missingCount 是 LitematList 按它自己的口径（含潜影盒内持有量）算出的缺口，
            // 这里只拿它过滤已齐的条目；目标量同样写总量 totalCount，口径与投影路径一致。
            if (entry.missingCount() > 0) {
                missing.add(new MaterialItemEntry(entry.stack().getItem(), entry.totalCount(),
                        entry.stack().getMaxStackSize()));
            }
        }
        return missing;
    }

    private Set<String> buildGlobalIgnoreSet() {
        Set<String> set = new HashSet<>();
        if (!Configs.BaritoneSettings.ENABLE_GLOBAL_IGNORE.getBooleanValue()) return set;
        for (String s : Configs.BaritoneSettings.GLOBAL_IGNORE_LIST.getStrings()) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        return set;
    }

    private boolean isInventoryFull(Minecraft mc) {
        return PlayerUtil.isInventoryFull(mc.player);
    }
}