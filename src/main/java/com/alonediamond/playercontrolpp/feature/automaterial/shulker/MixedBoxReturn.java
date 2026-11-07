package com.alonediamond.playercontrolpp.feature.automaterial.shulker;

import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerOpener;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;

import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** 只处理借用潜影盒的归还，优先原槽位；拒绝归还时保留盒子并排除来源，避免反复借取。 */
public final class MixedBoxReturn {
    public static void start(GatherContext ctx, AutoMaterialGatherer task, ContainerOpener opener) {
        if (ctx.mixedBoxReturnTarget == null || ctx.client.player.getEyePosition().distanceToSqr(Vec3.atCenterOf(ctx.mixedBoxReturnTarget)) > PlayerUtil.blockReachSq(ctx.client.player)) {
            finish(ctx, task, false); return;
        }
        task.setState(State.OPENING_CONTAINER);
        opener.openContainerAt(ctx.mixedBoxReturnTarget, ctx);
    }

    public static void opened(GatherContext ctx, AutoMaterialGatherer task, ContainerOpener opener) {
        var mc = ctx.client;
        var menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) {
            if (++ctx.openAttemptCount < 3) opener.openContainerWithRetry(ctx.mixedBoxReturnTarget, ctx.openAttemptCount, ctx);
            else finish(ctx, task, false);
            return;
        }
        int source = PlayerUtil.menuSlotOf(menu, mc.player.getInventory(), ctx.mixedBoxReturnSlot);
        if (source < 0 || !menu.getCarried().isEmpty()
                || !ItemStack.isSameItemSameComponents(menu.getSlot(source).getItem(), ctx.mixedBoxIdentity)) {
            finish(ctx, task, false); return;
        }
        Slot target = null;
        if (ctx.mixedBoxOriginSlot >= 0 && ctx.mixedBoxOriginSlot < menu.slots.size()) {
            Slot original = menu.getSlot(ctx.mixedBoxOriginSlot);
            if (accepts(ctx, original)) target = original;
        }
        if (target == null) for (Slot slot : menu.slots) if (accepts(ctx, slot)) { target = slot; break; }
        if (target == null) { finish(ctx, task, false); return; }
        SlotActionCompat.pickup(mc, menu.containerId, source);
        SlotActionCompat.pickup(mc, menu.containerId, target.index);
        if (!menu.getCarried().isEmpty()) SlotActionCompat.pickup(mc, menu.containerId, source);
        ctx.mixedBoxReturnClosing = true;
        ctx.transferCooldown = 6;
    }

    public static void verify(GatherContext ctx, AutoMaterialGatherer task) {
        ItemStack stack = ctx.client.player.getInventory().getItem(ctx.mixedBoxReturnSlot);
        finish(ctx, task, stack.isEmpty());
    }

    private static boolean accepts(GatherContext ctx, Slot slot) {
        return slot.container != ctx.client.player.getInventory() && !slot.hasItem() && slot.mayPlace(ctx.mixedBoxIdentity);
    }

    private static void finish(GatherContext ctx, AutoMaterialGatherer task, boolean returned) {
        if (returned) ctx.extractionBoxSlots.remove(ctx.mixedBoxReturnSlot);
        else {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.mixed_box_return_failed");
            if (ctx.mixedBoxReturnTarget != null) ctx.exhaustedPositions.add(ctx.mixedBoxReturnTarget);
        }
        ctx.client.player.closeContainer();
        ctx.mixedBoxReturnClosing = false;
        task.clearMixedBoxReturn();
        task.continueAfterExtraction();
    }
}
