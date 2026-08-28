package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer.State;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 从打开的容器里往外挪物品。
 *
 * <p>取货量不靠开工前的计划，而是每次点击都用实时缺口重判，规则只有两条：
 * <ul>
 *   <li>缺口 &gt; 整盒阈值 → 优先搬走装有所需物品的整盒（连拿，直到缺口降回阈值以内）；</li>
 *   <li>缺口 ≤ 阈值且 &gt; 0 → 按组取散装补满。</li>
 * </ul>
 *
 * <p>所以缺 1.7 盒会一次性连拿 2 盒；阈值设 1728 时，拿 1 盒后剩余缺口不足一盒，
 * 会自动改拿散装。整个流程没有事先算好的「几盒几组」计划——计划是开工前按当时缺口
 * 定死的，而缺口每拿一下都在变，按计划封顶反而会在同一容器里提前收手。
 */
public class ItemTransferExecutor {

    /** 两次容器点击之间的 tick 数，别超出服务端对点击频率的预期。 */
    private static final int CLICK_COOLDOWN = 4;
    /** 关闭容器后先稳定几 tick 再核对拿到了多少。 */
    private static final int CLOSE_COOLDOWN = 8;

    /**
     * @return 当前物品是否已经凑够，物品栏里的和其中潜影盒内的都算。自动存盒之后用它决定要不要继续。
     */
    public boolean isCurrentItemSatisfied(GatherContext ctx) {
        if (ctx.currentTargetItem == null) return false;
        ctx.currentlyGathered = countEverywhere(ctx.currentTargetItem, ctx.client);
        return ctx.currentlyGathered >= ctx.targetNeededTotal;
    }

    /** 换到下一个缺失物品，或者结束。 */
    public void nextItem(GatherContext ctx, TaskStateMachine tsm) {
        ctx.justTookShulkerBox = false;

        if (ctx.currentItemIndex >= ctx.missingItems.size()) {
            tsm.setState(State.COMPLETED);
            return;
        }

        MaterialItemEntry entry = ctx.missingItems.get(ctx.currentItemIndex);
        ctx.currentTargetItem = entry.item;
        ctx.targetNeededTotal = entry.neededCount;
        ctx.currentlyGathered = countEverywhere(entry.item, ctx.client);
        ctx.currentPosIndex = 0;
        ctx.chestRetryCount = 0;
        ctx.foundPositions.clear();
        // 对上一件物品空手的容器，对这一件可能还有货。
        ctx.exhaustedPositions.clear();

        if (ctx.currentlyGathered >= ctx.targetNeededTotal) {
            ctx.currentItemIndex++;
            tsm.setState(State.NEXT_ITEM);
            return;
        }

        tsm.setState(State.SEARCHING);
    }

    /** 从当前打开的容器里能拿多少拿多少。 */
    public void transfer(GatherContext ctx, TaskStateMachine tsm) {
        Minecraft mc = ctx.client;

        if (ctx.transferCooldown > 0) return;

        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            tsm.setState(State.VERIFYING);
            return;
        }

        if (isInventoryFull(mc)) {
            mc.player.closeContainer();
            tsm.onInventoryFull();
            return;
        }

        AbstractContainerMenu handler = mc.player.containerMenu;
        List<Slot> slots = handler.slots;

        // 整盒优先时先拿盒子，免得散装先把背包塞满、腾不出放盒子的空间。
        // 没开整盒优先时散装在前，但整盒依然可作为散装耗尽后的兜底。
        if (ctx.wholeBoxPriority) {
            if (tryTransferShulkerBoxes(mc, handler, slots, ctx)) return;
            if (tryTransferLooseItems(mc, handler, slots, ctx)) return;
        } else {
            if (tryTransferLooseItems(mc, handler, slots, ctx)) return;
            if (tryTransferShulkerBoxes(mc, handler, slots, ctx)) return;
        }

        // 这次探访一无所获：记进排除表，后续搜索不再来。
        // 不记的话，缓存里早已搬空的容器（箱子追踪刷新前仍显示有货）会被无限开关。
        ctx.exhaustedPositions.add(ctx.currentContainerTarget);

