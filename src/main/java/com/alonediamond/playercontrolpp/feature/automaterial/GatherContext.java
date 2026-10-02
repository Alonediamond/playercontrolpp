package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次自动备货运行的共享可变状态，传给本包内每个模块。
 *
 * <p>字段刻意是 public：它是从一个 1100 行的大类里拆出来的，读写它们的模块全在本包内。
 * 请把它当成那个类的字段块，而不是一套 API。
 */
public class GatherContext {

    public AutoMaterialGatherer.State state = AutoMaterialGatherer.State.IDLE;
    public boolean active;
    public Minecraft client;

    // 材料清单数据
    public final List<MaterialItemEntry> missingItems = new ArrayList<>();
    public int currentItemIndex;
    public Item currentTargetItem;
    public int targetNeededTotal;
    public int currentlyGathered;

    // 箱子搜索数据
    public final List<BlockPos> foundPositions = new ArrayList<>();
    public int currentPosIndex;
    public int chestRetryCount;

    /**
     * 对当前物品已确认「拿不到东西」的容器坐标（缓存失效、已被搬空、整盒额度用尽等）。
     * search() 重建候选列表时跳过它们，否则失效缓存会让开箱-关箱-再搜索的循环永不前进。
     * 换物品时清空：对上一件物品空手的容器可能装着下一件物品。
     */
    public final Set<BlockPos> exhaustedPositions = new HashSet<>();

    /**
     * 整盒优先：最近一次搜索发现缺口超过阈值、且箱子追踪缓存里有装着当前所需材料的整盒。
     * 为 true 时转移阶段先搬整盒再拿散装。每次 search() 重新判定。
     */
    public boolean wholeBoxPriority;

    /**
     * 杂盒模式：缺口没超过整盒阈值、散装容器一个都没搜到，但缓存里有"装着当前所需材料"的潜影盒。
     * 为 true 时转移阶段改为把盒子取出来、开盒取走盒内所有缺失材料、再把盒子还回原容器。
     */
    public boolean mixedBoxMode;

    /**
     * 待归还的杂盒所在物品栏槽位；&lt; 0 表示没有待归还的盒子。
     * 由 {@code ShulkerBoxAccess} 在取物结束时写入，{@code ContainerOpener} 在容器打开后消费。
     */
    public int mixedBoxReturnSlot = -1;

    /** 待归还的杂盒原来所在的容器坐标；与 {@link #mixedBoxReturnSlot} 配对使用。 */
    public BlockPos mixedBoxReturnTarget;

    /**
     * 已经把盒子 quickMove 回容器、正等关闭界面的那一拍。
     *
     * <p>点击和关闭是两个包，同 tick 连发有被服务端按"界面已关"处理掉的风险；
     * 由 {@code TaskStateMachine} 在冷却结束后统一关界面。
     */
    public boolean mixedBoxReturnClosing;

    /**
     * 六邻回退的"原目标"。
     *
     * <p>{@link #currentContainerTarget} 会被每一个试过的邻居覆盖，所以全部邻居都试完之后
     * 要记进排除表的是这份原目标——记错成最后一个邻居，原容器会被下一次搜索重新选中，
     * 于是"开箱→无货→六邻→再开箱"永不前进。
     */
    public BlockPos adjacentOriginTarget;

    /**
     * 本次运行里从容器搬出来开过盒的潜影盒槽位。
     *
     * <p>自动存盒要跳过它们，否则刚取出来的杂盒会被当成存储盒，材料绕一圈又回到盒子里。
     * 盒子归还或离开物品栏后由主状态机摘掉。
     */
    public final Set<Integer> extractionBoxSlots = new HashSet<>();

    /**
     * 原材料追溯这一轮碰过的物品（树上的节点、派出去采集/挖矿的目标）。
     *
     * <p>它们大多不在缺失清单上（木板、原木这些中间产物），但合成失败、材料留在背包里时，
     * 自动存盒也应该认它们——否则这些"白收集"的材料只会一直占着背包格子。
     */
    public final Set<Item> extraStorableItems = new HashSet<>();

    /**
     * 已经追溯过原材料、但最终没能凑齐的物品。同一个物品不重复追溯，否则
     * "搜不到 → 追溯 → 仍不够 → 再搜 → 再追溯"会变成死循环。
     */
    public final Set<Item> tracedItems = new HashSet<>();

    // Baritone 寻路追踪
    public Vec3 lastPlayerPos = Vec3.ZERO;
    public int stuckTicks;
    public int pathingTicks;
    public boolean pathingWasActive;
    public BlockPos currentPathTarget;

    // Container interaction
    public int transferCooldown;
    public boolean containerJustOpened;
    public int openAttemptCount;
    public BlockPos currentContainerTarget;
    public List<BlockPos> adjacentContainerTargets;
    public int adjacentTryIndex;

    /**
     * 上一次成功转移的是否是一整个潜影盒。如果是，紧接着背包满了也不能触发自动存盒——
     * 那会把刚拿的盒子原样存回去。
     */
    public boolean justTookShulkerBox;

