package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.SlotActionCompat;
import com.alonediamond.playercontrolpp.integration.QuickShulkerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

//#if MC >= 12102
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.SelectableRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
//#endif

/**
 * 用 QuickShulker 从背包里就地打开工作台 / 切石机，摆料、取产物、关界面。
 *
 * <p>QuickShulker 的 {@code OpenShulkerPacket} 对工作台、切石机同样有效
 * （它自己的 {@code quickCraftingTables} / {@code quickStonecutter} 开关控制），
 * 而且这两种方块没有被 {@code ignoreSingleStackCheck} 限制，堆叠放着也能开。
 *
 * <h3>两条摆料路径</h3>
 * <ol>
 *   <li><b>先试服务端的摆料包</b>（{@code handlePlaceRecipe}）：一次包让服务端按配方把材料
 *       从物品栏摆进格子，最省事。ItemScroller 用的就是这条路。</li>
 *   <li><b>摆料包没生效就自己点</b>：服务端会因为"配方没在服务端解锁 / 判定材料不齐"
 *       只回一个 ghost 配方，格子始终是空的。这时照 EasierCrafting 的做法，
 *       自己把每个合成格需要的材料从物品栏 shift/点击搬进格子——这条路不依赖服务端的配方书。</li>
 * </ol>
 *
 * <p>摆料是否成功一律以<b>产物槽里出现东西</b>为准（格子有没有料只是中间状态）。
 * 失败原因会打一条 {@code [craft]} 日志，方便实机排查。
 *
 * <p>本类只负责"打开→摆料→取产物→关闭"这一小段，生命周期由 {@code RawMaterialTask} 驱动。
 */
public class CraftingController {

    public enum Result {
        ACTIVE, DONE,
        /** 摆料/取产物失败（配方对不上、格子摆不进去……）。 */
        FAILED,
        /** 背包满了，产物拿不出来。 */
        INVENTORY_FULL
    }

//#if MC >= 12102

    /** 用哪种工作站。 */
    private enum Station { CRAFTING_TABLE, STONECUTTER }

    /** 摆料方式：先试服务端的包，不行就自己点。 */
    private enum PlaceMode { PACKET, MANUAL }

    // 工作台菜单：0 = 产物，1..9 = 3×3 合成格。
    // 原版这几个常量是 private 的，跨版本自己定义（理由同 ItemTransferStrategy.SHULKER_SLOT_COUNT）。
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

    private final QuickShulkerIntegration quickShulker = QuickShulkerIntegration.getInstance();

    private enum Stage { IDLE, OPEN, PREPARE, TAKE, CLOSE }

    private Stage stage = Stage.IDLE;
    private Result terminal = Result.ACTIVE;
    private boolean active;
    private int cooldown;
    private int waitTicks;

    private Item item;
    private int targetCount;
    private Item stonecutterInput;
    private Station station = Station.CRAFTING_TABLE;
    /** 工作站的物品栏槽位。 */
    private int stationSlot = -1;
    private RecipeDisplayEntry craftingRecipe;
    private ContextMap contextMap;
    private boolean openSent;
    private int replaceAttempts;

    // ---- 摆料状态 ----
    private PlaceMode placeMode = PlaceMode.PACKET;
    /** 摆料包已经发过 / 切石机配方已经选过。 */
    private boolean placeSent;
    private int placeTicks;
    /** 手动摆料前是否已经清空过格子。 */
    private boolean gridCleared;
    /** 这一轮手动摆料是否已经把材料摆进格子。 */
    private boolean manualPlaced;
    /** 手动摆料用的图案（shaped 含空位占位，shapeless 就是材料表）。 */
    private List<SlotDisplay> placeDisplays = List.of();
    /** 图案宽度：shaped 用它的宽度，shapeless 按一行三个铺开。 */
    private int placeWidth = CRAFT_GRID_WIDTH;
    /** 服务端摆料包还值不值得试。一旦被证伪，本次合成就一直走手动摆料。 */
    private boolean packetUsable = true;

    public boolean isActive() { return this.active; }

