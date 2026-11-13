package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialRequirements;
import com.alonediamond.playercontrolpp.feature.automaterial.restock.RestockMaterials;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.interaction.BlockInteraction;
import com.alonediamond.playercontrolpp.interaction.ContainerOpenWait;
import com.alonediamond.playercontrolpp.inventory.InventoryCounts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** 验证公共组件提取前后的数量口径、开箱等待边界与点击面选择。 */
final class SharedAutomationRegression {
    private static int checks;

    static int run() {
        checks = 0;
        requirementsKeepReservations();
        inventoryExcludesBorrowedContents();
        restockBalancesLooseMaterials();
        containerWaitKeepsTickBoundaries();
        interactionGeometry();
        return checks;
    }

    private static void requirementsKeepReservations() {
        MaterialRequirements requirements = new MaterialRequirements();
        requirements.replaceWith(List.of(
                new MaterialItemEntry(Items.STONE, 64), new MaterialItemEntry(Items.DIRT, 900)));
        check(requirements.entries().getFirst().item == Items.DIRT, "清单仍按需求总量降序");
        check(requirements.includingTarget(Items.STONE, 10).get(1).neededCount == 64,
                "追溯目标较小时保留清单总量");
        var expanded = requirements.includingTarget(Items.STONE, 80);
        check(expanded.size() == 2 && expanded.get(1).neededCount == 80, "追溯增加目标而不重复条目");
        check(requirements.targetFor(Items.STONE) == 64, "临时取物目标不改写清单预留量");
        check(requirements.includingTarget(Items.OAK_LOG, 16).size() == 3, "中间材料加入本次取物");
        check(requirements.targetFor(Items.OAK_LOG) == 0, "中间材料不凭空产生清单预留量");
        requirements.replaceWith(List.of(new MaterialItemEntry(Items.STONE, 32)));
        check(!requirements.contains(Items.DIRT) && requirements.targetFor(Items.STONE) == 32,
                "重新读取清单不继承上轮需求");
    }

    private static void inventoryExcludesBorrowedContents() {
        SimpleContainer inventory = new SimpleContainer(36);
        inventory.setItem(0, new ItemStack(Items.STONE, 10));
        inventory.setItem(1, stoneBox(40));
        inventory.setItem(2, stoneBox(64));
        inventory.setItem(3, new ItemStack(Items.DIRT, 20));

        InventoryCounts counts = InventoryCounts.capture(inventory::getItem, Set.of(2));
        check(counts.loose(Items.STONE) == 10, "散装不包含盒内材料");
        check(counts.owned(Items.STONE) == 50, "自有库存包含自己的盒内材料并排除借盒余额");
        check(counts.owned(Items.SHULKER_BOX) == 2, "借盒外壳仍按背包中的盒子计数");
        check(counts.owned(Items.DIRT) == 20 && counts.owned(Items.OAK_LOG) == 0,
                "无关物品和未持有材料的计数正确");
        check(InventoryCounts.capture(inventory::getItem, Set.of(-1, 36)).owned(Items.STONE) == 114,
                "越界借盒槽位不影响库存");
        inventory.setItem(0, ItemStack.EMPTY);
        check(counts.loose(Items.STONE) == 10, "一次选择使用稳定的计数快照");
        check(InventoryCounts.capture(inventory::getItem, Set.of(2)).owned(Items.STONE) == 40,
                "下一次选择重新读取变化后的库存");
    }

