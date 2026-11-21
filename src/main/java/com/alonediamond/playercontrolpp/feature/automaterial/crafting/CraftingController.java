package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes.Plan;
import com.alonediamond.playercontrolpp.compat.CraftingRecipes;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.config.Configs;
import com.alonediamond.playercontrolpp.config.CraftStationSource;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.CraftBatchActions.Craft;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.WorkstationOrder.ExactPlacement;
import com.alonediamond.playercontrolpp.integration.BaritoneIntegration;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.interaction.BlockInteraction;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * 选择并访问工作台或切石机，推进摆料、取产物和关闭菜单的合成流程。
 *
 * <p>QuickShulker 的 {@code OpenShulkerPacket} 对工作台、切石机同样有效
 * （它自己的 {@code quickCraftingTables} / {@code quickStonecutter} 开关控制），
 * 而且这两种方块没有被 {@code ignoreSingleStackCheck} 限制，堆叠放着也能开。
 *
 * <p>固定配方的批次直接精确摆料，避免原版摆料包动用其他目标的预留。
 * 其他路径先尝试摆料包，超时后改为手动摆料；跨版本配方访问集中在 {@link CraftingRecipes}。
 * 切石机先放一个输入确认实际产量，再按缺口补足本批，始终不超过调用方给出的投入预算。
 */
final class CraftingController {

    public enum Result {
        ACTIVE, DONE,
        /** 摆料/取产物失败（配方对不上、格子摆不进去……）。 */
        FAILED,
        /** 背包满了，产物拿不出来。 */
        INVENTORY_FULL
    }

    /** 用哪种工作站。 */
    private enum Station { CRAFTING_TABLE, STONECUTTER }

    /** 工作站从哪里来：背包装备（QuickShulker）或世界里记录好的坐标。 */
    private enum StationSource { INVENTORY, WORLD }

    /** 摆料方式：先试服务端的包，不行就自己点。 */
    private enum PlaceMode { PACKET, MANUAL }

    // 工作台菜单：0 = 产物，1..9 = 3×3 合成格。原版常量是 private 的，跨版本自己定义
    // （所有支持版本的菜单布局一致）。
    private static final int CRAFT_RESULT_SLOT = 0;
    private static final int CRAFT_GRID_START = 1;
    private static final int CRAFT_GRID_WIDTH = 3;
    private static final int CRAFT_GRID_SLOTS = 9;
    private static final int STONECUTTER_INPUT_SLOT = 0;
    private static final int STONECUTTER_RESULT_SLOT = 1;

    /** 发开盒包之后等工作站界面出现的 tick 数。 */
    private static final int OPEN_WAIT_TICKS = 20;
    /** 每次点击之间的间隔。 */
    private static final int ACTION_COOLDOWN = 4;
    /** 发摆料包之后最多等多少 tick 让服务端摆出产物；超了改手动摆。 */
    private static final int PLACE_PACKET_WAIT_TICKS = 12;
    /** 手动摆料 / 选完配方之后最多等多少 tick 让产物出现。 */
    private static final int RESULT_WAIT_TICKS = 20;
    /** 清空合成格最多等多少 tick（背包满时 quickMove 挪不动，不能无限重试）。 */
    private static final int GRID_CLEAR_WAIT_TICKS = 15;
    /** 一轮摆料取空之后，最多再重新摆几次。 */
    private static final int MAX_REPLACE_ATTEMPTS = 6;
    private static final int MAX_CRAFTS_PER_PLACEMENT = 64;
    private static final int RESULT_TAKE_COOLDOWN = 2;

    private final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();

    private enum Stage { IDLE, TRAVEL, OPEN, PREPARE, TAKE, CLOSE }

    private static final int TRAVEL_TIMEOUT_TICKS = 400;
    /** 距工作站多近才算到达（略大于交互距离，抵达后还要能右键到）。 */
    private static final double TRAVEL_REACH_PADDING = 1.0;

    private Stage stage = Stage.IDLE;
    private Result terminal = Result.ACTIVE;
    private boolean active;
    private int cooldown;
    private int waitTicks;
    private int travelTicks;

    private Item item;
    private int targetCount;
    private Item stonecutterInput;
    /** 本批允许投入的剩余数量，不能借用清单或祖先帧的预留。 */
    private int stonecutterInputBudget;
    private Station station = Station.CRAFTING_TABLE;
    /** 工作站的物品栏槽位（{@link StationSource#INVENTORY} 时有效）。 */
    private int stationSlot = -1;
    /** 工作站的世界坐标（{@link StationSource#WORLD} 时有效）。 */
    private BlockPos stationPos;
    /** 这一次实际选中的来源（背包或世界坐标），由选站流程写入。 */
    private StationSource source = StationSource.INVENTORY;
    /** 玩家配置的来源策略：自动 / 仅背包 / 仅世界坐标。 */
    private CraftStationSource mode = CraftStationSource.AUTO;
    /** 工作台或固定切石批次绑定的配方。 */
    private Plan recipe;
    private Plan plannedRecipe;
    private IntSupplier outputCount;
    private int lastOutputCount;
    private boolean openSent;
    private int replaceAttempts;
    private ExactPlacement exact;
    private AbstractContainerMenu exactMenu;
    private int exactStonecutterOption;
    private boolean exactTakeSent;

    // ---- 摆料状态 ----
    private PlaceMode placeMode = PlaceMode.PACKET;
    /** 摆料包已经发过 / 切石机配方已经选过。 */
    private boolean placeSent;
    private int placeTicks;
    /** 手动摆料前是否已经清空过格子。 */
    private boolean gridCleared;
    /** 这一轮手动摆料是否已经把材料摆进格子。 */
    private boolean manualPlaced;
    /** 服务端摆料包还值不值得试。一旦被证伪，本次合成就一直走手动摆料。 */
    private boolean packetUsable = true;

