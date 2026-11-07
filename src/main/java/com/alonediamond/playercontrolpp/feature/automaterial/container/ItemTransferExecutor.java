package com.alonediamond.playercontrolpp.feature.automaterial.container;

import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.shulker.ShulkerBoxAccess;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer.State;

import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.util.PlayerUtil;

/** 执行当前容器的一次取物；选择规则与点击事务独立，目标推进交回主状态机。 */
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
            ctx.transferCooldown = Configs.BaritoneSettings.CLICK_COOLDOWN.getIntegerValue();
            if (result == TransferSession.Result.UNCHANGED && PlayerUtil.isInventoryFull(mc.player)) task.onInventoryFull();
            return;
        }
        if (ctx.currentContainerTarget != null) ctx.exhaustedPositions.add(ctx.currentContainerTarget);
        mc.player.closeContainer();
        ctx.transferCooldown = 6;
        task.setState(State.VERIFYING);
    }
}