    private static ItemStack stoneBox(int count) {
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,
                ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE, count))));
        return box;
    }

    private static void restockBalancesLooseMaterials() {
        RestockMaterials materials = new RestockMaterials();
        Map<net.minecraft.world.item.Item, Integer> held = new HashMap<>();
        held.put(Items.STONE, 64);
        var entries = List.of(
                new LitematicaIntegration.MaterialEntry(new ItemStack(Items.STONE), 1000, 9999, false),
                new LitematicaIntegration.MaterialEntry(new ItemStack(Items.DIRT), 128, 0, false),
                new LitematicaIntegration.MaterialEntry(new ItemStack(Items.OAK_LOG), 64, 0, true));
        materials.replaceWith(entries, 2, item -> held.getOrDefault(item, 0));
        check(materials.needs().size() == 2 && materials.gapFor(Items.STONE, 64) == 64,
                "续料按散装与配置组数计算，忽略清单的盒内可用量");
        check(materials.wantedItems().size() == 2, "忽略项不进入续料目标和快捷栏整理");

        Slot stone = new Slot(new SimpleContainer(new ItemStack(Items.STONE, 64)), 0, 0, 0);
        Slot dirt = new Slot(new SimpleContainer(new ItemStack(Items.DIRT, 64)), 0, 0, 0);
        var selection = materials.selectLooseStack(List.of(stone, dirt), slot -> true,
                item -> held.getOrDefault(item, 0));
        check(selection.slot() == dirt, "优先补满足比例更低的材料");
        held.put(Items.DIRT, 64);
        selection = materials.selectLooseStack(List.of(stone, dirt), slot -> true,
                item -> held.getOrDefault(item, 0));
        check(selection.slot() == stone, "满足比例相等时保留菜单顺序");
        check(materials.selectLooseStack(List.of(stone), slot -> false, item -> 0) == null,
                "玩家侧槽位不参与容器取物");
        held.put(Items.STONE, 128);
        held.put(Items.DIRT, 128);
        check(!materials.anyMissing(item -> held.getOrDefault(item, 0)), "散装补齐后停止取料");
        materials.replaceWith(entries, 2, item -> held.getOrDefault(item, 0));
        check(materials.needs().isEmpty() && materials.wantedItems().size() == 2,
                "已满足材料仍保留给快捷栏整理使用");
        materials.replaceWith(List.of(new LitematicaIntegration.MaterialEntry(
                new ItemStack(Items.ENDER_PEARL), 1000, 0, false)), 2, item -> 0);
        check(materials.gapFor(Items.ENDER_PEARL, 0) == 32, "续料组数使用材料的实际堆叠上限");
    }

    private static void containerWaitKeepsTickBoundaries() {
        ContainerOpenWait wait = new ContainerOpenWait(10, 2);
        wait.begin();
        for (int tick = 1; tick < 10; tick++) {
            check(wait.tick(false, false) == ContainerOpenWait.Result.WAITING, "未开箱时保留超时宽限");
        }
        check(wait.tick(false, false) == ContainerOpenWait.Result.TIMED_OUT, "第十次检查才判开箱超时");
        wait.begin();
        check(wait.tick(true, true) == ContainerOpenWait.Result.READY, "内容已出现时立即继续");
        wait.begin();
        check(wait.tick(true, false) == ContainerOpenWait.Result.WAITING, "空菜单首次检查继续等待");
        check(wait.tick(true, false) == ContainerOpenWait.Result.READY, "空菜单第二次检查沿用原有宽限");
        wait.begin();
        check(wait.tick(true, false) == ContainerOpenWait.Result.WAITING, "换目标后重置内容等待");
        check(wait.tick(true, true) == ContainerOpenWait.Result.READY, "宽限期间内容到达即可继续");
    }

    private static void interactionGeometry() {
        BlockPos block = BlockPos.ZERO;
        check(BlockInteraction.nearestFace(new Vec3(2, 0.5, 0.5), block) == Direction.EAST, "正 X 点击东面");
        check(BlockInteraction.nearestFace(new Vec3(-1, 0.5, 0.5), block) == Direction.WEST, "负 X 点击西面");
        check(BlockInteraction.nearestFace(new Vec3(2, 2, 2), block) == Direction.EAST, "角点分量相等时仍优先 X");
        check(BlockInteraction.nearestFace(new Vec3(0.5, 2, 2), block) == Direction.UP, "Y 与 Z 相等时仍优先 Y");
        check(BlockInteraction.faceCenter(block, Direction.UP).equals(new Vec3(0.5, 1, 0.5)),
                "点击位置位于所选面的中心");
        check(BlockInteraction.distanceToBlockSq(new Vec3(0.5, 0.5, 0.5), block) == 0,
                "方块外框内的距离为零");
        check(BlockInteraction.distanceToBlockSq(new Vec3(2.5, 0.5, 0.5), block) == 2.25,
                "方块外框距离不混用中心距离");
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
        checks++;
    }
}