    public boolean isActive() { return this.active; }

    boolean start(Minecraft mc, WorkstationOrder order) {
        if (mc.player == null || mc.level == null || order.output() == null) return false;
        ExactPlacement placement = order.exact();
        if (placement != null && (order.layout().exactOutput() != order.output()
                || order.layout().outputCount() != placement.craft().recipe().yield())) return false;

        this.item = order.output();
        this.targetCount = order.target();
        this.stonecutterInput = order.stoneInput();
        this.stonecutterInputBudget = Math.max(0, order.inputBudget());
        this.plannedRecipe = order.layout();
        this.outputCount = order.outputCount();
        this.lastOutputCount = outputCount.getAsInt();
        this.exact = placement;
        this.exactMenu = null;
        this.exactStonecutterOption = -1;
        this.exactTakeSent = false;
        // 每次合成重新读一次配置：玩家可能在备货运行期间改了工作站来源。
        this.mode = (CraftStationSource) Configs.BaritoneSettings.CRAFT_STATION_SOURCE.getOptionListValue();
        this.source = StationSource.INVENTORY;
        this.stationPos = null;

        // 配方类型不支持的（熔炼 / 锻造）直接说做不了：调用方会保留已收集的子材料。
        if (order.recipeType() != null && !CraftingRecipes.isSupportedType(order.recipeType())) return false;

        if (!pickStation(mc, order.recipeType())) return false;
        if (placement != null) this.recipe = order.layout();

        this.stage = this.source == StationSource.WORLD && !canReachStation(mc)
                ? Stage.TRAVEL
                : Stage.OPEN;
        this.terminal = Result.ACTIVE;
        this.active = true;
        this.cooldown = 0;
        this.waitTicks = 0;
        this.travelTicks = 0;
        this.openSent = false;
        this.replaceAttempts = 0;
        // 已固定变种及批次数量时由手动摆料精确执行；原版摆料包会擅自取用清单预留量。
        this.packetUsable = plannedRecipe == null;
        resetPlacement();

        if (this.stage == Stage.TRAVEL) {
            // 世界坐标模式：先把玩家送到工作站旁边。寻路会打断 Baritone 的其它进程，
            // 但合成本来就是独占流程，取消挖矿/建造由调用方负责。
            BaritoneIntegration.getInstance().pathTo(this.stationPos);
        }
        return true;
    }

    /**
     * 中止并关掉工作站界面（停止备货、世界切换、背包满时调用）。
     *
     * <p>只关"我们打开的那个容器"：玩家自己的物品栏界面不是我们开的，关掉会像被抢了鼠标。
     */
    public void cancel(Minecraft mc) {
        if (mc.player != null && ownsOpenMenu(mc)) {
            mc.player.closeContainer();
        }
        // 世界坐标模式下是我们让 Baritone 走过去的，取消时要一并停下，否则玩家会继续被带着走。
        if (this.stage == Stage.TRAVEL || this.source == StationSource.WORLD) {
            BaritoneIntegration.getInstance().cancelPathing();
        }
        this.exactMenu = null;
        this.exact = null;
        this.active = false;
        this.stage = Stage.IDLE;
    }

    public Result tick(Minecraft mc) {
        if (!this.active || mc.player == null) return Result.DONE;
        if (this.cooldown > 0) {
            this.cooldown--;
            return Result.ACTIVE;
        }

        switch (this.stage) {
            case TRAVEL -> doTravel(mc);
            case OPEN -> doOpen(mc);
            case PREPARE -> doPrepare(mc);
            case TAKE -> doTake(mc);
            case CLOSE -> doClose(mc);
            default -> { }
        }

        if (this.stage == Stage.IDLE) {
            return this.terminal;
        }
        return Result.ACTIVE;
    }

    Result tickExact(Minecraft mc, boolean sessionCurrent) {
        // 已发出的批量取产物仍须核账，旧计划不再发送新的交互。
        if (this.active && !sessionCurrent && !this.exactTakeSent) {
            finish(Result.FAILED);
            return Result.FAILED;
        }
        return tick(mc);
    }

    // ---- 阶段实现 ----

    /**
     * 世界坐标模式：等 Baritone 走到工作站旁边。
     *
     * <p>到达判据用"方块外框到眼睛的距离"，与服务端接受右键的范围一致；
     * 寻路自己停了但没走到、或超时，都按失败处理，让调用方去收集原材料。
     */
    private void doTravel(Minecraft mc) {
        if (this.stationPos == null) {
            fail(mc, "no world workstation position");
            return;
        }
        if (canReachStation(mc)) {
            BaritoneIntegration.getInstance().cancelPathing();
            this.stage = Stage.OPEN;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }

        this.travelTicks++;
        boolean stopped = !BaritoneIntegration.getInstance().isPathing();
        if ((stopped && this.travelTicks > OPEN_WAIT_TICKS) || this.travelTicks > TRAVEL_TIMEOUT_TICKS) {
            BaritoneIntegration.getInstance().cancelPathing();
            fail(mc, "could not reach the world workstation at " + this.stationPos.toShortString());
            return;
        }
        this.cooldown = 1;
    }

