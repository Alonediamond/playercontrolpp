package com.alonediamond.playercontrolpp.feature.automaterial.shulker;

import com.alonediamond.playercontrolpp.feature.automaterial.GatherContext;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerOpener;
import com.alonediamond.playercontrolpp.feature.automaterial.container.SlotTransfer;
import com.alonediamond.playercontrolpp.feature.automaterial.container.TransferSession;

import com.alonediamond.playercontrolpp.compat.InventoryCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.StorageMode;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** 潜影盒存取周期。借盒、打开、转移、回收均有独立状态和超时；归还由主流程处理。 */
public final class ShulkerBoxAccess {
    public enum StorageResult { ACTIVE, DONE, ABORTED, INVENTORY_FULL, FAILED }
    /** 找不到放置位时最多侧移几次。次数多了会让玩家离原位越来越远，反而更难回到箱子边。 */
    private static final int MAX_NUDGE_ATTEMPTS = 2;
    /** 每次侧移按住方向键多少 tick（约半秒，够走一格）。 */
    private static final int NUDGE_HOLD_TICKS = 10;
    private enum Phase { TAKE, TAKE_WAIT, READY, FIND, NUDGE, NUDGE_WAIT, PLACE, PLACE_WAIT, OPEN, OPEN_WAIT, TRANSFER, CLOSE, MINE, PICKUP }
    private Phase phase;
    private enum Operation { STORE, EXTRACT }
    private Operation operation;
    private boolean active;
    private boolean optionalStorage;
    private boolean useQuickShulker;
    private boolean anyItemsMoved;
    private boolean placed;
    private int phaseTicks, cooldownTicks, boxSlot, selectedSlot, sourceSlot;
    private BlockPos position, returnTarget;
    /** 放置时实际点击的方块与面；{@link #position} 是放下去之后盒子占据的格子。 */
    private BlockPos placeClickPos;
    private Direction placeFace = Direction.UP;
    /** 因为找不到位置而侧移重试过几次。 */
    private int nudgeAttempts;
    private ItemStack identity = ItemStack.EMPTY;
    private final Set<Integer> existingBoxes = new HashSet<>();
    private final Set<Integer> triedBoxes = new HashSet<>();
    private final TransferSession transfers = new TransferSession();
    private final Map<Item, Integer> targets = new HashMap<>();
    private StorageResult result = StorageResult.ACTIVE;

    public boolean isActive() { return active; }
    public static boolean isEnabled() { return Configs.BaritoneSettings.AUTO_STORE_TO_SHULKER.getBooleanValue(); }
    public static boolean isQuickShulkerModeEnabled() {
        return Configs.BaritoneSettings.SHULKER_STORAGE_MODE.getOptionListValue() == StorageMode.QUICKSHULKER
                && QuickShulkerIntegration.getInstance().isLoaded();
    }

    public boolean startStorage(GatherContext ctx) { return startStorage(ctx, false); }
    public boolean startStorage(GatherContext ctx, boolean optionalStorage) {
        begin(ctx, Operation.STORE);
        this.optionalStorage = optionalStorage;
        phase = Phase.READY;
        return true;
    }

    public boolean startContainerBoxTake(GatherContext ctx, int menuSlot, BlockPos source) {
        begin(ctx, Operation.EXTRACT);
        sourceSlot = menuSlot;
        returnTarget = source;
        phase = Phase.TAKE;
        return true;
    }

    public boolean startExtraction(GatherContext ctx, int inventorySlot, BlockPos source, Map<Item, Integer> targets) {
        if (inventorySlot < 0 || inventorySlot >= Inventory.INVENTORY_SIZE) return false;
        begin(ctx, Operation.EXTRACT);
        boxSlot = inventorySlot;
        returnTarget = source;
        if (targets != null) this.targets.putAll(targets);
        phase = Phase.READY;
        return true;
    }

