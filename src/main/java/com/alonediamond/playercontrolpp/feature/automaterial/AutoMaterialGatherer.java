package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerFinder;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ItemTransferor;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialAnalyzer;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.util.InteractionUtil;
import com.alonediamond.playercontrolpp.feature.restock.MarkedContainerManager;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class AutoMaterialGatherer implements ClientFeature {
    private static final AutoMaterialGatherer INSTANCE = new AutoMaterialGatherer();

    /*public final List<MaterialItemEntry> missingItems = new ArrayList<>();
    public final Queue<Item> processingItems = new LinkedList<>();

    public final List<BlockPos> foundPositions = new ArrayList<>();
    public Item currentTargetItem;

    public boolean wholeBoxPriority;
    public boolean mixedBoxMode;
    public int targetNeededTotal;
    public int currentlyGathered;

    public Set<BlockPos> exhaustedPositions = new HashSet<>();*/

    public void setState(State state) {
        this.state = state;
    }

    public enum State {
        IDLE,
        ANALYZING,//分析材料
        STORING,//存入背包
        SEARCHING,//查找位置
        PATHING,//寻路
        OPENING_CONTAINER,//打开容器
        TRANSFERRING_ITEM,//移动物品
        VERIFYING,//校验并更新
        CRAFTING,//合成材料
        COMPLETED, FAILED, STOPPED//完成
    }
    private State state = State.IDLE;
    private boolean isActive = false;

    public Minecraft mc;

    private final MaterialAnalyzer materialAnalyzer;
    private final GatherContext ctx;
    //private final TaskStateMachine stateMachine;
    //private final BaritonePathingController pathingController;
    //private final ContainerOpener containerOpener;
    //private final ShulkerBoxAccess shulkerAccess;
    //private final RawMaterialTask rawMaterialTask;
    LitematicaIntegration litematica;
    BaritoneIntegration baritone;
    ChestTrackerIntegration chestTracker;
    ContainerFinder containerFinder;
    ItemTransferor itemTransferor;


    private AutoMaterialGatherer() {
        ctx = new GatherContext();
        litematica = LitematicaIntegration.getInstance();
        baritone = BaritoneIntegration.getInstance();
        chestTracker = ChestTrackerIntegration.getInstance();

        materialAnalyzer = new MaterialAnalyzer(litematica,LitematListIntegration.getInstance(),ctx);
        containerFinder = new ContainerFinder(chestTracker,ctx);
        itemTransferor = new ItemTransferor(ctx);
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

            case STORING:
                if (!(mc.player.containerMenu instanceof ShulkerBoxMenu)) {
                    break;
                }
                putItemInInventory(ctx);
                break;

            case SEARCHING:
                containerFinder.search(this);
                break;

            case PATHING:
                checkProgress();
                break;

            case OPENING_CONTAINER:
                if (mc.player.containerMenu instanceof AbstractContainerMenu){
                    setState(State.TRANSFERRING_ITEM);
                }else {
                    return;
                }
                break;

            case TRANSFERRING_ITEM:
                itemTransferor.transferring(this);
                break;

            case VERIFYING:
                break;

            case CRAFTING:

                break;

            case FAILED:
                // 寻路卡住 / 没开始，只是"这一项这次拿不到"：先给追溯任务一次机会，再决定跳过。
                //onCurrentTargetUnavailable();
                break;

            case STOPPED:
            default:
                break;
        }
    }


    private void putItemInInventory(GatherContext ctx) {
        List<Slot> indexes = new ArrayList<>();
        for (Slot slot : mc.player.containerMenu.slots) {
            ItemStack item = slot.getItem();
            if (ctx.needItemTypes.contains(item.getItem())) {
                indexes.add(slot);
            }
        }
        int times = 0;
        for (Slot slot : indexes) {
            if (times >= 27) {
                break;
            }
            SlotActionCompat.quickMove(mc, mc.player.containerMenu.containerId, slot.index/* + 27*/);
            times++;
        }
        mc.player.closeContainer();
        setState(State.ANALYZING);
    }

    public void startPathing(BlockPos pos) {
        baritone.pathTo(pos);
    }

    private void checkProgress() {
        if (PlayerUtil.isInventoryFull(mc.player)) {
            onInventoryFull();
            return;
        }
        if (!baritone.isPathing() && mc.player.blockPosition().distSqr(ctx.currentContainerPos) <= PlayerUtil.blockReachSq(mc.player)) {
            InteractionUtil.openContainerAt(ctx.currentContainerPos,mc);
            setState(State.OPENING_CONTAINER);
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
        //shulkerAccess.resetKnownFullSlots();
        //stateMachine.resetRunState();
        isActive = true;
        setState(State.IDLE);
        ctx.reset();

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.started");
        return true;
    }

    public void stop() {
        baritone.cancelPathing();
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
        state = State.STOPPED;
    }
}