        mc.player.closeContainer();
        ctx.transferCooldown = CLOSE_COOLDOWN;
        tsm.setState(State.VERIFYING);
    }

    /** 关闭容器后重新数一遍，然后决定推进还是换下一个位置。 */
    public void verify(GatherContext ctx, TaskStateMachine tsm,
                        ContainerOpener opener, BaritonePathingController pathing) {
        ctx.currentlyGathered = countEverywhere(ctx.currentTargetItem, ctx.client);

        if (ctx.currentlyGathered >= ctx.targetNeededTotal) {
            ctx.currentItemIndex++;
            tsm.setState(State.NEXT_ITEM);
            return;
        }

        ctx.currentPosIndex++;
        ctx.adjacentContainerTargets = null;
        ctx.adjacentTryIndex = 0;

        if (ctx.currentPosIndex >= ctx.foundPositions.size()) {
            tsm.setState(State.SEARCHING);
        } else {
            navigateToContainer(ctx.foundPositions.get(ctx.currentPosIndex), ctx, tsm, opener, pathing);
        }
    }

    private void navigateToContainer(BlockPos pos, GatherContext ctx, TaskStateMachine tsm,
                                     ContainerOpener opener, BaritonePathingController pathing) {
        if (ctx.client.player == null) return;
        if (ctx.client.player.blockPosition().distSqr(pos) <= PlayerUtil.blockReachSq(ctx.client.player)) {
            tsm.setState(State.OPENING_CONTAINER);
            opener.openContainerAt(pos, ctx);
        } else {
            tsm.setState(State.PATHING);
            pathing.startPathing(pos, ctx);
        }
    }

    // --- Transfer phases ---

    /** 缺口超过阈值时，搬走一个装有所需物品的整盒。 */
    private boolean tryTransferShulkerBoxes(Minecraft mc, AbstractContainerMenu handler,
                                            List<Slot> slots, GatherContext ctx) {
        for (Slot slot : slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemUtil.isShulkerBox(stack)) continue;

            MaterialItemEntry bestEntry = findBestMissingItemForShulker(stack, ctx);
            if (bestEntry == null) continue;

            if (tryTransferShulker(mc, handler, slot, bestEntry, ctx)) {
                ctx.justTookShulkerBox = true;
                ctx.transferCooldown = CLICK_COOLDOWN;
                return true;
            }
        }
        return false;
    }

    /** 取一组所需物品的散装。 */
    private boolean tryTransferLooseItems(Minecraft mc, AbstractContainerMenu handler,
                                          List<Slot> slots, GatherContext ctx) {
        for (Slot slot : slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || ItemUtil.isShulkerBox(stack)) continue;

            MaterialItemEntry matchedEntry = findMatchingMissingItem(stack, ctx);
            if (matchedEntry == null) continue;

            if (tryTransferLoose(mc, handler, slot, matchedEntry, ctx)) {
                ctx.transferCooldown = CLICK_COOLDOWN;
                return true;
            }
        }
        return false;
    }

    private MaterialItemEntry findMatchingMissingItem(ItemStack stack, GatherContext ctx) {
        for (MaterialItemEntry entry : ctx.missingItems) {
            if (ItemUtil.is(stack, entry.item)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * @return {@code shulkerBox} 里我们缺得最狠的那个缺失物品；
     *         但只有缺口超过整盒优先阈值时才返回，低于阈值一律只取散装。
     */
    private MaterialItemEntry findBestMissingItemForShulker(ItemStack shulkerBox, GatherContext ctx) {
        int threshold = Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();
        MaterialItemEntry best = null;
        int bestNeeded = 0;
        for (MaterialItemEntry entry : ctx.missingItems) {
            int needed = entry.neededCount - countEverywhere(entry.item, ctx.client);
            if (needed > threshold
                    && needed > bestNeeded
                    && ItemUtil.containsInside(shulkerBox, entry.item)) {
                bestNeeded = needed;
                best = entry;
            }
        }
        return best;
    }

    /** 取一组所需物品的散装。只看实时缺口，缺口补到 0 自然停。 */
    private boolean tryTransferLoose(Minecraft mc, AbstractContainerMenu handler, Slot slot,
                                     MaterialItemEntry entry, GatherContext ctx) {
        // 口径用 countEverywhere（含盒内持有），与满足判定一致：
        // 拿过整盒后再进散装容器时，只补真实的剩余缺口。
        int needed = entry.neededCount - countEverywhere(entry.item, ctx.client);
        if (needed <= 0) return false;

        try {
            SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 搬走一整盒。每次点击都按实时缺口对照阈值重判：缺口降回阈值以内就不再拿盒，
     * 剩下的缺口交给散装补——这正是「阈值 1728、缺 1.7 盒」先拿 1 盒再改散装的来由。
     */
    private boolean tryTransferShulker(Minecraft mc, AbstractContainerMenu handler, Slot slot,
                                       MaterialItemEntry entry, GatherContext ctx) {
        int needed = entry.neededCount - countEverywhere(entry.item, ctx.client);
        int threshold = Configs.BaritoneSettings.SHULKER_BOX_PRIORITY_THRESHOLD.getIntegerValue();
        if (needed <= threshold) return false;

        try {
            SlotActionCompat.quickMove(mc, handler.containerId, slot.index);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Counting ---

    /** @return 玩家持有多少个 {@code item}，散装加潜影盒内的一起算。 */
    private int countEverywhere(Item item, Minecraft mc) {
        if (mc.player == null || item == null) return 0;
        int count = 0;
        Inventory inventory = mc.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inventory.getItem(i);
            if (ItemUtil.is(stack, item)) {
                count += stack.getCount();
            } else if (ItemUtil.isShulkerBox(stack)) {
                count += ItemUtil.countInside(stack, item);
            }
        }
        return count;
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