    private void begin(GatherContext ctx, Operation operation) {
        active = true;
        this.operation = operation;
        optionalStorage = false;
        anyItemsMoved = false;
        placed = false;
        useQuickShulker = isQuickShulkerModeEnabled();
        phaseTicks = cooldownTicks = 0;
        boxSlot = sourceSlot = -1;
        position = returnTarget = null;
        selectedSlot = InventoryCompat.getSelectedSlot(ctx.client.player.getInventory());
        result = StorageResult.ACTIVE;
        targets.clear();
        triedBoxes.clear();
        transfers.reset();
        existingBoxes.clear();
    }

    public StorageResult tick(GatherContext ctx) {
        if (!active) return result;
        Minecraft mc = ctx.client;
        if (mc.player == null || mc.level == null || mc.player.isDeadOrDying()) return finish(mc, StorageResult.FAILED);
        if (cooldownTicks > 0) { cooldownTicks--; return StorageResult.ACTIVE; }
        phaseTicks++;
        switch (phase) {
            case TAKE -> {
                Slot source = mc.player.containerMenu.getSlot(sourceSlot);
                if (!QuickShulkerIntegration.isOpenableShulkerBox(source.getItem())) return fail(ctx, "mixed_box_take_failed");
                identity = identityOf(source.getItem());
                rememberBoxes(mc);
                if (SlotTransfer.toPlayer(mc, source, 1) == 0) return fail(ctx, "mixed_box_take_failed");
                enter(Phase.TAKE_WAIT, 5);
            }
            case TAKE_WAIT -> {
                boxSlot = findNewBox(mc);
                if (boxSlot < 0) return fail(ctx, "mixed_box_take_failed");
                ctx.extractionBoxSlots.add(boxSlot);
                ctx.mixedBoxOriginSlot = sourceSlot;
                enter(Phase.READY, 0);
            }
            case READY -> {
                if (mc.player.containerMenu != mc.player.inventoryMenu) {
                    mc.player.closeContainer();
                    if (phaseTicks > 40) return fail(ctx, "shulker_open_failed");
                    return StorageResult.ACTIVE;
                }
                if (operation == Operation.STORE) boxSlot = findStorageBox(ctx);
                if (boxSlot < 0) {
                    if (!anyItemsMoved && !optionalStorage) MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_no_box");
                    return finish(mc, anyItemsMoved ? StorageResult.DONE : optionalStorage ? StorageResult.ABORTED : StorageResult.FAILED);
                }
                ItemStack box = mc.player.getInventory().getItem(boxSlot);
                if (!QuickShulkerIntegration.isOpenableShulkerBox(box)) return fail(ctx, "shulker_no_box");
                identity = identityOf(box);
                if ((operation == Operation.EXTRACT) && returnTarget == null && targets.isEmpty()) return fail(ctx, "mixed_box_empty");
                triedBoxes.add(boxSlot);
                transfers.reset();
                enter(useQuickShulker ? Phase.OPEN : Phase.FIND, 0);
            }
            case FIND -> {
                Placement placement = findPlacement(mc);
                if (placement == null) {
                    // 玩家站在两格交界、贴墙或脚下没实体方块时，原地怎么找都没有合法位置。
                    // 按配置先侧移一格再试；重试次数有限，避免一直挪来挪去。
                    if (Configs.BaritoneSettings.ALLOW_NUDGE_FOR_PLACEMENT.getBooleanValue()
                            && nudgeAttempts < MAX_NUDGE_ATTEMPTS) {
                        nudgeAttempts++;
                        enter(Phase.NUDGE, 0);
                        return StorageResult.ACTIVE;
                    }
                    return fail(ctx, "shulker_no_position");
                }
                position = placement.pos();
                placeClickPos = placement.clicked();
                placeFace = placement.face();
                if (boxSlot >= PlayerUtil.HOTBAR_SIZE) {
                    int menuSlot = PlayerUtil.menuSlotOf(mc.player.inventoryMenu, mc.player.getInventory(), boxSlot);
                    SlotActionCompat.swapWithHotbar(mc, mc.player.inventoryMenu.containerId, menuSlot, selectedSlot);
                    if (returnTarget != null) { ctx.extractionBoxSlots.remove(boxSlot); ctx.extractionBoxSlots.add(selectedSlot); }
                    boxSlot = selectedSlot;
                }
                InventoryCompat.setSelectedSlot(mc.player.getInventory(), boxSlot);
                enter(Phase.PLACE, 3);
            }
            case NUDGE -> {
                // 朝旁边（优先左右，其次前后）挑一个能走的方向，按住移动键半秒。
                Direction side = pickNudgeDirection(mc);
                if (side == null) return fail(ctx, "shulker_no_position");
                SimulatedInput.hold(sideKey(mc, side), this);
                enter(Phase.NUDGE_WAIT, NUDGE_HOLD_TICKS);
            }
            case NUDGE_WAIT -> {
                releaseKeys();
                enter(Phase.FIND, 4);
            }
            case PLACE -> {
                // 目标格允许是可替换方块（草、雪层……），放置会把它顶掉。
                if (!isOpenSpace(mc, position)) return fail(ctx, "shulker_place_failed");
                Vec3 hitPos = Vec3.atCenterOf(placeClickPos == null ? position.below() : placeClickPos)
                        .add(placeFace.getStepX() * 0.5, placeFace.getStepY() * 0.5, placeFace.getStepZ() * 0.5);
                BlockPos clicked = placeClickPos == null ? position.below() : placeClickPos;
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                        new BlockHitResult(hitPos, placeFace, clicked, false));
                enter(Phase.PLACE_WAIT, 5);
            }
            case PLACE_WAIT -> {
                if (mc.level.getBlockState(position).getBlock() instanceof ShulkerBoxBlock
                        && !ItemUtil.isShulkerBox(mc.player.getInventory().getItem(boxSlot))) {
                    placed = true;
                    if (returnTarget != null) ctx.extractionBoxSlots.remove(boxSlot);
                    rememberBoxes(mc);
                    enter(Phase.OPEN, 2);
                } else if (phaseTicks > 30) return fail(ctx, "shulker_place_failed");
            }
            case OPEN -> {
                if (useQuickShulker) {
                    if (!QuickShulkerIntegration.getInstance().openShulkerBox(QuickShulkerIntegration.menuSlotForInventorySlot(boxSlot))) {
                        return fail(ctx, "shulker_open_failed");
                    }
                } else {
                    Direction face = ContainerOpener.nearestFace(mc.player.getEyePosition(), position);
                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(position).add(face.getStepX() * .5, face.getStepY() * .5, face.getStepZ() * .5), face, position, false));
                }
                enter(Phase.OPEN_WAIT, 4);
            }
            case OPEN_WAIT -> {
                if (mc.player.containerMenu instanceof ShulkerBoxMenu) enter(Phase.TRANSFER, 2);
                else if (phaseTicks > 40) return fail(ctx, "shulker_open_failed");
            }
            case TRANSFER -> transferContents(ctx);
            case CLOSE -> {
                mc.player.closeContainer();
                if (useQuickShulker) return afterBox(ctx, boxSlot);
                for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
                    if (mc.player.getInventory().getItem(i).is(ItemTags.PICKAXES)) {
                        InventoryCompat.setSelectedSlot(mc.player.getInventory(), i); break;
                    }
                }
                enter(Phase.MINE, 3);
            }
            case MINE -> {
                if (mc.level.getBlockState(position).isAir()) {
                    releaseKeys();
                    InventoryCompat.setSelectedSlot(mc.player.getInventory(), selectedSlot);
                    enter(Phase.PICKUP, 2);
                } else if (!(mc.level.getBlockState(position).getBlock() instanceof ShulkerBoxBlock)) {
                    return fail(ctx, "shulker_mine_failed");
                } else {
                    Direction face = ContainerOpener.nearestFace(mc.player.getEyePosition(), position);
                    if (phaseTicks == 1) mc.gameMode.startDestroyBlock(position, face);
                    mc.gameMode.continueDestroyBlock(position, face);
                    if (phaseTicks > 160) return fail(ctx, "shulker_mine_failed");
                }
            }
            case PICKUP -> {
                int recovered = findNewBox(mc);
                if (recovered >= 0) { placed = false; return afterBox(ctx, recovered); }
                if (phaseTicks > 100) return fail(ctx, "shulker_pickup_failed");
            }
        }
        return active ? StorageResult.ACTIVE : result;
    }

    /** 两种存取方向共享会话进展检查，每次只执行一次真实转移。 */
    private void transferContents(GatherContext ctx) {
        Minecraft mc = ctx.client;
        if (!(mc.player.containerMenu instanceof ShulkerBoxMenu)) { fail(ctx, "shulker_open_failed"); return; }
        var menu = mc.player.containerMenu;
        if (!menu.getCarried().isEmpty()) { fail(ctx, "transfer_interrupted"); return; }
        // 模拟模式为回收盒子保留一个空格；先归还借盒，再交回满包整理流程。
        if ((operation == Operation.EXTRACT) && emptySlots(mc) <= (placed ? 1 : 0)) {
            ctx.storageAfterExtraction = true;
            enter(Phase.CLOSE, 0);
            return;
        }
        transfers.bind(menu);
        TransferSession.Result outcome = transfers.transferNext(menu.slots,
                slot -> canTransfer(ctx, slot), slot -> transferSlot(ctx, slot));
        if (outcome == TransferSession.Result.EXHAUSTED) {
            enter(Phase.CLOSE, 0);
        } else {
            if (outcome == TransferSession.Result.MOVED) anyItemsMoved = true;
            cooldownTicks = Configs.BaritoneSettings.CLICK_COOLDOWN.getIntegerValue();
        }
    }

    private boolean canTransfer(GatherContext ctx, Slot slot) {
        boolean playerSide = SlotTransfer.isPlayerSlot(ctx.client, slot);
        if ((operation == Operation.EXTRACT) == playerSide) return false;
        return (operation == Operation.EXTRACT) ? extractGap(ctx, slot.getItem().getItem()) > 0 : storable(ctx, slot.getItem());
    }

    private int transferSlot(GatherContext ctx, Slot slot) {
        Minecraft mc = ctx.client;
        if ((operation == Operation.EXTRACT)) return SlotTransfer.toPlayer(mc, slot, extractGap(ctx, slot.getItem().getItem()));
        int before = slot.getItem().getCount();
        SlotActionCompat.quickMove(mc, mc.player.containerMenu.containerId, slot.index);
        return Math.max(0, before - slot.getItem().getCount());
    }

    private StorageResult afterBox(GatherContext ctx, int slot) {
        InventoryCompat.setSelectedSlot(ctx.client.player.getInventory(), selectedSlot);
        if ((operation == Operation.EXTRACT)) {
            if (returnTarget != null) {
                ctx.extractionBoxSlots.add(slot);
                ctx.mixedBoxReturnSlot = slot;
                ctx.mixedBoxReturnTarget = returnTarget;
                ctx.mixedBoxIdentity = ctx.client.player.getInventory().getItem(slot).copy();
            }
            return finish(ctx.client, StorageResult.DONE);
        }
        triedBoxes.add(slot);
        if (!hasStorableMaterials(ctx)) return finish(ctx.client, StorageResult.DONE);
        enter(Phase.READY, 5);
        return StorageResult.ACTIVE;
    }

    private int extractGap(GatherContext ctx, Item item) {
        if (!targets.isEmpty()) return Math.max(0, targets.getOrDefault(item, 0) - ItemUtil.countLoose(ctx.client.player, item));
        for (MaterialItemEntry entry : ctx.wantedEntries()) if (entry.item == item) return Math.max(0, entry.neededCount - ctx.countHeld(item));
        return 0;
    }

    public boolean hasStorableMaterials(GatherContext ctx) { return countStorableMaterials(ctx) > 0; }

    public int countStorableMaterials(GatherContext ctx) {
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = ctx.client.player.getInventory().getItem(i);
            if (storable(ctx, stack)) count += stack.getCount();
        }
        return count;
    }

    private boolean storable(GatherContext ctx, ItemStack stack) {
        return !stack.isEmpty() && !ItemUtil.isShulkerBox(stack)
                && (ctx.isWantedNow(stack.getItem()) || ctx.extraStorableItems.contains(stack.getItem()));
    }

    private int findStorageBox(GatherContext ctx) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (triedBoxes.contains(i) || ctx.extractionBoxSlots.contains(i)) continue;
            ItemStack stack = ctx.client.player.getInventory().getItem(i);
            if (!QuickShulkerIntegration.isOpenableShulkerBox(stack) || ctx.isWantedNow(stack.getItem())) continue;
            List<ItemStack> contents = ItemUtil.contentsOf(stack);
            if (contents.size() < 27) return i;
            for (ItemStack inner : contents) if (inner.isEmpty() || inner.getCount() < inner.getMaxStackSize()) return i;
        }
        return -1;
    }

    /**
     * 一个候选放置位。
     *
     * @param pos     放下去之后潜影盒占据的格子
     * @param clicked 要右键的那个方块
     * @param face    在那个方块上点击的面；{@code pos} 正好是 {@code clicked.relative(face)}
     */
    private record Placement(BlockPos pos, BlockPos clicked, Direction face) {}

    /**
     * 找一个能放下潜影盒、并且放完还打得开的位置。
     *
     * <p>旧实现只看脚步方块的四个水平邻居，玩家站在两格交界处、贴墙、或脚下没有坚实方块时就会失败——
     * 玩家不是一个点，而是一个 0.6×1.8 的碰撞箱，位置判定必须按碰撞箱算。
     *
     * <p>候选范围：以玩家为中心、配置半径内、脚下方块上下各一格的立方体。逐个检查：
     * <ol>
     *   <li>目标格与上方一格都是空气/可替换方块；</li>
     *   <li>不与玩家碰撞箱相交（盒子放下去后玩家仍能站着，而不是把自己埋进去）；</li>
     *   <li>下方是坚实的面；否则找一个相邻的实心方块作为点击面；</li>
     *   <li>眼睛到点击面中心在交互距离内；</li>
     *   <li>放完后能打开——潜影盒的盒盖会朝点击的面翻开，所以盖上那一层也必须是空的。</li>
     * </ol>
     *
     * <p>评分：优先正前方（玩家朝向）、其次距离近。
     *
     * @return 选中的位置；没有合法位置时 {@code null}
     */
    private Placement findPlacement(Minecraft mc) {
        BlockPos feet = mc.player.blockPosition();
        int radius = Math.max(1, Configs.BaritoneSettings.SHULKER_PLACE_RADIUS.getIntegerValue());
        double reachSq = PlayerUtil.blockReachSq(mc.player);
        Vec3 eye = mc.player.getEyePosition();
        AABB playerBox = mc.player.getBoundingBox().inflate(0.05);
        Direction facing = mc.player.getDirection();

        Placement best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    // 玩家自己站的那一格放不了；目标格与上方一格都要空。
                    if (candidate.equals(feet) || !isPlacementSpotFree(mc, candidate)) continue;

                    // 放下去之后盒子会占一格，玩家必须还站得下。
                    if (new AABB(candidate).intersects(playerBox)) continue;

                    Direction clickFace = findClickableFace(mc, candidate);
                    if (clickFace == null) continue;

                    BlockPos clicked = candidate.relative(clickFace.getOpposite());
                    Vec3 hitPos = Vec3.atCenterOf(clicked).add(
                            clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
                    double distSq = eye.distanceToSqr(hitPos);
                    if (distSq > reachSq) continue;

                    // 盒子会朝点击面翻开：那个方向必须是空的，否则开不了。
                    BlockPos lidSpace = candidate.relative(clickFace);
                    if (!isOpenSpace(mc, lidSpace) || lidSpace.equals(feet)) continue;

                    double score = score(candidate, facing, feet, distSq);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Placement(candidate, clicked, clickFace);
                    }
                }
            }
        }
        return best;
    }

    /** 目标格与上方一格都是空气/可替换，且视角射线能打到它。 */
    private static boolean isPlacementSpotFree(Minecraft mc, BlockPos pos) {
        return isOpenSpace(mc, pos) && isOpenSpace(mc, pos.above());
    }

    private static boolean isOpenSpace(Minecraft mc, BlockPos pos) {
        var state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * 找不到放置位时往哪边走一格。
     *
     * <p>优先左右（多半是卡在两格交界或贴着墙），其次前后。要求相邻格可站立：
     * 目标格下方或同高度有落脚点，且脚下这一格本身不阻挡。
     *
     * @return 移动方向；四周都走不了时 {@code null}
     */
    private static Direction pickNudgeDirection(Minecraft mc) {
        Direction facing = mc.player.getDirection();
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        for (Direction dir : new Direction[]{left, right, facing, facing.getOpposite()}) {
            BlockPos next = mc.player.blockPosition().relative(dir);
            if (isOpenSpace(mc, next) && isOpenSpace(mc, next.above())) return dir;
        }
        return null;
    }

    /** 世界方向 → 对应移动键。左右是相对朝向的，所以用 X 分量判断侧移。 */
    private static net.minecraft.client.KeyMapping sideKey(Minecraft mc, Direction dir) {
        Direction facing = mc.player.getDirection();
        if (dir == facing) return mc.options.keyUp;
        if (dir == facing.getOpposite()) return mc.options.keyDown;
        if (dir == facing.getClockWise()) return mc.options.keyRight;
        return mc.options.keyLeft;
    }

    /**
     * 找一个能右键出放置动作的面：下方坚实就用 UP，否则找六个方向里第一个实心的邻居。
     *
     * @return 点击面；四周都没有可附着方块时 {@code null}
     */
    private static Direction findClickableFace(Minecraft mc, BlockPos pos) {
        BlockPos below = pos.below();
        if (mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) return Direction.UP;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            // 点击这个邻居的 dir.getOpposite() 面，方块会被放到 pos 上。
            if (mc.level.getBlockState(neighbor).isFaceSturdy(mc.level, neighbor, dir.getOpposite())) {
                return dir.getOpposite();
            }
        }
        return null;
    }

    /** 越小越优先：正前方 → 同高度 → 距离近。 */
    private static double score(BlockPos candidate, Direction facing, BlockPos feet, double distSq) {
        double score = distSq;
        if (candidate.getY() != feet.getY()) score += 2.0;
        BlockPos delta = candidate.subtract(feet);
        if (delta.getX() == facing.getStepX() && delta.getZ() == facing.getStepZ() && delta.getY() == 0) {
            score -= 4.0;   // 正前方优先
        }
        return score;
    }

    private void rememberBoxes(Minecraft mc) {
        existingBoxes.clear();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (ItemUtil.isShulkerBox(mc.player.getInventory().getItem(i))) existingBoxes.add(i);
    }

    private int findNewBox(Minecraft mc) {
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!existingBoxes.contains(i) && ItemUtil.isShulkerBox(stack) && ItemStack.isSameItemSameComponents(identityOf(stack), identity)) return i;
        }
        return -1;
    }

    private static ItemStack identityOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.CONTAINER);
        return copy;
    }

    private static int emptySlots(Minecraft mc) {
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (mc.player.getInventory().getItem(i).isEmpty()) count++;
        return count;
    }

    private void enter(Phase phase, int cooldownTicks) { this.phase = phase; this.cooldownTicks = cooldownTicks; phaseTicks = 0; }
    public void releaseKeys() { SimulatedInput.releaseAll(this); }
    public void cancel(Minecraft mc) {
        if (active) {
            reportPlacedBox(mc);
            finish(mc, StorageResult.FAILED);
        }
    }
    private void reportPlacedBox(Minecraft mc) {
        if (placed && position != null && mc != null && mc.player != null) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.baritone.shulker_recover_at", position.getX(), position.getY(), position.getZ());
        }
    }
    private StorageResult finish(Minecraft mc, StorageResult result) {
        releaseKeys(); active = false; this.result = result;
        if (mc != null && mc.player != null) InventoryCompat.setSelectedSlot(mc.player.getInventory(), selectedSlot);
        return result;
    }
    private StorageResult fail(GatherContext ctx, String key) {
        MessageUtil.sendActionBar(ctx.client, "playercontrolpp.message.baritone." + key);
        reportPlacedBox(ctx.client);
        // 放置过盒子或已经借到盒子时停机，避免失败后遗忘世界中/背包中的盒子。
        return finish(ctx.client, placed || returnTarget != null ? StorageResult.FAILED : optionalStorage ? StorageResult.ABORTED : StorageResult.FAILED);
    }
}
