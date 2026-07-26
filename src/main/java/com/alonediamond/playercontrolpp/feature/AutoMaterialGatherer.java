package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.feature.automaterial.*;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Auto material gathering — the public face of the {@code automaterial} package.
 *
 * <p>Delegates to {@code GatherContext}, {@code TaskStateMachine} and the specialised modules
 * around them ({@code MaterialAnalyzer}, {@code ContainerSearcher},
 * {@code BaritonePathingController}, {@code ContainerOpener}, {@code ItemTransferExecutor},
 * {@code ShulkerBoxStorage}).
 *
 * <p>Requires Baritone, Litematica and ChestTracker; without all three the hotkey reports the
 * missing dependency instead of doing nothing.
 */
public class AutoMaterialGatherer implements ClientFeature {
    private static final AutoMaterialGatherer INSTANCE = new AutoMaterialGatherer();

    public enum State {
        IDLE, ANALYZING, SEARCHING, PATHING, OPENING_CONTAINER,
        TRANSFERRING_ITEM, VERIFYING, NEXT_ITEM, COMPLETED, FAILED, STOPPED
    }

    private final GatherContext ctx;
    private final TaskStateMachine stateMachine;
    private final BaritonePathingController pathingController;
    private final ContainerOpener containerOpener;
    private final ShulkerBoxStorage shulkerStorage;

    private AutoMaterialGatherer() {
        ctx = new GatherContext();

        LitematicaIntegration litematica = LitematicaIntegration.getInstance();
        BaritoneIntegration baritone = BaritoneIntegration.getInstance();
        ChestTrackerIntegration chestTracker = ChestTrackerIntegration.getInstance();

        MaterialAnalyzer materialAnalyzer = new MaterialAnalyzer(litematica);
        ContainerSearcher containerSearcher = new ContainerSearcher(chestTracker);
        pathingController = new BaritonePathingController(baritone);
        containerOpener = new ContainerOpener();
        ItemTransferExecutor transferExecutor = new ItemTransferExecutor();
        shulkerStorage = new ShulkerBoxStorage();

        stateMachine = new TaskStateMachine(ctx, materialAnalyzer, containerSearcher,
                pathingController, containerOpener, transferExecutor, shulkerStorage);
    }

    public static AutoMaterialGatherer getInstance() { return INSTANCE; }

    public State getState() { return ctx.state; }

    @Override
    public boolean isActive() { return ctx.active; }

    public boolean toggle() {
        if (ctx.active) {
            stop();
            return false;
        } else {
            return start();
        }
    }

    private boolean start() {
        ctx.client = Minecraft.getInstance();
        if (ctx.client.player == null) return false;

        if (!areAllThreeModsPresent()) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.mods_missing");
            return false;
        }

        pathingController.cancelPathing();
        containerOpener.closeAnyContainer(ctx.client);

        shulkerStorage.resetKnownFullSlots();
        ctx.active = true;
        ctx.reset();

        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.started");
        return true;
    }

    public void stop() {
        pathingController.cancelPathing();
        containerOpener.closeAnyContainer(ctx.client);
        ctx.active = false;
        ctx.state = State.STOPPED;
        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.stopped");
    }

    @Override
    public void onClientTick(Minecraft mc) {
        ctx.client = mc;
        stateMachine.tick();
    }

    @Override
    public void onWorldChange() {
        if (ctx.active) {
            stop();
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.world_change");
        }
    }

    public static boolean areAllThreeModsPresent() {
        FabricLoader loader = FabricLoader.getInstance();
        return (loader.isModLoaded("zbaritone")||loader.isModLoaded("baritone-meteor")||loader.isModLoaded("baritone"))
                && loader.isModLoaded("litematica")
                && loader.isModLoaded("chesttracker");
    }
}