    /**
     * @return 当前正在收集的物品；清单走完后返回 {@code null}。
     *
     * <p>把 {@code currentItemIndex} 的边界检查收在一处，而不是每个调用点各写一遍。
     */
    public MaterialItemEntry currentItem() {
        return currentItemIndex >= 0 && currentItemIndex < missingItems.size()
                ? missingItems.get(currentItemIndex)
                : null;
    }

    /**
     * @return 这一次采集关心的所有条目：缺失清单（常规备货），再加上"当前采集目标"。
     *
     * <p>原材料追溯派出去的子材料不在清单上，但容器开箱判据、取物匹配、整盒判断、
     * 存盒排除全都要把它当成目标，否则容器会被判成"没有需要的东西"而被反复开关
     * （v1.9 修复的实际故障）。清单为空、或当前目标就在清单上时直接返回清单本身，
     * 不额外分配。
     */
    public java.util.List<MaterialItemEntry> wantedEntries() {
        if (currentTargetItem == null || isMissing(currentTargetItem)) {
            return missingItems;
        }
        if (wantedCacheTarget != currentTargetItem
                || wantedCacheNeed != targetNeededTotal
                || wantedCacheMissingSize != missingItems.size()) {
            wantedEntriesCache.clear();
            wantedEntriesCache.addAll(missingItems);
            wantedEntriesCache.add(new MaterialItemEntry(currentTargetItem, targetNeededTotal,
                    currentTargetItem.getDefaultMaxStackSize()));
            wantedCacheTarget = currentTargetItem;
            wantedCacheNeed = targetNeededTotal;
            wantedCacheMissingSize = missingItems.size();
        }
        return wantedEntriesCache;
    }

    private final java.util.List<MaterialItemEntry> wantedEntriesCache = new ArrayList<>();
    private Item wantedCacheTarget;
    private int wantedCacheNeed = -1;
    private int wantedCacheMissingSize = -1;

    /** @return 这个物品是不是本次采集关心的对象（清单上的，或当前正在追的那个子材料）。 */
    public boolean isWantedNow(Item item) {
        return item != null && (item == currentTargetItem || isMissing(item));
    }

    /** @return 缺失清单里 {@code item} 的条目；不在清单上时 {@code null}。 */
    public MaterialItemEntry missingEntry(Item item) {
        if (item == null) return null;
        for (MaterialItemEntry entry : missingItems) {
            if (entry.item == item) return entry;
        }
        return null;
    }

    /** @return {@code item} 是否在缺失清单上。 */
    public boolean isMissing(Item item) {
        return missingEntry(item) != null;
    }

    /**
     * @return 缺失清单给 {@code item} 定的需求总量；不在清单上时 0。
     *
     * <p>原材料追溯的"预留额度"规则靠它：合成只能动用"持有量 − 这个目标量"的富余，
     * 这样清单上已经备齐的东西不会被合成步骤吃掉。
     */
    public int targetFor(Item item) {
        MaterialItemEntry entry = missingEntry(item);
        return entry != null ? entry.neededCount : 0;
    }

    /** @return 这个潜影盒里是否装着"本次采集关心的"任意一种材料（含追溯中的子材料）。 */
    public boolean boxContainsWantedItem(ItemStack shulkerBox) {
        for (ItemStack inner : ItemUtil.contentsOf(shulkerBox)) {
            if (isWantedNow(inner.getItem())) return true;
        }
        return false;
    }

    /**
     * 清掉属于一次运行的所有状态。
     *
     * <p>{@code active} 和 {@code client} 刻意不动：{@code active} 由调用方在这个调用前后设置，
     * 而 {@code client} 是 Minecraft 实例，根本不属于某一次运行。
     */
    public void reset() {
        state = AutoMaterialGatherer.State.IDLE;

        missingItems.clear();
        currentItemIndex = 0;
        currentTargetItem = null;
        targetNeededTotal = 0;
        currentlyGathered = 0;

        foundPositions.clear();
        currentPosIndex = 0;
        chestRetryCount = 0;
        wholeBoxPriority = false;
        mixedBoxMode = false;
        mixedBoxReturnSlot = -1;
        mixedBoxReturnTarget = null;
        mixedBoxReturnClosing = false;
        adjacentOriginTarget = null;
        extractionBoxSlots.clear();
        extraStorableItems.clear();
        wantedEntriesCache.clear();
        wantedCacheTarget = null;
        wantedCacheNeed = -1;
        wantedCacheMissingSize = -1;
        tracedItems.clear();
        exhaustedPositions.clear();

        lastPlayerPos = Vec3.ZERO;
        stuckTicks = 0;
        pathingTicks = 0;
        pathingWasActive = false;
        currentPathTarget = null;

        transferCooldown = 0;
        containerJustOpened = false;
        openAttemptCount = 0;
        currentContainerTarget = null;
        adjacentContainerTargets = null;
        adjacentTryIndex = 0;

        justTookShulkerBox = false;
    }
}