    /**
     * 开始一次合成。
     *
     * @param item             要合成的物品
     * @param targetCount      要持有到多少个（绝对量，含缺失清单给它的预留量）
     * @param recipeType      原材料树上这一层的配方类型
     * @param stonecutterInput 切石机要用的输入物品（树上唯一的那个子材料）；没有就传 {@code null}
     * @return false = 这次合成做不了（没有工作站 / 没有可用配方 / 配方类型不支持），调用方按"只收集原材料"处理
     */
    public boolean start(Minecraft mc, Item item, int targetCount, String recipeType, Item stonecutterInput) {
        if (mc.player == null || mc.level == null || item == null) return false;

        this.item = item;
        this.targetCount = targetCount;
        this.stonecutterInput = stonecutterInput;
        this.contextMap = SlotDisplayContext.fromLevel(mc.level);

        // 配方类型不支持的（熔炼 / 锻造）直接说做不了：调用方会保留已收集的子材料。
        if (recipeType != null && !isSupportedRecipe(recipeType)) return false;

        if (!pickStation(mc, recipeType)) return false;

        this.stage = Stage.OPEN;
        this.terminal = Result.ACTIVE;
        this.active = true;
        this.cooldown = 0;
        this.waitTicks = 0;
        this.openSent = false;
        this.replaceAttempts = 0;
        this.packetUsable = true;
        resetPlacement();
        return true;
    }

    /**
     * 中止并关掉工作站界面（停止备货、世界切换、背包满时调用）。
     *
     * <p>只关"我们打开的那个容器"：玩家自己的物品栏界面不是我们开的，关掉会像被抢了鼠标。
     */
    public void cancel(Minecraft mc) {
        if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        this.active = false;
        this.stage = Stage.IDLE;
    }

