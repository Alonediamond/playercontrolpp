package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerMaterialCollector;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.RawMaterialPlanRegression;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.*;

/** 使用真实物品组件验证取物规则，不需要启动游戏窗口或服务器。 */
public final class MaterialCollectorRegression {
    private static int checks;
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        //#if MC >= 260000
        //#if MC >= 260300
        //$$ var registries = net.minecraft.data.registries.VanillaRegistries.createWorldLookup();
        //#else
        var registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        //#endif
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries).forEach(p -> p.apply());
        //#endif
        Item stone = Items.STONE;
        ItemStack full = box(stone, 27, 64);
        check(ContainerMaterialCollector.isFullBox(full, stone), "满盒石头");
        check(!ContainerMaterialCollector.isFullBox(box(stone, 26, 64), stone), "26 组不算满盒");
        check(!ContainerMaterialCollector.isFullBox(box(stone, 27, 63), stone), "27 格未堆满不算满盒");
        check(ContainerMaterialCollector.isFullBox(box(Items.ENDER_PEARL, 27, 16), Items.ENDER_PEARL), "16 堆叠上限");
        List<ItemStack> mixed = new ArrayList<>();
        for (int i = 0; i < 26; i++) mixed.add(new ItemStack(stone, 64));
        mixed.add(new ItemStack(Items.DIRT, 64));
        ItemStack mixedBox = new ItemStack(Items.SHULKER_BOX);
        mixedBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(mixed));
        check(!ContainerMaterialCollector.isFullBox(mixedBox, stone), "杂盒不能按满盒搬走");
        Slot loose = slot(new ItemStack(stone, 64));
        Slot fullSlot = slot(full);
        var result = select(stone, 900, 0, List.of(loose, fullSlot), 864, false);
        check(result.slot() == fullSlot && result.amount() == 1 && !result.unpack(), "900 缺口允许拿一满盒超量");
        result = select(stone, 864, 0, List.of(fullSlot, loose), 864, false);
        check(result.slot() == loose, "等于阈值拿散装");
        result = select(stone, 900, 100, List.of(fullSlot, loose), 864, false);
        check(result.slot() == loose && result.amount() == 800, "实时扣除持有量后判阈值");
        result = select(stone, 900, 1728, List.of(fullSlot, loose), 864, true);
        check(result == null, "超量拿盒后不得再取散装或杂盒");
        result = select(stone, 65, 64, List.of(loose), 864, false);
        check(result.amount() == 1, "散装只补一个");
        check(select(stone, 900, 0, List.of(slot(mixedBox)), 864, false) == null, "关闭识别不借杂盒");
        result = select(stone, 900, 0, List.of(slot(mixedBox)), 864, true);
        check(result.unpack(), "超过阈值但没有满盒时仍可借杂盒取料");
        var needs = List.of(new MaterialItemEntry(stone, 64, 64), new MaterialItemEntry(Items.DIRT, 900, 64));
        Slot dirtBox = slot(box(Items.DIRT, 27, 64));
        result = ContainerMaterialCollector.select(needs, item -> 0, List.of(loose, dirtBox), s -> true, 864, false);
        check(result.slot() == dirtBox, "同一容器其他缺失材料也按满盒优先");
        check(ContainerMaterialCollector.select(needs, item -> 0, List.of(loose), s -> false, 864, true) == null, "不可操作槽位被排除");
        checks += TransferSessionRegression.run();
        checks += SharedAutomationRegression.run();
        checks += RawMaterialPlanRegression.run();
        System.out.println("Material collector regression: " + checks + " checks passed");
    }
    private static ContainerMaterialCollector.Selection select(Item item, int total, int held, List<Slot> slots, int threshold, boolean mixed) {
        return ContainerMaterialCollector.select(List.of(new MaterialItemEntry(item, total, item.getDefaultMaxStackSize())), i -> held, slots, s -> true, threshold, mixed);
    }
    private static Slot slot(ItemStack stack) { return new Slot(new SimpleContainer(stack), 0, 0, 0); }
    private static ItemStack box(Item item, int slots, int count) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        List<ItemStack> contents = new ArrayList<>();
        for (int i = 0; i < slots; i++) contents.add(new ItemStack(item, count));
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        return box;
    }
    private static void check(boolean ok, String scenario) {
        if (!ok) throw new AssertionError(scenario);
        checks++;
    }
}
