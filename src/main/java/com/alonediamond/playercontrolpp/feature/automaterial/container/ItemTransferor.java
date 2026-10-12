package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

public class ItemTransferor {
    private final GatherContext ctx;
    private final Queue<SlotInfo> neededToTransferSlots = new ArrayDeque<>();

    public ItemTransferor(GatherContext ctx) {
        this.ctx = ctx;
    }

    private static class SlotInfo{
        ItemStack item;
        Slot slotIndex;
        int count;
        boolean isFullBox;
        public SlotInfo(ItemStack item, Slot slot, int count) {
            this.item = item;
            this.slotIndex = slot;
            this.count = count;
        }

        public SlotInfo(ItemStack item, Slot slot, int count, boolean isFullBox) {
            this.count = count;
            this.isFullBox = isFullBox;
            this.item = item;
            this.slotIndex = slot;
        }
    }

    public void transferring(AutoMaterialGatherer amg) {
        if (!neededToTransferSlots.isEmpty()) {
            doTransferItems(amg);
        }

        AbstractContainerMenu menu = amg.mc.player.containerMenu;
        List<Slot> slotsList = menu.slots;
        //MaterialItemEntry currentNeed = ctx.missingItems.getFirst();

        //int currentNeedCount = currentNeed.neededCount;

        /*for (Slot slot : slotsList) {
            ItemStack item = slot.getItem();//槽位物品
            Item currentItem = currentNeed.item;//当前所需物品
            if (currentNeedCount >= Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue()){//整盒优先
                if (ItemUtil.isShulkerBox(item) && ItemUtil.countInside(item, currentItem) >= 27 * item.getMaxStackSize()) {
                    int slotIndex = slot.index;//这个位置有满盒的所需物品
                    currentNeedCount -= 27 * item.getMaxStackSize();
                    if (currentNeedCount <= 0){
                        ctx.missingItems.removeFirst();
                        currentNeed = ctx.missingItems.getFirst();
                        continue;
                    }
                }
            }else {//散装

            }
        }*/
        List<SlotInfo> container = new ArrayList<>();
        for (Slot slot : slotsList) {
            if (slot.container == amg.mc.player.getInventory()) continue;
            if (slot.hasItem()) {
                ItemStack slotItem = slot.getItem();
                container.add(new SlotInfo(slotItem,slot,slotItem.getCount()));
            }
        }
        /*for (int i = Inventory.INVENTORY_SIZE; i < slotsList.size(); i++) {
            Slot slot = slotsList.get(i);
            if (slot.hasItem()) {
                ItemStack slotItem = slot.getItem();
                container.add(new SlotInfo(slotItem,slot.index, slotItem.getCount()));
            }
        }*/
        //List<SlotInfo> neededToTransferSlots = new ArrayList<>();


        for (MaterialItemEntry missingItem : ctx.missingItems) {
            List<SlotInfo> tempIndexes = containsItem(container, missingItem.item, missingItem.neededCount);
            neededToTransferSlots.addAll(tempIndexes);
        }
        doTransferItems(amg);
        if (neededToTransferSlots.isEmpty()) {
            amg.mc.player.closeContainer();
            amg.setState(AutoMaterialGatherer.State.ANALYZING);
        }

    }
    private List<SlotInfo> containsItem(List<SlotInfo> slotInfos, Item target, int missingCount) {
        List<SlotInfo> targetIndexes = new ArrayList<>();

        if (missingCount >= Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue()) {//整盒优先
            for (SlotInfo slotInfo : slotInfos) {
                ItemStack slotItem = slotInfo.item;
                if (ItemUtil.isShulkerBox(slotItem)) {
                    int inside = ItemUtil.countInside(slotItem, target);
                    if (inside >= 27 * target.getDefaultMaxStackSize()){//满盒
                        SlotInfo sI = new SlotInfo(slotInfo.item,slotInfo.slotIndex, slotInfo.count,true);
                        targetIndexes.add(sI);
                        missingCount -= inside;
                    }
                }
                if (missingCount <= 0){
                    return targetIndexes;//满盒足够多，提前返回
                }
            }
        }
        //剩下不满盒
        for (SlotInfo slotInfo : slotInfos) {
            ItemStack slotItem = slotInfo.item;
            int total = 0;
            if (ItemUtil.isShulkerBox(slotItem)) {
                int inside = ItemUtil.countInside(slotItem, target);//盒内物品总数
                if (inside == 0) continue;
                targetIndexes.add(slotInfo);
                total += inside;
                if (total >= missingCount) {//物品够了，返回索引信息
                    return targetIndexes;
                }
            }else {
                if (ItemUtil.is(slotInfo.item,target)) {
                    total += slotInfo.count;
                    targetIndexes.add(slotInfo);
                    if (total >= missingCount) {//物品够了，返回索引信息
                        return targetIndexes;
                    }
                }
            }
        }
        return targetIndexes;//物品不够
    }

    private void doTransferItems(AutoMaterialGatherer amg) {
        if (neededToTransferSlots.isEmpty()) {
            amg.setState(AutoMaterialGatherer.State.ANALYZING);
            amg.mc.player.closeContainer();
            return;
        }

        int totalFreeSlots = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack itemStack = amg.mc.player.getInventory().getItem(i);
            if (itemStack.isEmpty()) {
                totalFreeSlots++;
            }
        }
        if (totalFreeSlots == 36) {
            amg.setState(AutoMaterialGatherer.State.STORING);
        }else {
            int times = Inventory.INVENTORY_SIZE - totalFreeSlots;
            Minecraft mc = amg.mc;
            if (neededToTransferSlots.size() >= times) {
                for (int i = 0; i < times; i++) {
                    SlotActionCompat.quickMove(mc, mc.player.containerMenu.containerId,neededToTransferSlots.poll().slotIndex.index);
                }
            }else {
                while (!neededToTransferSlots.isEmpty()) {
                    SlotActionCompat.quickMove(mc, mc.player.containerMenu.containerId,neededToTransferSlots.poll().slotIndex.index);
                }
            }
        }
    }
}
