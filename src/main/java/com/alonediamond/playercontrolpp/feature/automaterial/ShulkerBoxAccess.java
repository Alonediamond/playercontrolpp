package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.StorageMode;
import com.alonediamond.playercontrolpp.feature.ItemTransferStrategy;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * 潜影盒的存取执行器，同时服务两个方向：
 *
 * <ul>
 *   <li>{@link Operation#STORE}（自动存盒）：背包满时把已收集的建筑材料存进潜影盒，腾出空间。
 *   <pre>
 *   FINDING_SHULKER -&gt; FINDING_POSITION -&gt; SWITCHING_SHULKER -&gt; PLACING
 *                   -&gt; OPENING -&gt; TRANSFERRING -&gt; CLOSING -&gt; MINING -&gt; WAITING_PICKUP -&gt; DONE
 *   </pre></li>
 *   <li>{@link Operation#EXTRACT}（开盒取物）：把指定盒子里的材料取出来。用于"识别箱子追踪杂盒"
 *       和"合成前把材料从背包内的盒子里取出来"。
 *   <pre>
 *   CLOSE_CHEST -&gt; FINDING_POSITION -&gt; SWITCHING_SHULKER -&gt; PLACING
 *               -&gt; OPENING -&gt; TRANSFERRING -&gt; CLOSING -&gt; MINING -&gt; WAITING_PICKUP -&gt; DONE
 *   </pre>
 *   取完不自己归还盒子：把"待归还的盒子槽位 + 目标容器"交给主状态机，由它借用现成的
 *   开容器机制塞回去（见 {@code ContainerOpener.checkOpenResult}）。</li>
 * </ul>
 *
 * <p>装了 QuickShulker 且在配置里选了它时，中间一段整体跳过：盒子就地打开
 * （QUICK_OPEN -&gt; TRANSFERRING -&gt; CLOSING -&gt; DONE），完全不用放置和挖掘。
 */
public class ShulkerBoxAccess {

    /** 本次访问潜影盒是往里存还是往外取。 */
    public enum Operation { STORE, EXTRACT }

    public enum StorageState {
        IDLE, TAKE_BOX, FINDING_SHULKER, CLOSE_CHEST, FINDING_POSITION, SWITCHING_SHULKER,
        PLACING, OPENING, QUICK_OPEN, TRANSFERRING, CLOSING,
        MINING, WAITING_PICKUP, DONE
    }

    public enum StorageResult {
        ACTIVE,
        /** 本周期正常结束；EXTRACT 方向可能还留着"待归还的盒子"，见 {@code ctx.mixedBoxReturnSlot}。 */
        DONE,
        /** EXTRACT 方向专有：这次取物做不成（没位置放盒子 / 打不开 / 盒内没有要的东西），跳过当前物品即可。 */
        ABORTED,
        /** EXTRACT 方向专有：背包满了，交给主状态机走"背包已满"流程。 */
        INVENTORY_FULL,
        /** 存盒流程失败，整轮备货停止。 */
        FAILED
    }

    /** 潜影盒界面里盒子自己的槽位：索引 0..26。 */
    private static final int BOX_SLOT_COUNT = ItemTransferStrategy.SHULKER_SLOT_COUNT;
    /** 潜影盒界面里玩家背包的第一个槽位（前面 27 格是盒子的）。 */
    private static final int BOX_SCREEN_PLAYER_START = BOX_SLOT_COUNT;
    /** 潜影盒界面里玩家一侧的槽位数：27 格主背包 + 9 格快捷栏。 */
    private static final int BOX_SCREEN_PLAYER_COUNT = Inventory.INVENTORY_SIZE;
    /** 单个存储周期内最多点几下。挪不动的槽位（原版拒绝放进去的东西）不能把周期卡死。 */
    private static final int MAX_TRANSFER_ATTEMPTS = 200;

    /** 找放盒位置的重试次数。 */
    private static final int MAX_POSITION_RETRIES = 3;
    /** QuickShulker 开盒包没生效时的重试次数。 */
    private static final int MAX_QUICK_OPEN_RETRIES = 5;
    /** 点击后等几 tick 再检查盒子是不是真放下去了。 */
    private static final int PLACE_VERIFY_TICKS = 4;
    /** 放置总尝试次数，超了就算失败。 */
    private static final int MAX_PLACE_ATTEMPTS = 10;
    /** 等盒子界面出现的 tick 数。 */
    private static final int OPEN_VERIFY_LIMIT = 30;
    /** 持续挖掘多少 tick 后认为出了问题。 */
    private static final int MAX_MINING_TICKS = 100;
    /** 等挖下来的盒子被捡起的 tick 数。 */
    private static final int MAX_PICKUP_WAIT_TICKS = 100;
    /** 从容器里 quickMove 一个盒子之后，等物品栏同步过来的 tick 数。 */
    private static final int TAKE_BOX_WAIT_TICKS = 5;

    private StorageState state = StorageState.IDLE;
    private boolean active;
    /** 到达终态时设一次，让 tick() 只报告一次结果。 */
    private StorageResult terminalResult = StorageResult.ACTIVE;
    private int cooldown;
    private int retryCount;
    private int miningTicks;
    private int waitTicks;
    private int openVerifyTicks;

    private final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();
    private boolean useQuickShulkerMode;
    private boolean anyItemsTransferred;
    /** 实测已满的盒子所在物品栏槽位；跨周期保留。 */
    private final java.util.Set<Integer> knownFullSlots = new java.util.HashSet<>();

    private int shulkerSlotIndex = -1;
    private BlockPos placedPos;           // 盒子放在了哪
    private BlockPos placeAgainst;        // 放置时点的那个靠山方块
    private Direction placeClickFace;     // 点的是 placeAgainst 的哪个面
    /** 存盒时扫玩家物品栏的游标（相对玩家一侧的起点），跨 tick 前进，保证快捷栏也能轮到。 */
    private int scanCursor;
    /** 本周期已经点了几下，见 {@link #MAX_TRANSFER_ATTEMPTS}。 */
    private int transferAttempts;
    private int prevSelectedSlot;

    // ---- 取物方向（EXTRACT）专有状态 ----

    private Operation operation = Operation.STORE;
    /**
     * EXTRACT：要取的物品及"希望散装持有到多少个"；为空表示需求1 的开盒取物
     * （按缺失清单/追溯目标的实时缺口，含盒内持有但不含正在开的这个盒子）。
     */
    private final java.util.Map<Item, Integer> extractTargets = new java.util.HashMap<>();
    /** EXTRACT：盒子是从哪个容器拿出来的；{@code null} = 不用归还（从自己背包里取料）。 */
    private BlockPos returnTarget;
    /** STORE：这一趟只是"顺手存一下"，失败不该停掉整轮备货。 */
    private boolean optionalCycle;
    /** 放置盒子之前物品栏里已有的盒子槽位，用来在挖回后认出"我们刚放下去的那个"。 */
    private final java.util.Set<Integer> boxesBeforePlace = new java.util.HashSet<>();
    /** EXTRACT：要从打开的容器里取出的盒子所在菜单槽位。 */
    private int takeBoxMenuSlot = -1;
    /** TAKE_BOX 阶段的等待计时。 */
    private int takeBoxTicks;

    public boolean isActive() { return active; }

    public static boolean isEnabled() {
        return Configs.BaritoneSettings.AUTO_STORE_TO_SHULKER.getBooleanValue();
    }

    /** @return 是否走 QuickShulker 模式：配置里选了它<em>并且</em>它装了。 */
    public static boolean isQuickShulkerModeEnabled() {
        StorageMode mode = (StorageMode) Configs.BaritoneSettings.SHULKER_STORAGE_MODE.getOptionListValue();
        return mode == StorageMode.QUICKSHULKER
                && QuickShulkerIntegration.getInstance().isLoaded();
    }

    /**
     * 开始「从打开的容器里取杂盒 → 开盒取物」的周期。
     *
     * <p>调用时容器界面必须还开着：{@code containerBoxMenuSlot} 是那个盒子的菜单槽位。
     * 取盒、找盒子落点、关容器、开盒、取物、挖回，全部由本周期自己完成；
     * 归还盒子留给主状态机（见类注释）。
     *
     * @return 是否成功进入周期
     */
    public boolean startContainerBoxTake(GatherContext ctx, int containerBoxMenuSlot, BlockPos sourceContainer) {
        if (containerBoxMenuSlot < 0) return false;
        if (!beginCycle(ctx, Operation.EXTRACT)) return false;
        takeBoxMenuSlot = containerBoxMenuSlot;
        returnTarget = sourceContainer;
        state = StorageState.TAKE_BOX;
        return true;
    }

    /** 进入「自动存盒」周期（背包满时的必须动作：失败就停下整轮备货）。 */
    public boolean startStorage(GatherContext ctx) {
        return startStorage(ctx, false);
    }

    /**
     * 进入「自动存盒」周期。
     *
     * @param optional true = 只是顺手把散落的材料收起来（例如原材料合成失败之后）：
     *                 找不到可用盒子就悄悄跳过，不当作失败、更不会停掉整轮备货
     */
    public boolean startStorage(GatherContext ctx, boolean optional) {
        if (!beginCycle(ctx, Operation.STORE)) return false;
        this.optionalCycle = optional;
        state = StorageState.IDLE;
        if (!optional) {
            MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone.shulker_store_start");
        }
        return true;
    }

    /**
     * 进入「开盒取物」周期：盒子已经在玩家物品栏的 {@code boxInventorySlot} 里。
     *
     * @param boxInventorySlot 盒子的物品栏槽位
     * @param sourceContainer  盒子是从哪个容器拿出来的；{@code null} = 不用归还
     * @param targets          只取这些物品，值是"希望散装持有到多少个"；传空表示
     *                         "缺失清单（含追溯目标）上所有还缺的物品"
     */
    public boolean startExtraction(GatherContext ctx, int boxInventorySlot,
                                   BlockPos sourceContainer, Map<Item, Integer> targets) {
        if (boxInventorySlot < 0 || boxInventorySlot >= Inventory.INVENTORY_SIZE) return false;
        if (!beginCycle(ctx, Operation.EXTRACT)) return false;

        shulkerSlotIndex = boxInventorySlot;
        returnTarget = sourceContainer;
        extractTargets.clear();
        if (targets != null) extractTargets.putAll(targets);

        // 起始状态：容器界面还开着，先关掉才能开盒（QuickShulker 要 InventoryMenu，
        // 放置模式要腾出手来放方块）。
        state = StorageState.CLOSE_CHEST;
        return true;
    }

    private boolean beginCycle(GatherContext ctx, Operation op) {
        Minecraft mc = ctx.client;
        if (mc.player == null) return false;

        operation = op;
        state = StorageState.IDLE;
        active = true;
        terminalResult = StorageResult.ACTIVE;
        cooldown = 0;
        retryCount = 0;
        miningTicks = 0;
        waitTicks = 0;
        openVerifyTicks = 0;
        shulkerSlotIndex = -1;
        placedPos = null;
        placeAgainst = null;
        placeClickFace = null;
        scanCursor = 0;
        transferAttempts = 0;
        prevSelectedSlot = InventoryCompat.getSelectedSlot(mc.player.getInventory());
        anyItemsTransferred = false;
        returnTarget = null;
        optionalCycle = false;
        extractTargets.clear();
        boxesBeforePlace.clear();
        takeBoxMenuSlot = -1;
        takeBoxTicks = 0;
        // knownFullSlots 刻意不清：上个周期满的盒子现在还是满的。
        useQuickShulkerMode = isQuickShulkerModeEnabled();
        return true;
    }

    public StorageResult tick(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (!active || mc.player == null) {
            return terminalResult != StorageResult.ACTIVE ? terminalResult : StorageResult.ACTIVE;
        }
        if (cooldown > 0) { cooldown--; return StorageResult.ACTIVE; }
        if (mc.player.isDeadOrDying()) { abort(mc); return StorageResult.FAILED; }

        switch (state) {
            case IDLE -> state = operation == Operation.EXTRACT
                    ? StorageState.CLOSE_CHEST
                    : StorageState.FINDING_SHULKER;
            case TAKE_BOX -> doTakeBox(mc, ctx);
            case FINDING_SHULKER -> doFindShulker(mc, ctx);
            case CLOSE_CHEST -> doCloseChest(mc);
            case FINDING_POSITION -> doFindPosition(mc);
            case SWITCHING_SHULKER -> doSwitchToShulker(mc);
            case PLACING -> doPlace(mc);
            case OPENING -> doOpen(mc);
            case QUICK_OPEN -> doQuickOpen(mc);
            case TRANSFERRING -> doTransfer(mc, ctx);
            case CLOSING -> doClose(mc, ctx);
            case MINING -> doMine(mc);
            case WAITING_PICKUP -> { return doWaitPickup(mc, ctx); }
            case DONE -> {
                active = false;
                return terminalResult;
            }
        }

        if (!active && terminalResult != StorageResult.ACTIVE) {
            return terminalResult;
        }
        return StorageResult.ACTIVE;
    }

    // ---- Phases ----

    private void doFindShulker(Minecraft mc, GatherContext ctx) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (knownFullSlots.contains(i)) continue;
            // 本次为"开盒取物"搬出来的杂盒不许被当成存储盒，否则刚取出来的材料又被装回去。
            if (ctx.extractionBoxSlots.contains(i)) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!ItemUtil.isShulkerBox(stack)) continue;
            // 不能把材料存进我们正要收集的那种盒子里。
            if (isOnMissingList(stack, ctx)) continue;
            // 盒内装着缺失材料的（杂盒），留着待会儿开盒取物，也别拿来装东西：
            // 混在一起之后，取物流程就得先把我们刚存进去的材料再拿出来一次。
            if (ctx.boxContainsWantedItem(stack)) continue;
            // 「满没满」按打开后的界面判断，不看物品 NBT——NBT 可能是过期的。
            shulkerSlotIndex = i;
            state = useQuickShulkerMode ? StorageState.QUICK_OPEN : StorageState.FINDING_POSITION;
            return;
        }
        if (optionalCycle) {
            // 顺手存一下没盒子可用：材料留在背包里继续备货，不当作失败。
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_store_skipped");
            abortWith(mc, StorageResult.ABORTED);
            return;
        }
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_box");
        terminalResult = StorageResult.FAILED;
        active = false;
    }

    /**
     * QuickShulker 模式：盒子在哪就在哪开。不用换手，只要把物品栏索引换算成玩家界面槽位索引，
     * 再发 QuickShulker 自己的包。
     */
    private void doQuickOpen(Minecraft mc) {
        if (!quickShulker.isLoaded()) {
            fail(mc);
            return;
        }

        int screenSlot = playerScreenSlot(shulkerSlotIndex);
        if (!quickShulker.openShulkerBox(screenSlot)) {
            retryCount++;
            if (retryCount < MAX_QUICK_OPEN_RETRIES) {
                cooldown = 3;
                return;
            }
            if (operation == Operation.EXTRACT) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_open_failed");
                abortWith(mc, StorageResult.ABORTED);
                return;
            }
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
            fail(mc);
            return;
        }

        retryCount = 0;
        openVerifyTicks = 0;
        scanCursor = 0;
        transferAttempts = 0;
        state = StorageState.TRANSFERRING;
    }

    /**
     * 在玩家脚下同一高度找个地方放盒子：先前方，再后方与两侧。绝不放在玩家站着的那个方块上。
     */
    private void doFindPosition(Minecraft mc) {
        BlockPos playerFeet = mc.player.blockPosition();

        float yaw = mc.player.getYRot();
        double rad = Math.toRadians(yaw);
        int facingX = (int) -Math.round(Math.sin(rad));
        int facingZ = (int) Math.round(Math.cos(rad));
        if (facingX == 0 && facingZ == 0) { facingZ = 1; }

        int[][] offsets = {
            {facingX, facingZ},
            {facingX * 2, facingZ * 2},
            {-facingX, -facingZ},        // behind
            {facingZ, -facingX},         // right
            {-facingZ, facingX},         // left
        };

        double reachSq = PlayerUtil.blockReachSq(mc.player);

        for (int[] off : offsets) {
            int ox = off[0], oz = off[1];
            BlockPos ground = playerFeet.offset(ox, -1, oz);
            BlockPos placeAt = playerFeet.offset(ox, 0, oz);

            if (placeAt.equals(playerFeet)) continue;

            BlockState groundState = mc.level.getBlockState(ground);
            BlockState placeState = mc.level.getBlockState(placeAt);

            // isFaceSturdy 是「这个方块上面能不能放东西」的正确问法（未废弃）；
            // 老的 isSolid() 是 Mojang 的遗留近似，缓存也差。
            if (!groundState.isFaceSturdy(mc.level, ground, Direction.UP)) continue;
            if (!placeState.isAir() && !placeState.canBeReplaced()) continue;
            if (playerFeet.distSqr(placeAt) > reachSq) continue;

            placedPos = placeAt;
            placeAgainst = ground;
            placeClickFace = Direction.UP;
            retryCount = 0;
            state = StorageState.SWITCHING_SHULKER;
            return;
        }

        retryCount++;
        if (retryCount >= MAX_POSITION_RETRIES) {
            if (operation == Operation.EXTRACT) {
                // 放不下盒子就跳过这个物品：盒子还在背包里，需要的东西也还在盒子里，不会丢。
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_no_position");
                abortWith(mc, StorageResult.ABORTED);
                return;
            }
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_position");
            fail(mc);
            return;
        }
        // 低头看一下、给地面一点加载时间，然后再试。
        mc.player.setXRot(90f);
        cooldown = 10;
    }

    private void doSwitchToShulker(Minecraft mc) {
        if (shulkerSlotIndex < PlayerUtil.HOTBAR_SIZE) {
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), shulkerSlotIndex);
        } else {
            // 用三次点击把盒子换到当前选中的快捷栏格。
            int hotbarSlot = InventoryCompat.getSelectedSlot(mc.player.getInventory());
            int containerId = mc.player.containerMenu.containerId;
            int hotbarScreenSlot = InventoryMenu.USE_ROW_SLOT_START + hotbarSlot;
            SlotActionCompat.pickup(mc, containerId, hotbarScreenSlot);
            SlotActionCompat.pickup(mc, containerId, shulkerSlotIndex);
            SlotActionCompat.pickup(mc, containerId, hotbarScreenSlot);
        }
        cooldown = 3;
        state = StorageState.PLACING;
    }

    private void doPlace(Minecraft mc) {
        if (placedPos == null || placeAgainst == null) { fail(mc); return; }

        if (retryCount == 0) {
            faceToward(mc, Vec3.atCenterOf(placedPos));

            Vec3 hitPos = new Vec3(
                    placeAgainst.getX() + 0.5,
                    placeAgainst.getY() + 1.0,
                    placeAgainst.getZ() + 0.5
            );
            BlockHitResult hitResult = new BlockHitResult(hitPos, placeClickFace, placeAgainst, false);

            try {
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
            } catch (Exception e) {
                // 退回原版自己的射线检测；停止时由 releaseKeys() 松开。
                SimulatedInput.hold(mc.options.keyUse, this);
                cooldown = 2;
                return;
            }
        }

        retryCount++;
        if (retryCount < PLACE_VERIFY_TICKS) {
            cooldown = 2;
            return;
        }

        // 目标位置不再是空气就算放置成功。
        if (mc.level.getBlockState(placedPos).isAir()) {
            if (retryCount < MAX_PLACE_ATTEMPTS) {
                cooldown = 3;
                return;
            }
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_place_failed");
            fail(mc);
            return;
        }

        SimulatedInput.release(mc.options.keyUse, this);
        retryCount = 0;
        // 盒子已经离开物品栏了，此刻记下的槽位就是"除它以外"的盒子。
        rememberExistingBoxes(mc);
        cooldown = 3;
        state = StorageState.OPENING;
    }

    private void doOpen(Minecraft mc) {
        if (placedPos == null) { fail(mc); return; }

        faceToward(mc, Vec3.atCenterOf(placedPos));

        Direction nearestFace = getNearestFace(mc, placedPos);
        Vec3 hitPos = new Vec3(
                placedPos.getX() + 0.5 + nearestFace.getStepX() * 0.5,
                placedPos.getY() + 0.5 + nearestFace.getStepY() * 0.5,
                placedPos.getZ() + 0.5 + nearestFace.getStepZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(hitPos, nearestFace, placedPos, false);

        try {
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);
        } catch (Exception e) {
            SimulatedInput.hold(mc.options.keyUse, this);
            cooldown = 2;
            return;
        }

        cooldown = 6;
        state = StorageState.TRANSFERRING;
        retryCount = 0;
        openVerifyTicks = 0;
        scanCursor = 0;
        transferAttempts = 0;
    }

    /**
     * 取物方向：把盒子里"还需要"的材料一组一组挪进背包。
     *
     * <p>每 tick 只点一次，点完等一个短冷却；扫到没有可取的就把界面关掉。
     * 取哪些物品由 {@link #extractTargets} 决定：为空时按缺失清单 + 实时缺口判断
     * （需求1：同一盒里的多种缺失材料一次全取，缺口补齐的那一种自动停手）。
     */
    private void doExtract(Minecraft mc, GatherContext ctx) {
        if (placedPos != null) {
            faceToward(mc, Vec3.atCenterOf(placedPos));
        }

        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            openVerifyTicks++;
            if (openVerifyTicks > OPEN_VERIFY_LIMIT) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_open_failed");
                abortWith(mc, StorageResult.ABORTED);
            }
            return;
        }
        openVerifyTicks = 0;
        SimulatedInput.release(mc.options.keyUse, this);

        AbstractContainerMenu handler = mc.player.containerMenu;

        // 背包满了就先收手：盒子界面关掉之后，主状态机会走"背包已满"（存盒/提示停机）。
        if (PlayerUtil.isInventoryFull(mc.player)) {
            mc.player.closeContainer();
            abortWith(mc, StorageResult.INVENTORY_FULL);
            return;
        }

        for (int i = 0; i < BOX_SLOT_COUNT; i++) {
            Slot slot = handler.getSlot(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !isWantedExtract(mc, stack, ctx)) continue;

            try {
                SlotActionCompat.quickMove(mc, handler.containerId, i);
                anyItemsTransferred = true;
                cooldown = 2;
            } catch (Exception e) {
                // 这一格没收成，下一 tick 再看；连续失败也不会卡死，因为总会有扫完的一刻。
            }
            return;
        }

        // 盒子里没有还需要的东西了。
        if (!anyItemsTransferred) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_empty");
        }
        state = StorageState.CLOSING;
        cooldown = 3;
    }

    /** @return 这一堆物品是不是这次还要取出来的。 */
    private boolean isWantedExtract(Minecraft mc, ItemStack stack, GatherContext ctx) {
        Item item = stack.getItem();

        // 合成前取料：合成要的是散装材料，所以按散装数量判断够没够。
        if (!extractTargets.isEmpty()) {
            Integer desired = extractTargets.get(item);
            return desired != null && ItemUtil.countLoose(mc.player, item) < desired;
        }

        // 需求1：按"含盒内持有、但不含正在开的这个盒子"算还缺多少。
        // 盒子此刻已经拿在手里了，countEverywhere 会把它算成已有——不把它减掉的话，
        // 永远判定"不缺"，一个物品都取不出来。
        int haveOutside = ItemUtil.countEverywhere(mc.player, item) - countInsideCurrentBox(mc, item);
        for (MaterialItemEntry entry : ctx.wantedEntries()) {
            if (entry.item == item) {
                return haveOutside < entry.neededCount;
            }
        }
        return false;
    }

    /** @return 正在打开的这个盒子里有多少个 {@code item}（模拟模式下盒子在世界里，返回 0）。 */
    private int countInsideCurrentBox(Minecraft mc, Item item) {
        if (shulkerSlotIndex < 0 || shulkerSlotIndex >= Inventory.INVENTORY_SIZE) return 0;
        ItemStack box = mc.player.getInventory().getItem(shulkerSlotIndex);
        return ItemUtil.isShulkerBox(box) ? ItemUtil.countInside(box, item) : 0;
    }

    private void doTransfer(Minecraft mc, GatherContext ctx) {
        if (operation == Operation.EXTRACT) {
            doExtract(mc, ctx);
            return;
        }

        if (placedPos != null) {
            faceToward(mc, Vec3.atCenterOf(placedPos));
        }

        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            openVerifyTicks++;
            if (openVerifyTicks > OPEN_VERIFY_LIMIT) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
                fail(mc);
            }
            return;
        }
        openVerifyTicks = 0;
        SimulatedInput.release(mc.options.keyUse, this);

        AbstractContainerMenu handler = mc.player.containerMenu;

        if (isShulkerBoxFull()) {
            mc.player.closeContainer();
            knownFullSlots.add(shulkerSlotIndex);
            if (!anyItemsTransferred) {
                // 这里塞不下了；只有还有别的盒子值得试才绕回去。
                if (!hasCandidateShulker(mc, ctx)) {
                    MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_box");
                    fail(mc);
                    return;
                }
                cooldown = 3;
                state = StorageState.FINDING_SHULKER;
                return;
            }
            state = StorageState.CLOSING;
            return;
        }

        // 每 tick 从上次停下的地方往后扫一圈，找到一组可存的就挪走；一圈下来都没有就收工。
        //
        // 这里是"游标 + 一整圈"，而不是"每 tick 从头扫 + 总共扫多少格的预算"：
        // 早先用 transferIndex 计扫过的格子数（上限 200），而每 tick 都从 27 号槽重新开始扫，
        // 背包里的空格几下就把预算吃光，界面的 54-62 号槽（快捷栏）永远轮不到——
        // 表现就是"背包满了却存不走快捷栏里的材料"。
        for (int scanned = 0; scanned < BOX_SCREEN_PLAYER_COUNT; scanned++) {
            int i = BOX_SCREEN_PLAYER_START + scanCursor;
            scanCursor = (scanCursor + 1) % BOX_SCREEN_PLAYER_COUNT;

            Slot slot = handler.getSlot(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            // 潜影盒不能放进潜影盒：原版 ShulkerBoxSlot 会拒绝，点了也白点。
            if (ItemUtil.isShulkerBox(stack)) continue;
            if (!isOnMissingList(stack, ctx)) continue;

            try {
                SlotActionCompat.quickMove(mc, handler.containerId, i);
                anyItemsTransferred = true;
                if (++transferAttempts > MAX_TRANSFER_ATTEMPTS) {
                    state = StorageState.CLOSING;
                    cooldown = 3;
                    return;
                }
                cooldown = 2;
                return;
            } catch (Exception e) {
                // 这一格没收成，继续看下一格。
            }
        }

        // 一个没有空格的盒子也接不了新的物品类型。
        if (!hasEmptySlotInShulkerBox()) {
            knownFullSlots.add(shulkerSlotIndex);
        }
        state = StorageState.CLOSING;
        cooldown = 3;
    }

    private void doClose(Minecraft mc, GatherContext ctx) {
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }

        if (useQuickShulkerMode) {
            // 什么都没放下去，也就没有东西可挖、可捡。
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
            if (operation == Operation.EXTRACT) {
                finishExtraction(mc, ctx, shulkerSlotIndex);
            }
            terminalResult = StorageResult.DONE;
            state = StorageState.DONE;
            return;
        }

        cooldown = 3;
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.is(ItemTags.PICKAXES)) {
                InventoryCompat.setSelectedSlot(mc.player.getInventory(), i);
                break;
            }
        }
        miningTicks = 0;
        state = StorageState.MINING;
    }

    private void doMine(Minecraft mc) {
        if (placedPos == null) { active = false; return; }

        faceToward(mc, Vec3.atCenterOf(placedPos));
        Direction face = getNearestFace(mc, placedPos);

        if (miningTicks == 0) {
            mc.gameMode.startDestroyBlock(placedPos, face);
        }

        SimulatedInput.hold(mc.options.keyAttack, this);
        miningTicks++;

        if (miningTicks % 2 == 0) {
            mc.gameMode.continueDestroyBlock(placedPos, face);
        }

        if (mc.level.getBlockState(placedPos).isAir()) {
            SimulatedInput.release(mc.options.keyAttack, this);
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
            waitTicks = 0;
            state = StorageState.WAITING_PICKUP;
            cooldown = 2;
            return;
        }

        if (miningTicks > MAX_MINING_TICKS) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_mine_failed");
            fail(mc);
        }
    }

    private StorageResult doWaitPickup(Minecraft mc, GatherContext ctx) {
        waitTicks++;

        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (!ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) continue;
            // 取物方向必须认出"我们刚放下去的那个"：把玩家自己的盒子误当成它归还，
            // 等于把人家攒的材料连盒送回去。
            if (operation == Operation.EXTRACT && boxesBeforePlace.contains(i)) continue;

            if (operation == Operation.EXTRACT) {
                finishExtraction(mc, ctx, i);
            } else {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_store_done");
            }
            releaseKeys();
            active = false;
            state = StorageState.DONE;
            return StorageResult.DONE;
        }

        if (waitTicks > MAX_PICKUP_WAIT_TICKS) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_pickup_failed");
            releaseKeys();
            active = false;
            state = StorageState.DONE;
            return StorageResult.FAILED;
        }

        return StorageResult.ACTIVE;
    }

    // ---- Helpers ----

    /**
     * 把物品栏索引换算成玩家自己物品栏界面里的槽位索引：
     * 快捷栏 0-8 在界面的 36-44，主背包 9-35 保持原编号。
     */
    private static int playerScreenSlot(int inventoryIndex) {
        return inventoryIndex < PlayerUtil.HOTBAR_SIZE
                ? InventoryMenu.USE_ROW_SLOT_START + inventoryIndex
                : inventoryIndex;
    }

    /**
     * @return 物品栏里是否有存储周期真能挪动的东西：非潜影盒、且在缺失材料清单上的物品堆。
     *         开始一个周期前必须查——没有可存的东西时，一个周期会开盒、什么都不挪、关盒、报 DONE，
     *         而依然满着的背包立刻又启动下一个周期，于是无限开关同一个盒子。
     */
    public boolean hasStorableMaterials(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (mc.player == null) return false;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            // 盒子不能套盒子，所以潜影盒本身永远不算可存物品。
            if (ItemUtil.isShulkerBox(stack)) continue;
            if (isOnMissingList(stack, ctx)) return true;
        }
        return false;
    }

    /** @return 是否还有别的盒子值得打开，免得在已知满的盒子之间打转。 */
    private boolean hasCandidateShulker(Minecraft mc, GatherContext ctx) {
        if (mc.player == null) return false;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (knownFullSlots.contains(i)) continue;
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!ItemUtil.isShulkerBox(stack)) continue;
            if (isOnMissingList(stack, ctx)) continue;
            return true;
        }
        return false;
    }

    /** 松开本功能按下的所有键。 */
    public void releaseKeys() {
        SimulatedInput.releaseAll(this);
    }

    private void abort(Minecraft mc) {
        releaseKeys();
        if (mc.player != null) {
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
        }
        active = false;
    }

    /** 以 FAILED 结果中止，让任务状态机整体停下。 */
    private void fail(Minecraft mc) {
        terminalResult = StorageResult.FAILED;
        abort(mc);
    }

    /**
     * 中止当前周期并指定上报结果。
     *
     * <p>取物方向失败大多只意味着"这个物品这次拿不到"，用 {@link StorageResult#ABORTED} 让主状态机
     * 跳过当前物品继续跑；只有存盒方向才用 {@link StorageResult#FAILED} 整轮停下。
     */
    private void abortWith(Minecraft mc, StorageResult result) {
        terminalResult = result;
        abort(mc);
    }

    /**
     * 记下此刻物品栏里所有潜影盒的槽位。
     *
     * <p>用来在"放置→挖回"之后认出我们刚放下去的那个盒子：放下去之后它不在物品栏里，
     * 所以要在放置成功之后、挖回来之前记这一份。
     */
    private void rememberExistingBoxes(Minecraft mc) {
        boxesBeforePlace.clear();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) {
                boxesBeforePlace.add(i);
            }
        }
    }

    /**
     * 从还开着的容器里把目标盒子搬进背包，并认出它落在哪个物品栏槽位。
     *
     * <p>先记下"搬之前物品栏里都有哪些盒子"，等 quickMove 的往返结果到了再找多出来的那一个；
     * 找不到就说明没搬进来（背包满 / 槽位被同步打乱），直接跳过当前物品。
     */
    private void doTakeBox(Minecraft mc, GatherContext ctx) {
        if (takeBoxTicks == 0) {
            rememberExistingBoxes(mc);
            try {
                SlotActionCompat.quickMove(mc, mc.player.containerMenu.containerId, takeBoxMenuSlot);
            } catch (Exception e) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_take_failed");
                abortWith(mc, StorageResult.ABORTED);
                return;
            }
        }

        takeBoxTicks++;
        if (takeBoxTicks < TAKE_BOX_WAIT_TICKS) {
            cooldown = 1;
            return;
        }

        takeBoxTicks = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (boxesBeforePlace.contains(i)) continue;
            if (!ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) continue;
            shulkerSlotIndex = i;
            ctx.extractionBoxSlots.add(i);
            state = StorageState.CLOSE_CHEST;
            return;
        }

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_take_failed");
        abortWith(mc, StorageResult.ABORTED);
    }

    /**
     * EXTRACT 的起始阶段：关掉还开着的容器界面，等客户端菜单回到玩家自己的物品栏。
     *
     * <p>两件事都要求先关界面：QuickShulker 是按 {@code InventoryMenu} 的槽位索引解析的，
     * 放置模式则需要腾出手来放方块。
     */
    private void doCloseChest(Minecraft mc) {
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
            cooldown = 3;
            return;
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            // 界面没了但菜单还没换回来，再等一 tick。
            cooldown = 1;
            return;
        }

        retryCount = 0;
        state = useQuickShulkerMode ? StorageState.QUICK_OPEN : StorageState.FINDING_POSITION;
    }

    public void cancel(Minecraft mc) { abort(mc); }

    /** 自动备货重新开始时调用，清掉跨周期保留的状态。 */
    public void resetKnownFullSlots() {
        knownFullSlots.clear();
    }

    /**
     * 取物周期收尾：记下盒子现在在哪个物品栏槽位、要不要归还，然后交给主状态机。
     *
     * <p>盒子槽位记进 {@code ctx.extractionBoxSlots}，让自动存盒别再挑中它；
     * 归还成功时主状态机会把它从这个集合里摘掉。
     */
    private void finishExtraction(Minecraft mc, GatherContext ctx, int boxSlot) {
        if (boxSlot < 0) return;
        ctx.extractionBoxSlots.add(boxSlot);
        if (returnTarget != null) {
            ctx.mixedBoxReturnSlot = boxSlot;
            ctx.mixedBoxReturnTarget = returnTarget;
        }
        if (anyItemsTransferred) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.mixed_box_done");
        }
    }

    /**
     * @return 这个物品是不是本次采集要的东西（缺失清单，或原材料追溯正在追的子材料）。
     *         存盒时用它排除"不能拿来当收纳盒"的东西，同时也决定哪些材料值得存。
     */
    private boolean isOnMissingList(ItemStack stack, GatherContext ctx) {
        Item item = stack.getItem();
        // 追溯碰过的材料也算：合成失败时它们会留在背包里，自动存盒该把它们收走。
        return ctx.isWantedNow(item) || ctx.extraStorableItems.contains(item);
    }

    /** @return 打开的盒子里是否还有完全空的格，也就是能不能接新的物品类型。 */
    private boolean hasEmptySlotInShulkerBox() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu == null) return false;
        for (int i = 0; i < BOX_SLOT_COUNT; i++) {
            Slot slot = mc.player.containerMenu.getSlot(i);
            if (slot == null || !slot.hasItem()) return true;
        }
        return false;
    }

    private boolean isShulkerBoxFull() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.containerMenu == null) return true;
        for (int i = 0; i < BOX_SLOT_COUNT; i++) {
            Slot slot = mc.player.containerMenu.getSlot(i);
            if (slot == null || !slot.hasItem()) return false;
            if (slot.getItem().getCount() < slot.getItem().getMaxStackSize()) return false;
        }
        return true;
    }

    private Direction getNearestFace(Minecraft mc, BlockPos pos) {
        return ContainerOpener.nearestFace(mc.player.getEyePosition(), pos);
    }

    private void faceToward(Minecraft mc, Vec3 target) {
        Vec3 eye = mc.player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double distH = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, distH));
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
        mc.player.setXRot(pitch);
    }
}