    public Result tick(Minecraft mc) {
        if (!this.active || mc.player == null) return Result.DONE;
        if (this.cooldown > 0) { this.cooldown--; return Result.ACTIVE; }

        switch (this.stage) {
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

    // ---- 阶段实现 ----

    private void doOpen(Minecraft mc) {
        if (this.openSent) {
            // 包已经发出去了：这一支里**绝不能**再执行"关掉非物品栏菜单"的清理——
            // 服务端刚打开的工作站菜单正好符合那个条件，会被我们自己立刻关掉，
            // 现象就是"工作台界面闪一下就消失，然后报菜单从未出现"。
            this.waitTicks++;
            if (stationMenu(mc) != null) {
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
        // 解析槽位的，开着箱子时同一个索引指向完全不同的槽位。
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
            this.cooldown = ACTION_COOLDOWN;
            return;
        }

        // 工作站可能在取料/存盒过程中被挪了位置，开之前重新确认一次它还在。
        Item stationItem = this.station == Station.STONECUTTER ? Items.STONECUTTER : Items.CRAFTING_TABLE;
        if (!ItemUtil.is(mc.player.getInventory().getItem(this.stationSlot), stationItem)) {
            int again = findInInventory(mc, stationItem);
            if (again < 0) { fail(mc, stationItem + " is gone"); return; }
            this.stationSlot = again;
        }

        int menuSlot = PlayerUtil.menuSlotOf(mc.player.inventoryMenu, mc.player.getInventory(), this.stationSlot);
        if (menuSlot < 0 || !this.quickShulker.openShulkerBox(menuSlot)) {
            fail(mc, "QuickShulker refused to open the workstation (menuSlot=" + menuSlot + ")");
            return;
        }
        log(mc, "opened " + this.station + " for " + this.item + " (inventory slot " + this.stationSlot
                + " -> menu slot " + menuSlot + ")");
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
                mc.gameMode.handlePlaceRecipe(menu.containerId, this.craftingRecipe.id(), true);
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
                log(mc, "place packet had no effect, switching to manual placement");
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

    /**
     * 自己点击摆料（参考 EasierCrafting）：先清空格子，再一格一格把材料搬进去，最后等服务端算出产物。
     *
     * <p>一 tick 只做一次点击，让每次槽位变更都有时间同步回来；这样即使中途被打断，
     * 也不会在客户端和服务端之间留下"手上一半物品"的中间态。
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
            int perSlot = craftsPerPlacement(mc);
            if (perSlot <= 0) {
                fail(mc, "not enough ingredients for a single craft (grid=" + gridSummary(menu) + ")");
                return;
            }
            for (int index = 0; index < this.placeDisplays.size(); index++) {
                SlotDisplay display = this.placeDisplays.get(index);
                if (display.resolveForStacks(this.contextMap).isEmpty()) continue;   // shaped 图案的空位
                if (!fillGridSlot(mc, menu, gridSlotFor(index), display, perSlot)) {
                    fail(mc, "ingredient missing for grid slot " + gridSlotFor(index)
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
            this.stage = Stage.TAKE;
            return;
        }

        Slot inputSlot = menu.getSlot(STONECUTTER_INPUT_SLOT);
        if (inputSlot == null || !inputSlot.hasItem()) {
            int inventorySlot = findInInventory(mc, this.stonecutterInput);
            int menuSlot = inventorySlot < 0
                    ? -1
                    : PlayerUtil.menuSlotOf(menu, mc.player.getInventory(), inventorySlot);
            if (menuSlot < 0) {
                fail(mc, "stonecutter input missing (" + this.stonecutterInput + ")");
                return;
            }
            SlotActionCompat.quickMove(mc, menu.containerId, menuSlot);
            this.placeTicks = 0;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }

        if (!this.placeSent) {
            int index = findStonecutterIndex(menu, this.item);
            if (index < 0) {
                // 可见配方表是客户端按输入物品算的，可能比输入格的内容晚一 tick 才出来。
                this.placeTicks++;
                if (this.placeTicks > RESULT_WAIT_TICKS) {
                    fail(mc, "stonecutter has no recipe for this item (visible="
                            + visibleStonecutterRecipes(menu) + ")");
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
        if (resultReady(menu, STONECUTTER_RESULT_SLOT)) {
            this.stage = Stage.TAKE;
            return;
        }
        if (this.placeTicks > RESULT_WAIT_TICKS) {
            fail(mc, "no stonecutter result (input=" + !inputSlot.getItem().isEmpty() + ")");
            return;
        }
        this.cooldown = 1;
    }

    /**
     * @return 这一次摆料每格该放几个，也就是"按现有材料最多能做几套"，同时不超过目标数量与 64。
     *         0 = 材料连一套都不够。
     */
    private int craftsPerPlacement(Minecraft mc) {
        java.util.Map<Item, Integer> perCraft = new java.util.LinkedHashMap<>();
        for (SlotDisplay display : this.placeDisplays) {
            List<ItemStack> candidates = display.resolveForStacks(this.contextMap);
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

        int crafts = 64;
        for (java.util.Map.Entry<Item, Integer> entry : perCraft.entrySet()) {
            crafts = Math.min(crafts, ItemUtil.countLoose(mc.player, entry.getKey()) / entry.getValue());
        }
        int remaining = this.targetCount - ItemUtil.countEverywhere(mc.player, this.item);
        return Math.max(0, Math.min(crafts, remaining));
    }

    /** 往一个合成格里凑够 {@code amount} 个材料（不够就从下一个物品栏槽位接着拿）。 */
    private boolean fillGridSlot(Minecraft mc, AbstractContainerMenu menu, int gridSlot,
                                 SlotDisplay display, int amount) {
        List<ItemStack> candidates = display.resolveForStacks(this.contextMap);
        int remaining = amount;
        for (int i = 0; i < Inventory.INVENTORY_SIZE && remaining > 0; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack.isEmpty() || !matchesAny(stack, candidates)) continue;
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

    private void doTake(Minecraft mc) {
        AbstractContainerMenu menu = stationMenu(mc);
        if (menu == null) {
            fail(mc, "workstation menu closed before taking the result");
            return;
        }

        if (ItemUtil.countEverywhere(mc.player, this.item) >= this.targetCount) {
            this.stage = Stage.CLOSE;
            this.cooldown = ACTION_COOLDOWN;
            return;
        }
        // 产物拿不出来就别再合成更多了：先把界面关掉，让主状态机去腾空间。
        if (PlayerUtil.isInventoryFull(mc.player)) {
            finish(Result.INVENTORY_FULL);
            return;
        }

        int resultIndex = this.station == Station.CRAFTING_TABLE ? CRAFT_RESULT_SLOT : STONECUTTER_RESULT_SLOT;
        Slot resultSlot = menu.getSlot(resultIndex);
        if (resultSlot != null && resultSlot.hasItem()) {
            SlotActionCompat.quickMove(mc, menu.containerId, resultIndex);
            this.cooldown = 2;
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

    private void doClose(Minecraft mc) {
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
        finish(Result.DONE);
    }

    private void finish(Result result) {
        this.terminal = result;
        this.active = false;
        this.stage = Stage.IDLE;
    }

    private void fail(Minecraft mc, String reason) {
        Playercontrolpp.LOGGER.info("[craft] FAILED: {} | item={} station={} grid={}",
                reason, this.item, this.station, gridSummary(stationMenu(mc)));
        finish(Result.FAILED);
    }

    private static void log(Minecraft mc, String message) {
        Playercontrolpp.LOGGER.info("[craft] {}", message);
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
     * 按树给出的配方类型挑工作站，挑不到就回退到另一种（作者确认允许回退）。
     *
     * @return false = 两种工作站都没得用，或没有能用的配方
     */
    private boolean pickStation(Minecraft mc, String recipeType) {
        boolean preferStonecutter = "stonecutting".equals(recipeType);
        Item preferred = preferStonecutter ? Items.STONECUTTER : Items.CRAFTING_TABLE;
        Item fallback = preferStonecutter ? Items.CRAFTING_TABLE : Items.STONECUTTER;

        if (tryStation(mc, preferred, preferStonecutter)) return true;
        return tryStation(mc, fallback, !preferStonecutter);
    }

    private boolean tryStation(Minecraft mc, Item stationItem, boolean stonecutter) {
        int slot = findInInventory(mc, stationItem);
        if (slot < 0) return false;

        if (stonecutter) {
            // 切石机的配方要等输入物品进了输入格才存在，这里只确认"有站 + 有输入材料"。
            if (this.stonecutterInput == null || findInInventory(mc, this.stonecutterInput) < 0) return false;
            this.station = Station.STONECUTTER;
            this.stationSlot = slot;
            return true;
        }

        RecipeDisplayEntry recipe = findCraftingRecipe(mc, this.item);
        if (recipe == null) return false;
        this.station = Station.CRAFTING_TABLE;
        this.stationSlot = slot;
        this.craftingRecipe = recipe;
        this.placeDisplays = craftingLayout(recipe);
        this.placeWidth = craftingWidth(recipe);
        return true;
    }

    /** @return 物品栏里第一个 {@code wanted} 的槽位；没有则 -1。 */
    private static int findInInventory(Minecraft mc, Item wanted) {
        if (wanted == null || mc.player == null) return -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (ItemUtil.is(mc.player.getInventory().getItem(i), wanted)) return i;
        }
        return -1;
    }

    /** 图案里第 {@code index} 项对应的合成格。shaped 按图案宽度整行排，shapeless 顺着铺。 */
    private int gridSlotFor(int index) {
        int width = Math.max(1, this.placeWidth);
        int slot = (index / width) * CRAFT_GRID_WIDTH + (index % width);
        return CRAFT_GRID_START + Math.min(Math.max(slot, 0), CRAFT_GRID_SLOTS - 1);
    }

    private static List<SlotDisplay> craftingLayout(RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return shaped.ingredients();
        if (display instanceof ShapelessCraftingRecipeDisplay shapeless) return shapeless.ingredients();
        return List.of();
    }

    private static int craftingWidth(RecipeDisplayEntry entry) {
        RecipeDisplay display = entry.display();
        if (display instanceof ShapedCraftingRecipeDisplay shaped) return Math.max(1, shaped.width());
        // shapeless 没有图案，位置不影响配方匹配，顺着 3 个一行铺开即可。
        return CRAFT_GRID_WIDTH;
    }

    /**
     * 在客户端配方书里找"能在工作台做出 {@code item}"的那一条。
     *
     * <p>只认 shaped / shapeless，避免把熔炉、切石机的展示条目当成工作台配方。
     */
    private RecipeDisplayEntry findCraftingRecipe(Minecraft mc, Item item) {
        try {
            for (RecipeCollection collection : mc.player.getRecipeBook().getCollections()) {
                for (RecipeDisplayEntry entry : collection.getRecipes()) {
                    if (entry.craftingRequirements().isEmpty()) continue;
                    RecipeDisplay display = entry.display();
                    if (!(display instanceof ShapedCraftingRecipeDisplay)
                            && !(display instanceof ShapelessCraftingRecipeDisplay)) {
                        continue;
                    }
                    List<ItemStack> results = entry.resultItems(this.contextMap);
                    if (results.isEmpty()) continue;
                    if (ItemUtil.is(results.get(0), item)) return entry;
                }
            }
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to look up a crafting recipe for {}", item, e);
        }
        return null;
    }

    /** @return 切石机菜单里产出 {@code item} 的那一项的按钮索引；没有则 -1。 */
    private int findStonecutterIndex(AbstractContainerMenu menu, Item item) {
        if (!(menu instanceof StonecutterMenu stonecutter)) return -1;
        List<SelectableRecipe.SingleInputEntry<net.minecraft.world.item.crafting.StonecutterRecipe>> entries =
                stonecutter.getVisibleRecipes().entries();
        for (int i = 0; i < entries.size(); i++) {
            ItemStack option = entries.get(i).recipe().optionDisplay().resolveForFirstStack(this.contextMap);
            if (ItemUtil.is(option, item)) return i;
        }
        return -1;
    }

    private static int visibleStonecutterRecipes(AbstractContainerMenu menu) {
        return menu instanceof StonecutterMenu stonecutter ? stonecutter.getNumberOfVisibleRecipes() : -1;
    }

    private static boolean isSupportedRecipe(String recipeType) {
        return "crafting_shaped".equals(recipeType)
                || "crafting_shapeless".equals(recipeType)
                || "stonecutting".equals(recipeType);
    }

    private static AbstractContainerMenu stationMenu(Minecraft mc) {
        AbstractContainerMenu menu = mc.player == null ? null : mc.player.containerMenu;
        if (menu instanceof CraftingMenu || menu instanceof StonecutterMenu) return menu;
        return null;
    }

    private static boolean resultReady(AbstractContainerMenu menu, int slotIndex) {
        Slot slot = menu.getSlot(slotIndex);
        return slot != null && slot.hasItem();
    }

    /** 只用于日志：把合成格里有什么打出来，排查"摆不进去"时一眼能看懂。 */
    private static String gridSummary(AbstractContainerMenu menu) {
        if (menu == null) return "no-menu";
        StringBuilder sb = new StringBuilder("[");
        for (int i = CRAFT_GRID_START; i < CRAFT_GRID_START + CRAFT_GRID_SLOTS; i++) {
            Slot slot = menu.getSlot(i);
            if (slot == null || !slot.hasItem()) continue;
            if (sb.length() > 1) sb.append(", ");
            sb.append(slot.getItem().getCount()).append('x').append(slot.getItem().getItem());
        }
        return sb.append(']').toString();
    }

//#else
//$$    // 1.21.1 没有配方展示 API（RecipeDisplay / ContextMap 那一套），而 LitematList 也没有
//$$    // 该版本的构建，这个功能在那个版本上不可能被启用。这里只保留签名，让上层代码照常编译。
//$$    public boolean isActive() { return false; }
//$$
//$$    public boolean start(Minecraft mc, Item item, int targetCount, String recipeType, Item stonecutterInput) {
//$$        return false;
//$$    }
//$$
//$$    public void cancel(Minecraft mc) { }
//$$
//$$    public Result tick(Minecraft mc) { return Result.DONE; }
//#endif
}
