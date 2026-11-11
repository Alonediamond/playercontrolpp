package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.shulker.ShulkerBoxAccess;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.PlayerUtil;

/**
 * 执行当前容器的一次取物；选择规则与点击事务独立，目标推进交回主状态机。
 *
 * <p>一次开箱里会一直取到 {@link ContainerMaterialCollector} 返回 {@code null} 为止——
 * 它每次都在<b>全部缺料</b>里挑，所以一个装着多种所需材料的箱子，开一次就能取完。
 * 取空之后把箱子记进 {@link GatherContext#drainedContainers}，后续搜索不会再打开它。
 */
public final class ItemTransferExecutor {
    private final ShulkerBoxAccess shulkerAccess;
    private final TransferSession transfers = new TransferSession();

    public ItemTransferExecutor(ShulkerBoxAccess access) { shulkerAccess = access; }
    public void reset() { transfers.reset(); }

    public void transfer(GatherContext ctx, AutoMaterialGatherer task) {
        if (ctx.transferCooldown > 0) return;
        var mc = ctx.client;
        var menu = mc.player.containerMenu;
        if (menu == mc.player.inventoryMenu) { task.setState(State.VERIFYING); return; }
        transfers.bind(menu);
        if (!menu.getCarried().isEmpty()) {
            task.setState(State.STOPPED, "playercontrolpp.message.baritone.transfer_interrupted");
            return;
        }
        var selection = ContainerMaterialCollector.select(ctx, menu.slots, transfers::canAttempt);
        if (selection != null) {
            if (selection.unpack()) {
                if (PlayerUtil.isInventoryFull(mc.player)) { task.onInventoryFull(); return; }
                shulkerAccess.startContainerBoxTake(ctx, selection.slot().index, ctx.currentContainerTarget);
                return;
            }
            var result = transfers.attempt(selection.slot(), slot -> SlotTransfer.toPlayer(mc, slot, selection.amount()));
            // 同一个容器内的两次点击之间用单独的间隔：它不受寻路影响，可以比跨容器更短。
            ctx.transferCooldown = Configs.BaritoneSettings.INTRA_CONTAINER_CLICK_DELAY.getIntegerValue();
            if (result == TransferSession.Result.UNCHANGED && PlayerUtil.isInventoryFull(mc.player)) task.onInventoryFull();
            return;
        }

        // 这个容器对"当前这几种缺料"已经没有东西可取了；换物品时清空，因为里面可能还有别的材料。
        if (ctx.currentContainerTarget != null) ctx.exhaustedPositions.add(ctx.currentContainerTarget);
        mc.player.closeContainer();
        ctx.transferCooldown = Configs.BaritoneSettings.CONTAINER_CLOSE_DELAY.getIntegerValue();
        task.setState(State.VERIFYING);
    }
}