    private void doOpen(Minecraft mc) {
        if (this.openSent) {
            // 包已经发出去了：这一支里**绝不能**再执行"关掉非物品栏菜单"的清理——
            // 服务端刚打开的工作站菜单正好符合那个条件，会被我们自己立刻关掉，
            // 现象就是"工作台界面闪一下就消失，然后报菜单从未出现"。
            this.waitTicks++;
            AbstractContainerMenu menu = stationMenu(mc);
            if (menu != null) {
                boolean expectedMenu = this.station == Station.STONECUTTER
                        ? menu instanceof StonecutterMenu : menu instanceof CraftingMenu;
                if (this.exact != null && !expectedMenu) {
                    fail(mc, "unexpected menu for exact crafting");
                    return;
                }
                if (this.exact != null) this.exactMenu = menu;
                this.stage = Stage.PREPARE;
                this.waitTicks = 0;
                return;
            }
            if (this.waitTicks > OPEN_WAIT_TICKS) {
                fail(mc, "workstation menu never appeared (menu="
                        + mc.player.containerMenu.getClass().getSimpleName()
                        + ", stationSlot=" + this.stationSlot + ")");
                return;
            }
            this.cooldown = 1;
            return;
        }

        // 还没发包：这时才需要先把别的容器关掉——QuickShulker 是按玩家自己的物品栏菜单
        // 解析槽位的，开着箱子时同一个索引指向完全不同的槽位。世界坐标路径同样要求先关界面，
        // 否则右键会落到已打开的容器上。
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            if (this.exact != null) {
                fail(mc, "another menu was open before exact crafting");
                return;
            }
            mc.player.closeContainer();
            this.cooldown = ACTION_COOLDOWN;
            return;
        }

        if (this.source == StationSource.WORLD) {
            openWorldStation(mc);
            return;
        }

        // 工作站可能在取料/存盒过程中被挪了位置，开之前重新确认一次它还在。
        Item stationItem = this.station == Station.STONECUTTER ? Items.STONECUTTER : Items.CRAFTING_TABLE;
        if (!ItemUtil.is(mc.player.getInventory().getItem(this.stationSlot), stationItem)) {
            int again = findInInventory(mc, stationItem);
            if (again < 0) {
                fail(mc, stationItem + " is gone");
                return;
            }
            this.stationSlot = again;
        }

