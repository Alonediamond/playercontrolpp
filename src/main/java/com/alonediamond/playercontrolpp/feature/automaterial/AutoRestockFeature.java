package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.feature.ClientFeature;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.LitematicaIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.MessageUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * 监控 Baritone 的 {@code #litematica} 建造进程，它因缺料暂停时自动补货，然后让建造继续。
 *
 * <h3>启动方式</h3>
 * <ul>
 *   <li><b>一键建造+续料</b>（热键）—— 开关式。按一次同时启动建造并开启监控，再按一次停止。
 *       蓝图建完会自行结束，所以这个热键平时只用于提前中止。</li>
 *   <li><b>标记容器</b>（热键）—— 标记/取消标记准星指向的容器；潜行 + 热键切换启用状态。
 *       数据存在 {@code Configs.Restocks.MARKED_CONTAINERS}，也可在配置界面里改。</li>
 * </ul>
 *
 * <h3>流程</h3>
 * <pre>
 * MONITORING ──建造完成─────────────────────────────→ 停止
 *      │
 *      └─建造暂停→ ANALYZING
 *                    ├─其实不缺料      → 挪材料上快捷栏 → resume
 *                    ├─物品栏有含料盒  → SHULKER_OPEN → SHULKER_TAKE ─┐
 *                    └─要跑标记容器    → PATHING → OPENING →          │
 *                                        TRANSFERRING ────────────────┤
 *                                                                     ▼
 *                                                                 FINISHING
 *                                                            （resume 或重启建造）
 * </pre>
 *
 * <h3>两条「继续建造」的路为什么不一样</h3>
 * 走去标记容器要驱动 Baritone 的 {@code CustomGoalProcess}，而
 * {@code PathingBehavior.cancelEverything()} 会给所有进程发 {@code onLostControl()}，
 * {@code BuilderProcess} 收到后会丢掉整个蓝图——所以跑完容器必须重新启动建造。
 * 从潜影盒取料完全不需要移动，那条路不碰暂停中的建造进程，直接 resume 即可，
 * Baritone 已经建到的层数也保留。
 */
public class AutoRestockFeature implements ClientFeature {

    /**
     * 标记容器热键：直接按 = 标记/取消标记准星指向的容器；潜行 + 按 = 切换该容器的启用状态。
     *
     * <p>禁用的容器留在列表里但续料时不会寻路过去，适合"这箱子暂时空了/太远了"的情况——
     * 比删掉再重新标记省事。
     */
    public static void onMarkContainer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != BlockHitResult.Type.BLOCK) {
            return;
        }

        BlockPos pos = hit.getBlockPos();
        MarkedContainerManager mgr = MarkedContainerManager.getInstance();

        if (mc.player.isShiftKeyDown()) {
            Boolean enabled = mgr.toggleEnabled(pos, mc.level);
            if (enabled != null) {
                MessageUtil.sendActionBar(mc, enabled
                                ? "playercontrolpp.message.restock.container_enabled"
                                : "playercontrolpp.message.restock.container_disabled",
                        pos.getX(), pos.getY(), pos.getZ());
                return;
            }
            // 还没标记过，就按普通标记处理，省得玩家再按一次。
        }

        if (mgr.contains(pos, mc.level)) {
            mgr.remove(pos, mc.level);
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_unmarked",
                    pos.getX(), pos.getY(), pos.getZ());
        } else {
            mgr.add(pos, mc.level);
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_marked",
                    pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public static void onOneClickBuild() {
        getInstance().startOneClick();
    }

    // ---- 单例与功能接口 ----

    private static final AutoRestockFeature INSTANCE = new AutoRestockFeature();

    private AutoRestockFeature() {}

    public static AutoRestockFeature getInstance() { return INSTANCE; }

    enum State {
        IDLE, MONITORING, ANALYZING, PATHING, OPENING, TRANSFERRING,
        SHULKER_OPEN, SHULKER_TAKE, FINISHING
    }

    /**
     * 本轮要凑齐的一种材料。
     *
     * @param item   要收集的物品
     * @param target 玩家最终应在 36 格主物品栏里<em>散装</em>持有多少个。全部用「目标值」表达而不是
     *               「还差多少」，这样每次判断「还需要吗」都是重新看一遍物品栏，不会有计数漂移。
     */
    private record Need(Item item, int target) {}

    private State state = State.IDLE;
    private boolean active;

    /** 当前状态再次执行前先原地等几 tick，让数据包和界面稳定下来。 */
    private int deferTicks;

    /**
     * 本轮是否为了走路而收掉过 Baritone 的建造进程——是的话就必须重启建造，不能 resume。
     */
    private boolean builderCancelled;

    /**
     * 本轮是否已经跑过一趟标记容器。跑这趟时捡到的潜影盒开完又会回到同一个「还缺吗」判断，
     * 没有这个标志的话两个阶段会永远互相甩。
     */
    private boolean containerTripDone;

    /** 本轮续料期间真正进到物品栏里的物品数。 */
    private int cycleGained;
    /** 连续多少轮续料一无所获。用来防死循环。 */
    private int noGainCycles;

    private final BaritoneIntegration baritone = BaritoneIntegration.getInstance();
    private final LitematicaIntegration litematica = LitematicaIntegration.getInstance();
    private final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();
    private final MarkedContainerManager containerManager = MarkedContainerManager.getInstance();

    // 单次续料的临时状态
    /** 玩家还缺的材料。 */
    private final List<Need> needs = new ArrayList<>();
    /** 蓝图仍列为缺失的所有材料类型，不管当前够不够。 */
    private final List<Item> schematicWanted = new ArrayList<>();
    private final List<BlockPos> containerQueue = new ArrayList<>();
    /** 值得打开的潜影盒所在的物品栏槽位（0-35）。 */
    private final List<Integer> shulkerQueue = new ArrayList<>();

    private int containerIndex;
    private BlockPos currentContainerTarget;
    private int pathingTicks;
    private int stuckTicks;
    private int openCooldown;
    private int openRetries;
    private int transferCooldown;
    /** 距上一次真正从界面里挪出物品过了多少 tick。 */
    private int dryTicks;
    private boolean tookFromThisContainer;
    private int shulkerOpenWait;
    /** 在 SHULKER_OPEN 里等「当前打开的是玩家自己的物品栏菜单」等了多少 tick。 */
    private int shulkerReadyWait;
    /** 本轮从标记容器搬走、还没开的潜影盒数量。 */
    private int shulkersTaken;
    private Vec3 lastPlayerPos = Vec3.ZERO;

    private static final int OPEN_WAIT_TICKS = 12;
    private static final int MAX_OPEN_RETRIES = 3;
    /** 连续多少 tick 没挪出任何物品就认为这个容器已经掏空。 */
    private static final int MAX_DRY_TICKS = 8;
    private static final int PATHING_STUCK_LIMIT = 120;
    private static final int PATHING_SETTLE = 5;
    private static final double STUCK_EPSILON_SQ = 0.04;
    /** 分析前留给材料清单和正在关闭的界面稳定的 tick 数。 */
    private static final int ANALYZE_SETTLE = 2;
    /** resume/重启之后先让建造干这么多 tick，再相信下一次「暂停」。 */
    private static final int BUILD_SETTLE = 10;
    /** 等 QuickShulker 开盒的 tick 数，超了就放弃这个盒子。 */
    private static final int SHULKER_OPEN_WAIT = 20;
    /** 发 QuickShulker 开盒包之前，等所有界面关掉的 tick 数。 */
    private static final int SHULKER_READY_WAIT = 40;
    /**
     * 每轮最多搬走几个潜影盒。每个盒子都要等到这趟结束才打开，而在打开前它一直算「还需要」，
     * 不设上限的话一整箱潜影盒会被一次搬空。
     */
    private static final int MAX_SHULKERS_PER_CYCLE = 3;
    private static final int MAX_NO_GAIN_CYCLES = 3;

    // ---- Public API ----

    @Override
    public boolean isActive() { return active; }

    @Override
    public void onClientTick(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.player.isDeadOrDying()) {
            stop("playercontrolpp.message.restock.player_died");
            return;
        }

        if (deferTicks > 0) { deferTicks--; return; }
        if (transferCooldown > 0) { transferCooldown--; }

        switch (state) {
            case IDLE -> {} // active 时不可达
            case MONITORING -> tickMonitoring(mc);
            case ANALYZING -> doAnalyze(mc);
            case PATHING -> tickPathing(mc);
            case OPENING -> tickOpening(mc);
            case TRANSFERRING -> tickTransferring(mc);
            case SHULKER_OPEN -> tickShulkerOpen(mc);
            case SHULKER_TAKE -> tickShulkerTake(mc);
            case FINISHING -> tickFinishing(mc);
        }
    }

    @Override
    public void onWorldChange() {
        if (active) {
            stop("playercontrolpp.message.restock.world_change");
        }
    }

    // ---- Activation ----

    /** 开关：空闲则启动，运行中则停止。一键热键调的就是它。 */
    private void startOneClick() {
        if (active) {
            stop("playercontrolpp.message.restock.stopped");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        if (!litematica.isLoaded() || !baritone.isLoaded()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.mods_missing");
            return;
        }

        resetRunState();

        // 已经在跑的建造不去打扰——玩家可能是自己用 #litematica 起的，现在才想加上续料。
        if (!baritone.isBuilderActive() && !baritone.startLitematicaBuild(0)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_failed");
            return;
        }

        active = true;
        state = State.MONITORING;
        deferTicks = BUILD_SETTLE;
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.started");
    }

    private void stop(String messageKey) {
        baritone.cancelPathing();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        active = false;
        state = State.IDLE;
        resetRunState();
        if (messageKey != null) {
            MessageUtil.sendActionBar(mc, messageKey);
        }
    }

    /** 清掉一轮运行积累的全部状态，重启时不会继承旧计数。 */
    private void resetRunState() {
        deferTicks = 0;
        builderCancelled = false;
        containerTripDone = false;
        cycleGained = 0;
        noGainCycles = 0;
        needs.clear();
        schematicWanted.clear();
        containerQueue.clear();
        shulkerQueue.clear();
        containerIndex = 0;
        currentContainerTarget = null;
        pathingTicks = 0;
        stuckTicks = 0;
        openCooldown = 0;
        openRetries = 0;
        transferCooldown = 0;
        dryTicks = 0;
        tookFromThisContainer = false;
        shulkerOpenWait = 0;
        shulkerReadyWait = 0;
        shulkersTaken = 0;
        lastPlayerPos = Vec3.ZERO;
    }

    // ---- Monitoring ----

    private void tickMonitoring(Minecraft mc) {
        // 建造进程在 onLostControl() 里丢掉蓝图，而它打完「Done building」日志后自己就会调这个。
        // 所以「刚才在跑、现在不在」意味着蓝图建完了（或被别的东西取消了）——两种情况本功能都无事可做。
        // 早先在这里干等，才导致玩家必须再按一次热键。
        if (!baritone.isBuilderActive()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_finished");
            stop(null);
            return;
        }

        if (baritone.isBuilderPaused()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.pause_detected");
            state = State.ANALYZING;
            deferTicks = ANALYZE_SETTLE;
        }
    }

    /** 读 Litematica 材料清单，决定怎么补上缺口。 */
    private void doAnalyze(Minecraft mc) {
        needs.clear();
        schematicWanted.clear();
        containerQueue.clear();
        shulkerQueue.clear();
        containerIndex = 0;
        cycleGained = 0;
        shulkersTaken = 0;
        containerTripDone = false;

        if (!readMaterialList(mc)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.no_material_list");
            stop(null);
            return;
        }

        if (needs.isEmpty()) {
            handlePauseWithoutShortage(mc);
            return;
        }

        // 已经带在身上的潜影盒不用跑路，也不会动到暂停中的建造进程。
        if (shulkerModeEnabled() && quickShulker.isLoaded()) {
            collectShulkerCandidates(mc);
        }
        if (!shulkerQueue.isEmpty()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.shulker_found",
                    shulkerQueue.size());
            state = State.SHULKER_OPEN;
            return;
        }

        startContainerTrip(mc);
    }

    /**
     * 建造暂停了，但玩家其实已经持有 Litematica 列为缺失的全部材料。
     *
     * <p>通常原因是 Baritone 的 {@code allowInventory} 默认关闭，建造时只认快捷栏 9 格——
     * 躺在主背包里的材料它看不见。挪一组到空的快捷栏格就能解开，这就是「继续建造」和
     * 「玩家眼睁睁看着背包里有料、功能却停了」的区别。
     */
    private void handlePauseWithoutShortage(Minecraft mc) {
        int promoted = promoteToHotbar(mc, schematicWanted);
        if (promoted > 0) {
            noGainCycles = 0;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.hotbar_promoted", promoted);
        } else {
            noGainCycles++;
            if (noGainCycles >= MAX_NO_GAIN_CYCLES) {
                MessageUtil.sendActionBar(mc, baritone.allowsInventory()
                        ? "playercontrolpp.message.restock.paused_unreachable"
                        : "playercontrolpp.message.restock.paused_hotbar_full");
                stop(null);
                return;
            }
        }
        resumeOrRelaunch(mc);
    }

    /**
     * 从 Litematica 材料清单填充 {@link #needs} 与 {@link #schematicWanted}。
     *
     * <p>{@code MaterialListEntry.getCountMissing()} 数的是<em>世界里</em>还差多少方块，
     * 且在清单创建时就定死了——{@code updateAvailableCounts()} 只刷新 {@code countAvailable}。
     * 所以它是「目标总量」而不是「还差多少」，早先把它当同一单位去和背包数量比，
     * 正是导致玩家明明拿着一大堆材料却每个容器都被跳过的原因。
     *
     * <p>Litematica 自己的 {@code countAvailable} 也不能用：它把潜影盒和收纳袋里的也算进去，
     * 而那些 Baritone 放不了。「已有」这一侧改为在这里自己数，只数建造真正会取用的 36 个散装格。
     */
    private boolean readMaterialList(Minecraft mc) {
        try {
            Object materialList = litematica.getMaterialList();
            if (materialList == null) return false;

            Object allMaterials = materialList.getClass()
                    .getMethod("getMaterialsAll").invoke(materialList);
            if (!(allMaterials instanceof List<?> allList) || allList.isEmpty()) return false;

            Set<Object> ignored = litematica.getIgnoredSet(materialList);
            int stacks = Math.max(1, Configs.Restocks.RESTOCK_STACKS_PER_ITEM.getIntegerValue());

            for (Object entry : allList) {
                if (ignored.contains(entry)) continue;

                ItemStack stack = (ItemStack) entry.getClass().getMethod("getStack").invoke(entry);
                int countMissing = (Integer) entry.getClass()
                        .getMethod("getCountMissing").invoke(entry);
                if (stack.isEmpty() || countMissing <= 0) continue;

                Item item = stack.getItem();
                schematicWanted.add(item);

                // 整张蓝图通常要的远超一个背包，所以只补到几组而不是追着总数跑——
                // 否则一种材料就把所有格子填满，其余材料永远收不到。
                int target = Math.min(countMissing, Math.max(1, stack.getMaxStackSize()) * stacks);
                if (looseCount(mc, item) < target) {
                    needs.add(new Need(item, target));
                }
            }
            return true;

        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn("Auto-restock: failed to read Litematica material list", e);
            return false;
        }
    }

    // ---- Marked containers ----

    private void startContainerTrip(Minecraft mc) {
        BlockPos playerPos = mc.player.blockPosition();
        MarkedContainerManager.Targets targets = containerManager.pickTargets(mc.level, playerPos);
        if (targets.isEmpty()) {
            // 分清"一个都没标记"和"标记了但全被禁用/超出距离"——后者玩家改个设置就能继续。
            stop(targets.filtered() > 0
                    ? "playercontrolpp.message.restock.all_containers_filtered"
                    : "playercontrolpp.message.restock.no_containers");
            return;
        }
        if (targets.filtered() > 0) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.containers_filtered",
                    targets.disabled(), targets.tooFar());
        }

        // 到这一步才收掉暂停中的建造进程：驱动 CustomGoalProcess 会给它发
        // onLostControl()，那会让它丢掉整个蓝图。
        baritone.cancelPathing();
        builderCancelled = true;
        containerTripDone = true;

        containerQueue.addAll(targets.positions());
        containerIndex = 0;

        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.gathering",
                needs.size(), containerQueue.size());

        navigateToCurrentContainer(mc);
    }

    private void navigateToCurrentContainer(Minecraft mc) {
        if (containerIndex >= containerQueue.size()) {
            afterContainers(mc);
            return;
        }
        currentContainerTarget = containerQueue.get(containerIndex);
        pathingTicks = 0;
        stuckTicks = 0;
        openRetries = 0;
        dryTicks = 0;
        tookFromThisContainer = false;
        lastPlayerPos = mc.player.position();
        baritone.pathTo(currentContainerTarget);
        state = State.PATHING;
    }

    private void nextContainer(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        containerIndex++;
        navigateToCurrentContainer(mc);
    }

    /** 所有标记容器都跑完了。路上捡到的潜影盒现在打开。 */
    private void afterContainers(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        if (shulkerModeEnabled() && quickShulker.isLoaded() && anythingStillNeeded(mc)) {
            collectShulkerCandidates(mc);
            if (!shulkerQueue.isEmpty()) {
                baritone.cancelPathing();
                state = State.SHULKER_OPEN;
                deferTicks = ANALYZE_SETTLE;
                return;
            }
        }
        endCycle(mc);
    }

    private void tickPathing(Minecraft mc) {
        pathingTicks++;

        if (currentContainerTarget != null
                && mc.player.blockPosition().distSqr(currentContainerTarget) <= PlayerUtil.blockReachSq(mc.player)) {
            baritone.cancelPathing();
            openContainer(mc);
            return;
        }

        if (pathingTicks > PATHING_SETTLE) {
            Vec3 pos = mc.player.position();
            if (pos.distanceToSqr(lastPlayerPos) < STUCK_EPSILON_SQ) {
                stuckTicks++;
                if (stuckTicks > PATHING_STUCK_LIMIT) {
                    baritone.cancelPathing();
                    nextContainer(mc);
                    return;
                }
            } else {
                stuckTicks = 0;
            }
            lastPlayerPos = pos;
        }

        if (pathingTicks > PATHING_SETTLE && !baritone.isPathing()) {
            stuckTicks = 0;
            // isPathing() 变 false 也可能是 Baritone 根本没找到路，那时玩家还离得很远，没东西可开。
            if (currentContainerTarget != null
                    && mc.player.blockPosition().distSqr(currentContainerTarget) < 36.0) {
                openContainer(mc);
            } else {
                nextContainer(mc);
            }
        }
    }

    private void openContainer(Minecraft mc) {
        if (currentContainerTarget == null) return;
        openCooldown = 0;
        openRetries = 0;
        tryOpenContainerClick(mc);
    }

    /** 只发 useItemOn 包，不动重试计数。 */
    private void tryOpenContainerClick(Minecraft mc) {
        try {
            Vec3 eye = mc.player.getEyePosition();
            double dx = currentContainerTarget.getX() + 0.5 - eye.x;
            double dy = currentContainerTarget.getY() + 0.5 - eye.y;
            double dz = currentContainerTarget.getZ() + 0.5 - eye.z;
            double distH = Math.sqrt(dx * dx + dz * dz);
            mc.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setYHeadRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
            mc.player.setXRot((float) Math.toDegrees(-Math.atan2(dy, distH)));

            Direction face = ContainerOpener.nearestFace(eye, currentContainerTarget);
            Vec3 hitPos = new Vec3(
                    currentContainerTarget.getX() + 0.5 + face.getStepX() * 0.5,
                    currentContainerTarget.getY() + 0.5 + face.getStepY() * 0.5,
                    currentContainerTarget.getZ() + 0.5 + face.getStepZ() * 0.5);
            BlockHitResult hitResult = new BlockHitResult(hitPos, face, currentContainerTarget, false);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult);

            openCooldown = OPEN_WAIT_TICKS;
            dryTicks = 0;
            state = State.OPENING;
        } catch (Exception e) {
            openRetries++;
            if (openRetries >= MAX_OPEN_RETRIES) {
                nextContainer(mc);
            }
        }
    }

    private void tickOpening(Minecraft mc) {
        if (openCooldown > 0) { openCooldown--; return; }
        if (ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>) {
            openRetries = 0;
            transferCooldown = 0;
            dryTicks = 0;
            state = State.TRANSFERRING;
        } else {
            openRetries++;
            if (openRetries < MAX_OPEN_RETRIES) {
                // 重试打开——不能调 openContainer（那会把 openRetries 清零）。
                openCooldown = 0;
                tryOpenContainerClick(mc);
            } else {
                nextContainer(mc);
            }
        }
    }

    private void tickTransferring(Minecraft mc) {
        // 界面没了（服务端关的，或我们自己关的）——再往里点击也没有意义。
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            nextContainer(mc);
            return;
        }

        if (isInventoryFull(mc)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.inventory_full");
            endCycle(mc);
            return;
        }

        if (transferCooldown > 0) return;

        AbstractContainerMenu menu = mc.player.containerMenu;

        if (takeOneNeeded(mc, menu) || (shulkerModeEnabled() && takeShulkerBox(mc, menu))) {
            tookFromThisContainer = true;
            dryTicks = 0;
            transferCooldown = 2;
            return;
        }

        if (++dryTicks >= MAX_DRY_TICKS) {
            if (!tookFromThisContainer) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.container_no_match");
            }
            nextContainer(mc);
        }
    }

    // ---- 物品栏里的潜影盒（QuickShulker）----

    /** 找出装有我们缺的材料、且数量为 1 的潜影盒所在物品栏槽位。 */
    private void collectShulkerCandidates(Minecraft mc) {
        shulkerQueue.clear();
        Inventory inv = mc.player.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (!QuickShulkerIntegration.isOpenableShulkerBox(stack)) continue;
            if (shulkerHoldsSomethingNeeded(mc, stack)) {
                shulkerQueue.add(i);
            }
        }
    }

    private void tickShulkerOpen(Minecraft mc) {
        // QuickShulker 的包里带的槽位索引是由服务端对着 player.containerMenu 解析的，
        // 所以只要当前打开的不是玩家自己的物品栏菜单，索引就会落到完全不同的槽位上。
        if (!quickShulker.canOpenFromInventory(mc)) {
            if (++shulkerReadyWait > SHULKER_READY_WAIT) {
                afterShulkers(mc);
                return;
            }
            mc.player.closeContainer();
            deferTicks = ANALYZE_SETTLE;
            return;
        }
        shulkerReadyWait = 0;

        Inventory inv = mc.player.getInventory();
        while (!shulkerQueue.isEmpty()) {
            int slot = shulkerQueue.remove(0);
            ItemStack stack = inv.getItem(slot);
            if (!QuickShulkerIntegration.isOpenableShulkerBox(stack)
                    || !shulkerHoldsSomethingNeeded(mc, stack)) {
                continue;
            }
            if (quickShulker.openShulkerBox(QuickShulkerIntegration.menuSlotForInventorySlot(slot))) {
                shulkerOpenWait = SHULKER_OPEN_WAIT;
                transferCooldown = 0;
                dryTicks = 0;
                state = State.SHULKER_TAKE;
                return;
            }
        }

        afterShulkers(mc);
    }

    private void tickShulkerTake(Minecraft mc) {
        if (!(ScreenCompat.getScreen(mc) instanceof AbstractContainerScreen<?>)) {
            if (--shulkerOpenWait <= 0) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.shulker_open_failed");
                state = State.SHULKER_OPEN;
            }
            return;
        }

        if (transferCooldown > 0) return;

        if (isInventoryFull(mc)) {
            mc.player.closeContainer();
            shulkerQueue.clear();
            state = State.SHULKER_OPEN;
            deferTicks = ANALYZE_SETTLE;
            return;
        }

        if (takeOneNeeded(mc, mc.player.containerMenu)) {
            dryTicks = 0;
            transferCooldown = 2;
            return;
        }

        if (++dryTicks >= MAX_DRY_TICKS) {
            mc.player.closeContainer();
            state = State.SHULKER_OPEN;
            deferTicks = ANALYZE_SETTLE;
        }
    }

    private void afterShulkers(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        // 还缺料、而且还有没跑过的容器？那就跑一趟；否则收尾——再绕一圈只会在两个阶段之间弹来弹去。
        if (!containerTripDone && anythingStillNeeded(mc)
                && !containerManager.pickTargets(mc.level, mc.player.blockPosition()).isEmpty()) {
            startContainerTrip(mc);
            return;
        }
        endCycle(mc);
    }

    // ---- Cycle end ----

    /** 一轮续料结束：报告结果、防死循环，然后让建造重新跑起来。 */
    private void endCycle(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }

        if (cycleGained > 0) {
            noGainCycles = 0;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.stock_complete");
        } else {
            noGainCycles++;
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.nothing_found");
            if (noGainCycles >= MAX_NO_GAIN_CYCLES) {
                MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.no_progress");
                stop(null);
                return;
            }
        }

        state = State.FINISHING;
        deferTicks = ANALYZE_SETTLE + 1;
    }

    /**
     * 在最后一个容器关掉之后一两 tick 才跑，保证「挪材料上快捷栏」看到的物品栏与服务端一致。
     */
    private void tickFinishing(Minecraft mc) {
        promoteToHotbar(mc, schematicWanted);
        resumeOrRelaunch(mc);
    }

    /** 建造进程活过了这轮续料就 resume，否则重新启动。 */
    private void resumeOrRelaunch(Minecraft mc) {
        if (!builderCancelled && baritone.isBuilderActive() && baritone.resumeBuilder()) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_resumed");
            state = State.MONITORING;
            deferTicks = BUILD_SETTLE;
            return;
        }
        relaunchBuilder(mc);
    }

    private void relaunchBuilder(Minecraft mc) {
        baritone.cancelPathing();
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        currentContainerTarget = null;
        containerQueue.clear();
        shulkerQueue.clear();

        if (!baritone.startLitematicaBuild(0)) {
            MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_failed");
            stop(null);
            return;
        }

        builderCancelled = false;
        MessageUtil.sendActionBar(mc, "playercontrolpp.message.restock.build_restarted");
        // 直接回到监控：startLitematicaBuild 只有在进程真的跑起来时才报成功；
        // 如果蓝图其实已经建完，建造进程会自己变成非活动，监控那边会把它报成「已完成」。
        state = State.MONITORING;
        deferTicks = BUILD_SETTLE;
    }

    // ---- Transfers ----

    /**
     * 从打开的界面里挪出一组「玩家缺得最狠」的材料。
     *
     * <p>每次都挑满足度最低的那种，缺口就会轮流被补上，而不是让列表里第一种材料把所有空格占满。
     *
     * @return 物品栏真的多出东西了才返回 true。
     */
    private boolean takeOneNeeded(Minecraft mc, AbstractContainerMenu menu) {
        Slot best = null;
        Item bestItem = null;
        double bestRatio = Double.MAX_VALUE;

        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            // 装着东西的潜影盒是储物，交给 takeShulkerBox() 处理；空盒子只是普通建筑材料。
            if (ItemUtil.isShulkerBox(stack) && !ItemUtil.contentsOf(stack).isEmpty()) continue;

            for (Need need : needs) {
                if (!ItemUtil.is(stack, need.item())) continue;
                int have = looseCount(mc, need.item());
                if (have < need.target()) {
                    double ratio = (double) have / need.target();
                    if (ratio < bestRatio) {
                        bestRatio = ratio;
                        best = slot;
                        bestItem = need.item();
                    }
                }
                break;
            }
        }

        if (best == null) return false;

        try {
            int before = looseCount(mc, bestItem);
            SlotActionCompat.quickMove(mc, menu.containerId, best.index);
            // handleContainerInput 会同步把移动应用到客户端菜单上，所以这是真的确认，
            // 不是「假设点击生效了」。
            if (looseCount(mc, bestItem) > before) {
                cycleGained++;
                return true;
            }
        } catch (Exception ignored) {
            // 槽位在扫描与点击之间消失了；交给空转计数处理。
        }
        return false;
    }

    /**
     * 把装有所需材料的整个潜影盒从打开的容器里拿走。盒子等这趟跑完、玩家站住、
     * 没有别的界面挡着时再开。
     */
    private boolean takeShulkerBox(Minecraft mc, AbstractContainerMenu menu) {
        if (shulkersTaken >= MAX_SHULKERS_PER_CYCLE) return false;

        for (Slot slot : menu.slots) {
            if (slot.container == mc.player.getInventory()) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemUtil.isShulkerBox(stack)) continue;
            if (!shulkerHoldsSomethingNeeded(mc, stack)) continue;

            try {
                SlotActionCompat.quickMove(mc, menu.containerId, slot.index);
                shulkersTaken++;
                cycleGained++;
                return true;
            } catch (Exception ignored) {
                // 同上——按一次空转处理。
            }
        }
        return false;
    }

    /**
     * 把需要的材料从主背包挪到空的快捷栏格。
     *
     * <p>只用空格：把玩家自己放在快捷栏的东西挤掉，可能挤走 Baritone 清方块要用的镐子，
     * 那是拿一种卡住换另一种卡住。
     *
     * @return 挪了几组。
     */
    private int promoteToHotbar(Minecraft mc, List<Item> wanted) {
        if (wanted.isEmpty() || mc.player == null) return 0;
        // 槽位索引只有相对服务端当前打开的菜单才有意义。
        if (mc.player.containerMenu != mc.player.inventoryMenu) return 0;

        Inventory inv = mc.player.getInventory();
        int moved = 0;

        for (Item item : wanted) {
            if (hotbarCount(inv, item) > 0) continue;

            int source = -1;
            for (int i = PlayerUtil.HOTBAR_SIZE; i < Inventory.INVENTORY_SIZE; i++) {
                if (ItemUtil.is(inv.getItem(i), item)) { source = i; break; }
            }
            if (source < 0) continue;

            int target = -1;
            for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
                if (inv.getItem(i).isEmpty()) { target = i; break; }
            }
            if (target < 0) break; // 快捷栏满了，不挤掉玩家的东西就无能为力

            // 在 InventoryMenu 空间里主背包保持原索引，所以 source 同时就是界面槽位；
            // 交换按钮就是快捷栏下标本身。
            SlotActionCompat.swapWithHotbar(mc, mc.player.inventoryMenu.containerId, source, target);
            moved++;
        }

        return moved;
    }

    // ---- Helpers ----

    private static boolean shulkerModeEnabled() {
        return Configs.Restocks.RESTOCK_SHULKER_MODE.getBooleanValue();
    }

    private boolean anythingStillNeeded(Minecraft mc) {
        for (Need need : needs) {
            if (looseCount(mc, need.item()) < need.target()) return true;
        }
        return false;
    }

    private boolean shulkerHoldsSomethingNeeded(Minecraft mc, ItemStack shulkerBox) {
        for (ItemStack inner : ItemUtil.contentsOf(shulkerBox)) {
            for (Need need : needs) {
                if (ItemUtil.is(inner, need.item()) && looseCount(mc, need.item()) < need.target()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code item} 在 Baritone 真正取用的 36 个散装格里有多少个。
     *
     * <p>刻意不用 Litematica 的 {@code countAvailable}——它把潜影盒和收纳袋里的也折进去，
     * 一盒石头会被读成「石头充足」，而建造那边其实一个都放不出来。
     */
    private int looseCount(Minecraft mc, Item item) {
        if (mc.player == null) return 0;
        Inventory inv = mc.player.getInventory();
        int count = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (ItemUtil.is(stack, item)) count += stack.getCount();
        }
        return count;
    }

    private static int hotbarCount(Inventory inv, Item item) {
        int count = 0;
        for (int i = 0; i < PlayerUtil.HOTBAR_SIZE; i++) {
            ItemStack stack = inv.getItem(i);
            if (ItemUtil.is(stack, item)) count += stack.getCount();
        }
        return count;
    }

    private boolean isInventoryFull(Minecraft mc) {
        if (mc.player == null) return true;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return false;
        }
        return true;
    }
}
