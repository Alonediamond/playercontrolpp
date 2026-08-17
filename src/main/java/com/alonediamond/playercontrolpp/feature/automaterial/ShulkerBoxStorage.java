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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 自动备货途中把已收集的建筑材料存进潜影盒，腾出背包空间。
 *
 * <pre>
 * FINDING_SHULKER -&gt; FINDING_POSITION -&gt; SWITCHING_SHULKER -&gt; PLACING
 *                 -&gt; OPENING -&gt; TRANSFERRING -&gt; CLOSING -&gt; MINING -&gt; WAITING_PICKUP -&gt; DONE
 * </pre>
 *
 * <p>装了 QuickShulker 且在配置里选了它时，中间一段整体跳过：盒子就地打开
 * （FINDING_SHULKER -&gt; QUICK_OPEN -&gt; TRANSFERRING -&gt; CLOSING -&gt; DONE），完全不用放置和挖掘。
 */
public class ShulkerBoxStorage {

    public enum StorageState {
        IDLE, FINDING_SHULKER, FINDING_POSITION, SWITCHING_SHULKER,
        PLACING, OPENING, QUICK_OPEN, TRANSFERRING, CLOSING,
        MINING, WAITING_PICKUP, DONE
    }

    public enum StorageResult {
        ACTIVE, DONE, FAILED
    }

    /** 潜影盒界面里盒子自己的槽位：索引 0..26。 */
    private static final int BOX_SLOT_COUNT = ItemTransferStrategy.SHULKER_SLOT_COUNT;
    /** 潜影盒界面里玩家背包的第一个槽位（前面 27 格是盒子的）。 */
    private static final int BOX_SCREEN_PLAYER_START = BOX_SLOT_COUNT;
    /** 潜影盒界面里玩家背包的最后一个槽位。 */
    private static final int BOX_SCREEN_PLAYER_END = BOX_SLOT_COUNT + Inventory.INVENTORY_SIZE - 1;
    /** 单个存储周期内 shift 点击次数的安全上限。 */
    private static final int MAX_TRANSFERS_PER_CYCLE = 200;

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
    private int transferIndex;
    private int prevSelectedSlot;

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

    public boolean startStorage(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (mc.player == null) return false;

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
        transferIndex = 0;
        prevSelectedSlot = InventoryCompat.getSelectedSlot(mc.player.getInventory());
        anyItemsTransferred = false;
        // knownFullSlots 刻意不清：上个周期满的盒子现在还是满的。
        useQuickShulkerMode = isQuickShulkerModeEnabled();

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_store_start");
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
            case IDLE -> state = StorageState.FINDING_SHULKER;
            case FINDING_SHULKER -> doFindShulker(mc, ctx);
            case FINDING_POSITION -> doFindPosition(mc);
            case SWITCHING_SHULKER -> doSwitchToShulker(mc);
            case PLACING -> doPlace(mc);
            case OPENING -> doOpen(mc);
            case QUICK_OPEN -> doQuickOpen(mc);
            case TRANSFERRING -> doTransfer(mc, ctx);
            case CLOSING -> doClose(mc);
            case MINING -> doMine(mc);
            case WAITING_PICKUP -> { return doWaitPickup(mc); }
            case DONE -> {
                active = false;
                return StorageResult.DONE;
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
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!ItemUtil.isShulkerBox(stack)) continue;
            // 不能把材料存进我们正要收集的那种盒子里。
            if (isOnMissingList(stack, ctx)) continue;
            // 「满没满」按打开后的界面判断，不看物品 NBT——NBT 可能是过期的。
            shulkerSlotIndex = i;
            state = useQuickShulkerMode ? StorageState.QUICK_OPEN : StorageState.FINDING_POSITION;
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
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_open_failed");
            fail(mc);
            return;
        }

        retryCount = 0;
        openVerifyTicks = 0;
        transferIndex = 0;
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
        transferIndex = 0;
    }

    private void doTransfer(Minecraft mc, GatherContext ctx) {
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

        // 每 tick 从界面的玩家一侧往盒子里挪一组匹配的物品。
        for (int i = BOX_SCREEN_PLAYER_START;
             i <= BOX_SCREEN_PLAYER_END && transferIndex < MAX_TRANSFERS_PER_CYCLE; i++) {
            transferIndex++;
            Slot slot = handler.getSlot(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            if (!isOnMissingList(stack, ctx)) continue;

            try {
                SlotActionCompat.quickMove(mc, handler.containerId, i);
                anyItemsTransferred = true;
                cooldown = 2;
                return;
            } catch (Exception e) {
                // 这一格不收，试下一格。
            }
        }

        // 一个没有空格的盒子也接不了新的物品类型。
        if (!hasEmptySlotInShulkerBox()) {
            knownFullSlots.add(shulkerSlotIndex);
        }
        state = StorageState.CLOSING;
        cooldown = 3;
    }

    private void doClose(Minecraft mc) {
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen) {
            mc.player.closeContainer();
        }

        if (useQuickShulkerMode) {
            // 什么都没放下去，也就没有东西可挖、可捡。
            InventoryCompat.setSelectedSlot(mc.player.getInventory(), prevSelectedSlot);
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

    private StorageResult doWaitPickup(Minecraft mc) {
        waitTicks++;

        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_store_done");
                releaseKeys();
                active = false;
                state = StorageState.DONE;
                return StorageResult.DONE;
            }
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

    public void cancel(Minecraft mc) { abort(mc); }

    /** 自动备货重新开始时调用，清掉跨周期保留的状态。 */
    public void resetKnownFullSlots() {
        knownFullSlots.clear();
    }

    private boolean isOnMissingList(ItemStack stack, GatherContext ctx) {
        for (MaterialItemEntry entry : ctx.missingItems) {
            if (ItemUtil.is(stack, entry.item)) return true;
        }
        return false;
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
