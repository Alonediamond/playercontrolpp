package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerFinder;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialAnalyzer;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.restock.MarkedContainerManager;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 自动投影材料备货——{@code automaterial} 包对外的门面。
 *
 * <p>需要 Baritone、Litematica、ChestTracker 三者齐备；缺任何一个时热键会报出缺哪个，
 * 而不是按下去没反应。
 *
 * <p>材料清单来源可选（{@code materialListSource}）：默认跟随投影的信息HUD；
 * 装有 LitematList 时可改为跟随它上传的材料清单，其余行为不变。
 */
public class AutoMaterialGatherer implements ClientFeature {
    private static final AutoMaterialGatherer INSTANCE = new AutoMaterialGatherer();

    public final List<MaterialItemEntry> missingItems = new ArrayList<>();
    public final Queue<Item> processingItems = new LinkedList<>();

    public final List<BlockPos> foundPositions = new ArrayList<>();
    public Item currentTargetItem;

    public boolean wholeBoxPriority;
    public boolean mixedBoxMode;
    public int targetNeededTotal;
    public int currentlyGathered;

    public Set<BlockPos> exhaustedPositions = new HashSet<>();

    public void setState(State state) {
        this.state = state;
    }

    public enum State {
        IDLE, ANALYZING, SEARCHING, PATHING, OPENING_CONTAINER,
        TRANSFERRING_ITEM, VERIFYING, NEXT_ITEM, COMPLETED, FAILED, STOPPED
    }
    private State state = State.IDLE;
    private boolean isActive = false;

    public Minecraft mc;

    private final MaterialAnalyzer materialAnalyzer;
    //private final GatherContext ctx;
    //private final TaskStateMachine stateMachine;
    //private final BaritonePathingController pathingController;
    //private final ContainerOpener containerOpener;
    //private final ShulkerBoxAccess shulkerAccess;
    //private final RawMaterialTask rawMaterialTask;
    LitematicaIntegration litematica;
    BaritoneIntegration baritone;
    ChestTrackerIntegration chestTracker;
    ContainerFinder containerFinder;


    private AutoMaterialGatherer() {
        //ctx = new GatherContext();

        litematica = LitematicaIntegration.getInstance();
        baritone = BaritoneIntegration.getInstance();
        chestTracker = ChestTrackerIntegration.getInstance();

        materialAnalyzer = new MaterialAnalyzer(litematica,LitematListIntegration.getInstance());
        containerFinder = new ContainerFinder(chestTracker);

        //MaterialAnalyzer materialAnalyzer =
        //        new MaterialAnalyzer(litematica, LitematListIntegration.getInstance());
        //ContainerSearcher containerSearcher = new ContainerSearcher(chestTracker);
        //pathingController = new BaritonePathingController(baritone);
        //containerOpener = new ContainerOpener();
        //shulkerAccess = new ShulkerBoxAccess();
        //rawMaterialTask = new RawMaterialTask(shulkerAccess);
        //ItemTransferExecutor transferExecutor = new ItemTransferExecutor(shulkerAccess);

        //stateMachine = new TaskStateMachine(ctx, materialAnalyzer, containerSearcher,
        //        pathingController, containerOpener, transferExecutor, shulkerAccess, rawMaterialTask);
    }

    public static AutoMaterialGatherer getInstance() { return INSTANCE; }

    public State getState() { return state; }

    @Override
    public boolean isActive() { return isActive; }

    public void tick(Minecraft mc) {
        if (!isActive || mc.player == null || mc.player.isDeadOrDying()) {
            if (isActive) {
                setState(State.STOPPED);
            }
            return;
        }

        switch (state) {
            case IDLE:
                if (isActive) {
                    setState(State.ANALYZING);
                }
                break;

            case ANALYZING:
                materialAnalyzer.analyze(this);
                break;

            case SEARCHING:
                containerFinder.search(this);
                break;

            case PATHING:
                checkProgress(this);
                break;

            case TRANSFERRING_ITEM:
                transfer(this);
                break;

            case VERIFYING:
                verify(this);
                break;

            case NEXT_ITEM:
                nextItem(this);
                break;

            case OPENING_CONTAINER:
                // 由上面的 transferCooldown 机制驱动。
                break;

            case FAILED:
                // 寻路卡住 / 没开始，只是"这一项这次拿不到"：先给追溯任务一次机会，再决定跳过。
                onCurrentTargetUnavailable();
                break;

            case STOPPED:
            default:
                break;
        }
    }



    public boolean toggle() {
        if (isActive) {
            stop();
            return false;
        } else {
            return start();
        }
    }

    private boolean start() {
        mc = Minecraft.getInstance();
        if (mc.player == null) return false;

        if (!areAllThreeModsPresent()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mods_missing");
            return false;
        }

        //pathingController.cancelPathing();
        //containerOpener.closeAnyContainer(mc);
//
        //shulkerAccess.resetKnownFullSlots();
        //stateMachine.resetRunState();
        isActive = true;
        //ctx.reset();

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.started");
        return true;
    }

    public void stop() {
        //pathingController.cancelPathing();
        //containerOpener.closeAnyContainer(mc);
        // 追溯任务可能正开着合成界面、或让 Baritone 挖着矿；取盒子状态机也可能正开着盒子界面。
        //rawMaterialTask.cancel(mc);
        //shulkerAccess.cancel(mc);
        isActive = false;
        state = State.STOPPED;
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.stopped");
    }

    @Override
    public void onClientTick(Minecraft mc) {
        tick(mc);
    }

    @Override
    public void onWorldChange() {
        if (isActive) {
            stop();
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.world_change");
        }
    }

    public static boolean areAllThreeModsPresent() {
        FabricLoader loader = FabricLoader.getInstance();
        return (loader.isModLoaded("zbaritone")||loader.isModLoaded("baritone-meteor")||loader.isModLoaded("baritone"))
                && loader.isModLoaded("litematica")
                && loader.isModLoaded("chesttracker");
    }

    public void onInventoryFull() {
        this.missingItems.clear();
        this.processingItems.clear();
        state = State.STOPPED;
    }
}