        int menuSlot = PlayerUtil.menuSlotOf(mc.player.inventoryMenu, mc.player.getInventory(), this.stationSlot);
        if (menuSlot < 0 || !this.quickShulker.openShulkerBox(menuSlot)) {
            fail(mc, "QuickShulker refused to open the workstation (menuSlot=" + menuSlot + ")");
            return;
        }
        this.openSent = true;
        this.waitTicks = 0;
        this.cooldown = ACTION_COOLDOWN;
    }

    /** 世界坐标路径：对着工作站方块的最近面发右键。 */
    private void openWorldStation(Minecraft mc) {
        if (this.stationPos == null) {
            fail(mc, "no world workstation position");
            return;
        }
        if (!canReachStation(mc)) {
            fail(mc, "world workstation out of reach");
            return;
        }

        // 方块可能已经被拆掉或被换成别的东西：开之前确认还是同一种工作站。
        Block expectedBlock = this.station == Station.STONECUTTER
                ? Blocks.STONECUTTER
                : Blocks.CRAFTING_TABLE;
        if (!mc.level.getBlockState(this.stationPos).is(expectedBlock)) {
            fail(mc, "world workstation is gone or changed at " + this.stationPos.toShortString());
            return;
        }

        BlockInteraction.useNearestFace(mc, this.stationPos);
        this.openSent = true;
        this.waitTicks = 0;
        this.cooldown = ACTION_COOLDOWN;
    }

    private void doPrepare(Minecraft mc) {
        AbstractContainerMenu menu = stationMenu(mc);
        if (menu == null) {
            fail(mc, "workstation menu closed during preparation");
            return;
        }
        if (this.exact != null) {
            prepareExact(mc, menu);
            return;
        }
        if (this.station == Station.CRAFTING_TABLE) {
            prepareCrafting(mc, menu);
        } else {
            prepareStonecutter(mc, menu);
        }
    }

    /** 工作台：先让服务端摆料，不成就自己点。 */
    private void prepareCrafting(Minecraft mc, AbstractContainerMenu menu) {
        if (resultReady(menu, CRAFT_RESULT_SLOT)) {
            this.stage = Stage.TAKE;
            return;
        }

        if (this.placeMode == PlaceMode.PACKET) {
            if (!this.placeSent) {
                this.placeSent = true;
                this.placeTicks = 0;
                CraftingRecipes.place(mc, menu, this.recipe);
                this.cooldown = ACTION_COOLDOWN;
                return;
            }

            this.placeTicks++;
            if (resultReady(menu, CRAFT_RESULT_SLOT)) {
                this.stage = Stage.TAKE;
                return;
            }
            if (this.placeTicks > PLACE_PACKET_WAIT_TICKS) {
                // 摆料包没有生效（服务端认为配方未解锁、或判定材料不齐），只回了 ghost 配方。
                // 改自己点击摆料——这条路不依赖服务端的配方书。
                this.packetUsable = false;
                resetPlacement();
                this.cooldown = ACTION_COOLDOWN;
                return;
            }
            this.cooldown = 1;
            return;
        }

        manualPlace(mc, menu);
    }

    private void prepareExact(Minecraft mc, AbstractContainerMenu menu) {
        if (menu != this.exactMenu || !menu.getCarried().isEmpty() || !exact.recipeCurrent().getAsBoolean()) {
            fail(mc, "workstation, cursor or recipe changed during exact crafting");
            return;
        }
        if (this.station == Station.STONECUTTER) {
            prepareExactStonecutter(mc, (StonecutterMenu) menu);
            return;
        }
        if (!emptyCraftGrid(menu) || outputCount.getAsInt() != lastOutputCount
                || !exact.inputsAvailable().getAsBoolean()) {
            fail(mc, "unexpected crafting grid or insufficient unreserved inputs");
            return;
        }
        int operations = exact.craft().operations();
        if (!new CraftInventory(mc.player.getInventory()::getItem).canPlace(exact.craft(), operations)) {
            finish(Result.INVENTORY_FULL);
            return;
        }
        for (int index = 0; index < recipe.slotCount(); index++) {
            if (recipe.isEmptySlot(index)) continue;
            int slot = gridSlotFor(index);
            if (!fillGridSlot(mc, menu, slot, recipe.ingredientsAt(index), operations)) {
                fail(mc, "exact input missing for grid slot " + slot);
                return;
            }
        }
        this.placeTicks = 0;
        this.stage = Stage.TAKE;
        this.cooldown = ACTION_COOLDOWN;
    }

    private void prepareExactStonecutter(Minecraft mc, StonecutterMenu menu) {
        ItemStack input = menu.getSlot(STONECUTTER_INPUT_SLOT).getItem();
        ItemStack result = menu.getSlot(STONECUTTER_RESULT_SLOT).getItem();
        List<ItemStack> ingredient = recipe.ingredientsAt(0);
        int operations = exact.craft().operations();
        if (!this.manualPlaced) {
            if (!input.isEmpty() || !result.isEmpty() || outputCount.getAsInt() != lastOutputCount
                    || !exact.inputsAvailable().getAsBoolean()) {
                fail(mc, "unexpected stonecutter input or insufficient unreserved stock");
                return;
            }
            if (!new CraftInventory(mc.player.getInventory()::getItem).canPlace(exact.craft(), operations)) {
                finish(Result.INVENTORY_FULL);
                return;
            }
            // 先用一个输入确认服务端给出的选项和产量，确认前不放入该批余下的材料。
            if (!fillGridSlot(mc, menu, STONECUTTER_INPUT_SLOT, ingredient, 1)) {
                fail(mc, "exact stonecutter input missing");
                return;
            }
            this.manualPlaced = true;
            this.placeTicks = 0;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }
        int expectedAfterProbe = lastOutputCount - (this.stonecutterInput == item ? 1 : 0);
        if (!matchesExactInput(input, this.stonecutterInput, 1) || outputCount.getAsInt() != expectedAfterProbe) {
            fail(mc, "stonecutter probe input or inventory changed");
            return;
        }
        if (!this.placeSent) {
            int index = CraftingRecipes.exactStonecutterIndex(mc, menu, recipe);
            if (index >= 0) {
                if (!result.isEmpty() || menu.getSelectedRecipeIndex() >= 0) {
                    fail(mc, "stonecutter selection changed during probe");
                    return;
                }
                mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
                this.exactStonecutterOption = index;
                this.placeSent = true;
                this.placeTicks = 0;
                this.cooldown = ACTION_COOLDOWN;
                return;
            }
        } else if (!result.isEmpty() && menu.getSelectedRecipeIndex() >= 0) {
            if (!hasExactStonecutterSelection(menu) || !matchesExactInput(result, item, recipe.outputCount())
                    || !exact.reservesKept().getAsBoolean()) {
                fail(mc, "stonecutter selection, yield or inventory reservations changed");
                return;
            }
            if (!fillGridSlot(mc, menu, STONECUTTER_INPUT_SLOT, ingredient, operations - 1)
                    || !exact.reservesKept().getAsBoolean()) {
                fail(mc, "exact stonecutter inputs missing or reserved after probe");
                return;
            }
            this.placeTicks = 0;
            this.stage = Stage.TAKE;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }
        if (++this.placeTicks > RESULT_WAIT_TICKS) {
            fail(mc, "no matching exact stonecutter option or result");
            return;
        }
        this.cooldown = 1;
    }

    /**
     * 自己点击摆料（参考 EasierCrafting）：先清空格子，再按"这次能做几套"给每格精确分配材料，
     * 最后等服务端算出产物。
     *
     * <p>清空阶段每 tick 只移一格；精确摆料依赖同 tick 顺序点击的客户端预测。
     */
    private void manualPlace(Minecraft mc, AbstractContainerMenu menu) {
        this.placeTicks++;

        // 1) 清空合成格：上一次留下的零头会挡住摆放。
        if (!this.gridCleared) {
            if (this.placeTicks > GRID_CLEAR_WAIT_TICKS) {
                fail(mc, "cannot clear the crafting grid (inventory full?) grid=" + gridSummary(menu));
                return;
            }
            for (int i = 0; i < CRAFT_GRID_SLOTS; i++) {
                Slot slot = menu.getSlot(CRAFT_GRID_START + i);
                if (slot != null && slot.hasItem()) {
                    SlotActionCompat.quickMove(mc, menu.containerId, CRAFT_GRID_START + i);
                    this.cooldown = 1;
                    return;
                }
            }
            this.gridCleared = true;
            this.placeTicks = 0;
        }

        // 2) 一次性把所有合成格摆好。
        //
        // 关键在于"每格放几个"：材料数量要按这次能做多少套来分，不能把整堆都丢进第一格——
        // 那样后面的格子就没材料可摆了（EasierCrafting 的 getMaxCraftable 就是这个意思）。
        if (!this.manualPlaced) {
            if (this.recipe == null) {
                fail(mc, "no recipe plan");
                return;
            }
            int perSlot = craftsPerPlacement(mc);
            if (perSlot <= 0) {
                fail(mc, "not enough ingredients for a single craft (grid=" + gridSummary(menu) + ")");
                return;
            }
            for (int index = 0; index < this.recipe.slotCount(); index++) {
                if (this.recipe.isEmptySlot(index)) continue;   // shaped 图案的空位
                int gridSlot = gridSlotFor(index);
                if (!fillGridSlot(mc, menu, gridSlot, this.recipe.ingredientsAt(index), perSlot)) {
                    fail(mc, "ingredient missing for grid slot " + gridSlot
                            + " (grid=" + gridSummary(menu) + ")");
                    return;
                }
            }
            this.manualPlaced = true;
            this.placeTicks = 0;
            return;
        }

        // 3) 摆完了，等服务端算出产物。
        if (resultReady(menu, CRAFT_RESULT_SLOT)) {
            this.stage = Stage.TAKE;
            return;
        }
        if (this.placeTicks > RESULT_WAIT_TICKS) {
            fail(mc, "no result after manual placement (grid=" + gridSummary(menu) + ")");
            return;
        }
        this.cooldown = 1;
    }

    /** 切石机：把输入放进输入格，再按可见配方表选中目标产物的那一项。 */
    private void prepareStonecutter(Minecraft mc, AbstractContainerMenu menu) {
        if (resultReady(menu, STONECUTTER_RESULT_SLOT)) {
            int remainingOutput = this.targetCount - outputCount.getAsInt();
            int outputPerInput = menu.getSlot(STONECUTTER_RESULT_SLOT).getItem().getCount();
            int currentInput = menu.getSlot(STONECUTTER_INPUT_SLOT).getItem().getCount();
            int additional = stonecutterInputsToAdd(
                    remainingOutput, outputPerInput, currentInput, this.stonecutterInputBudget);
            if (additional > 0) {
                supplyStonecutterInput(mc, menu, additional);
            } else {
                this.stage = Stage.TAKE;
            }
            return;
        }

        Slot inputSlot = menu.getSlot(STONECUTTER_INPUT_SLOT);
        if (inputSlot == null || !inputSlot.hasItem()) {
            // 先用一个输入探明实际产量，产物出现后再补足本批，不能整堆快移消耗预留。
            supplyStonecutterInput(mc, menu, 1);
            this.placeTicks = 0;
            return;
        }

        if (!this.placeSent) {
            int index = CraftingRecipes.stonecutterIndex(mc, menu, this.item);
            if (index < 0) {
                // 可见配方表是客户端按输入物品算的，可能比输入格的内容晚一 tick 才出来。
                this.placeTicks++;
                if (this.placeTicks > RESULT_WAIT_TICKS) {
                    fail(mc, "stonecutter has no recipe for this item (visible="
                            + CraftingRecipes.visibleStonecutterRecipes(menu) + ")");
                    return;
                }
                this.cooldown = 1;
                return;
            }
            mc.gameMode.handleInventoryButtonClick(menu.containerId, index);
            this.placeSent = true;
            this.placeTicks = 0;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }

        this.placeTicks++;
        if (this.placeTicks > RESULT_WAIT_TICKS) {
            fail(mc, "no stonecutter result (input=" + !inputSlot.getItem().isEmpty() + ")");
            return;
        }
        this.cooldown = 1;
    }

    /** 实际产量可能大于一；输入总量还受本批已备材料约束。 */
    static int stonecutterInputsToAdd(int missingOutput, int outputPerInput, int currentInput, int budget) {
        int required = RecipeAllocation.craftsFor(missingOutput, outputPerInput);
        return Math.min(Math.max(0, budget), Math.max(0, required - currentInput));
    }

    private void supplyStonecutterInput(Minecraft mc, AbstractContainerMenu menu, int requested) {
        int inventorySlot = findInInventory(mc, this.stonecutterInput);
        int sourceSlot = inventorySlot < 0
                ? -1
                : PlayerUtil.menuSlotOf(menu, mc.player.getInventory(), inventorySlot);
        if (sourceSlot < 0 || this.stonecutterInputBudget <= 0) {
            fail(mc, "stonecutter input missing or batch budget exhausted (" + this.stonecutterInput + ")");
            return;
        }
        Slot input = menu.getSlot(STONECUTTER_INPUT_SLOT);
        ItemStack source = menu.getSlot(sourceSlot).getItem();
        int space = input.getMaxStackSize(source) - input.getItem().getCount();
        int amount = Math.min(Math.min(requested, this.stonecutterInputBudget), Math.min(space, source.getCount()));
        if (amount <= 0) {
            this.stage = Stage.TAKE;
            return;
        }
        int before = input.getItem().getCount();
        transfer(mc, menu, sourceSlot, STONECUTTER_INPUT_SLOT, amount);
        int moved = input.getItem().getCount() - before;
        if (moved <= 0) {
            fail(mc, "stonecutter rejected input transfer");
            return;
        }
        this.stonecutterInputBudget -= moved;
        this.cooldown = ACTION_COOLDOWN;
    }

    private void doTake(Minecraft mc) {
        AbstractContainerMenu menu = stationMenu(mc);
        if (menu == null) {
            fail(mc, "workstation menu closed before taking the result");
            return;
        }
        if (this.exact != null) {
            takeExact(mc, menu);
            return;
        }

        int produced = outputCount.getAsInt();
        if (produced > lastOutputCount) {
            lastOutputCount = produced;
            replaceAttempts = 0;
        }
        if (produced >= this.targetCount) {
            this.stage = Stage.CLOSE;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }
        int resultIndex = this.station == Station.CRAFTING_TABLE ? CRAFT_RESULT_SLOT : STONECUTTER_RESULT_SLOT;
        Slot resultSlot = menu.getSlot(resultIndex);
        if (resultSlot != null && resultSlot.hasItem()) {
            if (!ItemUtil.is(resultSlot.getItem(), item)) {
                fail(mc, "unexpected crafting result");
                return;
            }
            if (!hasOutputSpace(mc, resultSlot.getItem())) {
                finish(Result.INVENTORY_FULL);
                return;
            }
            SlotActionCompat.quickMove(mc, menu.containerId, resultIndex);
            this.cooldown = RESULT_TAKE_COOLDOWN;
            return;
        }

        // 格子取空了但还差数量：重新摆一次料；连续几次都没有进展就认输。
        this.replaceAttempts++;
        if (this.replaceAttempts > MAX_REPLACE_ATTEMPTS) {
            fail(mc, "crafting stalled after " + this.replaceAttempts + " placements");
            return;
        }
        resetPlacement();
        this.stage = Stage.PREPARE;
        this.cooldown = ACTION_COOLDOWN;
    }

    private void takeExact(Minecraft mc, AbstractContainerMenu menu) {
        if (menu != this.exactMenu || !menu.getCarried().isEmpty()) {
            fail(mc, "workstation or cursor changed during exact crafting");
            return;
        }
        if (this.exactTakeSent) {
            if (exactResultReceived(menu)) {
                this.stage = Stage.CLOSE;
                this.cooldown = ACTION_COOLDOWN;
            } else if (++this.placeTicks > RESULT_WAIT_TICKS) {
                fail(mc, "exact batch did not produce the planned quantity");
            }
            return;
        }

        Craft craft = exact.craft();
        int operations = craft.operations();
        int expectedAfterPlacement = lastOutputCount - craft.inputs().getOrDefault(item, 0) * operations;
        if (outputCount.getAsInt() != expectedAfterPlacement || !matchesExactGrid(menu, operations)) {
            fail(mc, "inventory or fixed grid changed before taking exact output");
            return;
        }
        int resultSlot = this.station == Station.STONECUTTER ? STONECUTTER_RESULT_SLOT : CRAFT_RESULT_SLOT;
        ItemStack result = menu.getSlot(resultSlot).getItem();
        if (result.isEmpty()) {
            if (++this.placeTicks > RESULT_WAIT_TICKS) fail(mc, "no exact crafting result");
            return;
        }
        if (!matchesExactInput(result, item, recipe.outputCount())
                || this.station == Station.STONECUTTER && !hasExactStonecutterSelection((StonecutterMenu) menu)) {
            fail(mc, "unexpected item, components or yield in exact crafting result");
            return;
        }
        if (!exact.recipeCurrent().getAsBoolean() || !exact.reservesKept().getAsBoolean()) {
            fail(mc, "recipe or inventory reservations changed before taking exact output");
            return;
        }
        if (!new CraftInventory(mc.player.getInventory()::getItem).canTake(craft, operations)) {
            finish(Result.INVENTORY_FULL);
            return;
        }
        // 原版快移会反复制作，由已核对的输入格数量限制次数。等待整批对账期间不重发。
        this.exactTakeSent = true;
        this.placeTicks = 0;
        SlotActionCompat.quickMove(mc, menu.containerId, resultSlot);
        this.cooldown = RESULT_TAKE_COOLDOWN;
    }

    private boolean exactResultReceived(AbstractContainerMenu menu) {
        int resultSlot = this.station == Station.STONECUTTER ? STONECUTTER_RESULT_SLOT : CRAFT_RESULT_SLOT;
        return outputCount.getAsInt() == this.targetCount && matchesExactGrid(menu, 0) && !menu.getSlot(resultSlot).hasItem();
    }

    private boolean matchesExactGrid(AbstractContainerMenu menu, int remaining) {
        if (this.station == Station.STONECUTTER) {
            return matchesExactInput(menu.getSlot(STONECUTTER_INPUT_SLOT).getItem(), this.stonecutterInput, remaining);
        }
        for (int index = 0; index < CRAFT_GRID_SLOTS; index++) {
            ItemStack actual = menu.getSlot(CRAFT_GRID_START + index).getItem();
            if (remaining == 0 || recipe.isEmptySlot(index)) {
                if (!actual.isEmpty()) return false;
            } else if (!matchesExactInput(actual, recipe.ingredientsAt(index).getFirst().getItem(), remaining)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasExactStonecutterSelection(StonecutterMenu menu) {
        return this.exactStonecutterOption >= 0 && menu.getSelectedRecipeIndex() == this.exactStonecutterOption;
    }

    static boolean matchesExactInput(ItemStack actual, Item expected, int count) {
        return count == 0 ? actual.isEmpty()
                : ItemUtil.isPlainLoose(actual) && ItemUtil.is(actual, expected) && actual.getCount() == count;
    }

    private static boolean emptyCraftGrid(AbstractContainerMenu menu) {
        for (int index = 0; index < CRAFT_GRID_SLOTS; index++) {
            if (menu.getSlot(CRAFT_GRID_START + index).hasItem()) return false;
        }
        return true;
    }

    private void doClose(Minecraft mc) {
        if (this.exact != null && (!ownsOpenMenu(mc) || !this.exactMenu.getCarried().isEmpty()
                || !exactResultReceived(this.exactMenu))) {
            fail(mc, "exact batch changed before closing the workstation");
            return;
        }
        if (ownsOpenMenu(mc)) {
            mc.player.closeContainer();
        }
        this.exactMenu = null;
        this.exact = null;
        finish(Result.DONE);
    }

    private boolean ownsOpenMenu(Minecraft mc) {
        if (this.exact != null) return mc.player.containerMenu == this.exactMenu;
        return mc.player.containerMenu != mc.player.inventoryMenu;
    }

    private void finish(Result result) {
        this.terminal = result;
        this.active = false;
        this.stage = Stage.IDLE;
    }

    private void fail(Minecraft mc, String reason) {
        Playercontrolpp.LOGGER.warn("Crafting failed: {} | item={} station={} grid={}",
                reason, this.item, this.station, gridSummary(stationMenu(mc)));
        finish(Result.FAILED);
    }

    /** 为一轮新的摆料复位。这一轮里摆料包已经被证伪的话，后续直接走手动。 */
    private void resetPlacement() {
        this.placeMode = this.packetUsable ? PlaceMode.PACKET : PlaceMode.MANUAL;
        this.placeSent = false;
        this.placeTicks = 0;
        this.gridCleared = false;
        this.manualPlaced = false;
    }

    // ---- 选站 / 配方 ----

    /**
     * 按已经备料的配方类型挑工作站；换工作站可能改变投入数量，不能临时替换配方。
     *
     * <p>每种工作站按配置决定从哪来：背包（QuickShulker）或世界坐标。自动模式下先背包、
     * 后世界坐标——装了 QuickShulker 的玩家不必为此多走路，没装的玩家也能靠标记的坐标工作。
     *
     * @return false = 当前配方需要的工作站不可用，或没有可用配方
     */
    private boolean pickStation(Minecraft mc, String recipeType) {
        boolean preferStonecutter = "stonecutting".equals(recipeType);
        Item preferred = preferStonecutter ? Items.STONECUTTER : Items.CRAFTING_TABLE;
        return tryStation(mc, preferred, preferStonecutter);
    }

    private boolean tryStation(Minecraft mc, Item stationItem, boolean stonecutter) {
        // 1) 背包里的工作站（需要 QuickShulker 才能就地打开）。
        if (this.mode != CraftStationSource.WORLD && tryInventoryStation(mc, stationItem, stonecutter)) return true;
        // 2) 世界坐标里记录的工作站（不装 QuickShulker 也能用）。
        if (this.mode != CraftStationSource.INVENTORY && tryWorldStation(mc, stationItem, stonecutter)) return true;
        return false;
    }

    private boolean tryInventoryStation(Minecraft mc, Item stationItem, boolean stonecutter) {
        int slot = findInInventory(mc, stationItem);
        if (slot < 0) return false;
        // 已装 QuickShulker 才可能就地打开手上的工作站。
        if (!this.quickShulker.isLoaded()) return false;

        if (stonecutter) {
            // 切石机的配方要等输入物品进了输入格才存在，这里只确认"有站 + 有输入材料"。
            if (this.stonecutterInput == null || findInInventory(mc, this.stonecutterInput) < 0) return false;
            this.station = Station.STONECUTTER;
            this.stationSlot = slot;
            this.stationPos = null;
            this.source = StationSource.INVENTORY;
            this.recipe = null;
            return true;
        }

        Plan plan = this.plannedRecipe != null ? this.plannedRecipe : CraftingRecipes.find(mc, this.item);
        if (plan == null) return false;
        this.station = Station.CRAFTING_TABLE;
        this.stationSlot = slot;
        this.stationPos = null;
        this.source = StationSource.INVENTORY;
        this.recipe = plan;
        return true;
    }

    /**
     * 世界坐标工作站：找玩家记录过、且当前维度里最近的那一个。
     *
     * <p>切石机同样要求背包里有输入材料——右键打开的只是工作站，原料还是要从身上摆过去。
     */
    private boolean tryWorldStation(Minecraft mc, Item stationItem, boolean stonecutter) {
        CraftStationManager.Kind kind = stonecutter
                ? CraftStationManager.Kind.STONECUTTER
                : CraftStationManager.Kind.CRAFTING_TABLE;
        BlockPos pos = CraftStationManager.getInstance().nearest(
                mc.level, mc.player.blockPosition(), kind);
        if (pos == null) return false;

        if (stonecutter) {
            if (this.stonecutterInput == null || findInInventory(mc, this.stonecutterInput) < 0) return false;
            this.station = Station.STONECUTTER;
            this.recipe = null;
        } else {
            Plan plan = this.plannedRecipe != null ? this.plannedRecipe : CraftingRecipes.find(mc, this.item);
            if (plan == null) return false;
            this.station = Station.CRAFTING_TABLE;
            this.recipe = plan;
        }
        this.stationSlot = -1;
        this.stationPos = pos;
        this.source = StationSource.WORLD;
        return true;
    }

    /** @return 玩家离工作站够不够近，可以直接右键。 */
    private boolean canReachStation(Minecraft mc) {
        if (this.stationPos == null || mc.player == null) return false;
        Vec3 eye = mc.player.getEyePosition();
        BlockPos pos = this.stationPos;
        double reach = PlayerUtil.blockReach(mc.player) + TRAVEL_REACH_PADDING;
        return BlockInteraction.distanceToBlockSq(eye, pos) <= reach * reach;
    }

    /** @return 物品栏里第一个 {@code wanted} 的槽位；没有则 -1。 */
    private static int findInInventory(Minecraft mc, Item wanted) {
        if (wanted == null || mc.player == null) return -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (ItemUtil.is(mc.player.getInventory().getItem(i), wanted)) return i;
        }
        return -1;
    }

    /**
     * @return 这一次摆料每格该放几个，也就是"按现有材料最多能做几套"，同时不超过目标数量与 64。
     *         0 = 材料连一套都不够。
     */
    private int craftsPerPlacement(Minecraft mc) {
        Map<Item, Integer> perCraft = new LinkedHashMap<>();
        for (List<ItemStack> candidates : this.recipe.ingredients()) {
            if (candidates.isEmpty()) continue;   // 图案空位
            Item chosen = null;
            for (ItemStack candidate : candidates) {
                if (ItemUtil.countLoose(mc.player, candidate.getItem()) > 0) {
                    chosen = candidate.getItem();
                    break;
                }
            }
            if (chosen == null) return 0;         // 有一种材料一个都没有
            perCraft.merge(chosen, 1, Integer::sum);
        }
        if (perCraft.isEmpty()) return 0;

        int crafts = MAX_CRAFTS_PER_PLACEMENT;
        for (Map.Entry<Item, Integer> entry : perCraft.entrySet()) {
            crafts = Math.min(crafts, entry.getKey().getDefaultMaxStackSize());
            crafts = Math.min(crafts, ItemUtil.countLoose(mc.player, entry.getKey()) / entry.getValue());
        }
        int remaining = this.targetCount - outputCount.getAsInt();
        return Math.max(0, Math.min(crafts, RecipeAllocation.craftsFor(remaining, recipe.outputCount())));
    }

    /** 往一个合成格里凑够 {@code amount} 个材料（不够就从下一个物品栏槽位接着拿）。 */
    private boolean fillGridSlot(Minecraft mc, AbstractContainerMenu menu, int gridSlot,
                                 List<ItemStack> candidates, int amount) {
        int remaining = amount;
        for (int i = 0; i < Inventory.INVENTORY_SIZE && remaining > 0; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !matchesAny(stack, candidates)) continue;
            if (this.exact != null && !ItemUtil.isPlainLoose(stack)) continue;
            int menuSlot = PlayerUtil.menuSlotOf(menu, mc.player.getInventory(), i);
            if (menuSlot < 0) continue;
            int take = Math.min(remaining, stack.getCount());
            transfer(mc, menu, menuSlot, gridSlot, take);
            remaining -= take;
        }
        return remaining <= 0;
    }

    /** @return 这一堆物品是不是 {@code candidates} 里的任意一种（配方材料常写成标签）。 */
    private static boolean matchesAny(ItemStack stack, List<ItemStack> candidates) {
        for (ItemStack candidate : candidates) {
            if (ItemUtil.is(stack, candidate.getItem())) return true;
        }
        return false;
    }

    /**
     * 从 {@code from} 往 {@code to} 精确搬 {@code amount} 个（参考 EasierCrafting 的 transfer）。
     *
     * <p>整堆够就一次"拿起→放下"；不够整堆的用"拿起整堆 → 右键逐个放 → 剩下的放回原槽"。
     * 这些点击在同一 tick 内按顺序发出，客户端的预测会让后续几步读到已经变化的槽位。
     */
    private void transfer(Minecraft mc, AbstractContainerMenu menu, int from, int to, int amount) {
        ItemStack fromContent = menu.getSlot(from).getItem();
        if (fromContent.isEmpty() || amount <= 0) return;

        if (amount >= fromContent.getCount()) {
            SlotActionCompat.pickup(mc, menu.containerId, from);
            SlotActionCompat.pickup(mc, menu.containerId, to);
            this.cooldown = 1;
            return;
        }

        SlotActionCompat.pickup(mc, menu.containerId, from);                 // 拿起整堆
        for (int i = 0; i < amount; i++) {
            SlotActionCompat.pickupRight(mc, menu.containerId, to);          // 右键放 1 个
        }
        SlotActionCompat.pickup(mc, menu.containerId, from);                 // 剩下的放回去
        this.cooldown = 1;
    }

    /** 图案里第 {@code index} 项对应的合成格。shaped 按图案宽度整行排，shapeless 顺着铺。 */
    private int gridSlotFor(int index) {
        int width = this.recipe == null ? CRAFT_GRID_WIDTH : this.recipe.width();
        int slot = (index / width) * CRAFT_GRID_WIDTH + (index % width);
        return CRAFT_GRID_START + Math.min(Math.max(slot, 0), CRAFT_GRID_SLOTS - 1);
    }

    private static AbstractContainerMenu stationMenu(Minecraft mc) {
        AbstractContainerMenu menu = mc.player == null ? null : mc.player.containerMenu;
        if (menu instanceof CraftingMenu || menu instanceof StonecutterMenu) return menu;
        return null;
    }

    private boolean resultReady(AbstractContainerMenu menu, int slotIndex) {
        Slot slot = menu.getSlot(slotIndex);
        return slot != null && slot.hasItem() && ItemUtil.is(slot.getItem(), item);
    }

    private static boolean hasOutputSpace(Minecraft mc, ItemStack output) {
        int remaining = output.getCount();
        for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
            ItemStack stack = mc.player.getInventory().getItem(index);
            if (stack.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(stack, output)) remaining -= stack.getMaxStackSize() - stack.getCount();
            if (remaining <= 0) return true;
        }
        return false;
    }

    BlockPos targetPosition() { return active ? stationPos : null; }

    /** 只用于日志：把合成格里有什么打出来，排查"摆不进去"时一眼能看懂。 */
    private static String gridSummary(AbstractContainerMenu menu) {
        if (menu == null) return "no-menu";
        StringBuilder sb = new StringBuilder("[");
        int start = menu instanceof StonecutterMenu ? STONECUTTER_INPUT_SLOT : CRAFT_GRID_START;
        int end = menu instanceof StonecutterMenu ? STONECUTTER_RESULT_SLOT : CRAFT_GRID_START + CRAFT_GRID_SLOTS;
        for (int i = start; i < end; i++) {
            Slot slot = menu.getSlot(i);
            if (slot == null || !slot.hasItem()) continue;
            if (sb.length() > 1) sb.append(", ");
            sb.append(slot.getItem().getCount()).append('x').append(slot.getItem().getItem());
        }
        return sb.append(']').toString();
    }
}
