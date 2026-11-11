package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.container.ContainerOpener;
import com.alonediamond.playercontrolpp.feature.automaterial.shulker.ShulkerBoxAccess;

import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单轮任务的协作数据；主状态由 AutoMaterialGatherer 私有持有，寻路及存取过程状态归各自执行器。 */
public final class GatherContext {

    public int mixedBoxOriginSlot = -1;
    public ItemStack mixedBoxIdentity = ItemStack.EMPTY;
    public boolean storageAfterExtraction;

    /** 借来的杂盒只把已取出的散装计入备货，盒内余额不能冒充自己的库存。 */
    public int countHeld(Item item) {
        int count = ItemUtil.countEverywhere(client.player, item);
        for (int slot : extractionBoxSlots) {
            if (slot >= 0 && slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE) {
                count -= ItemUtil.countInside(client.player.getInventory().getItem(slot), item);
            }
        }
        return Math.max(0, count);
    }

    public Minecraft client;

    // 材料清单数据
    public final List<MaterialItemEntry> missingItems = new ArrayList<>();
    public Item currentTargetItem;
    public int targetNeededTotal;

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
     * 本轮已经「把里面所有还需要的东西都拿走了」的容器。
     *
     * <p>与 {@link #exhaustedPositions} 的区别：后者换物品时清空，因为它只表示"对那一件没货"；
     * 这个是**全局**的——一次开箱已经把当前所有缺料都拿走之后，无论接下来要哪一件，
     * 这个箱子都不必再开一次。搜索时两个集合都跳过，所以同一个箱子只会被打开一次。
     */
    public final Set<BlockPos> drainedContainers = new HashSet<>();

    /**
     * 待归还的杂盒所在物品栏槽位；&lt; 0 表示没有待归还的盒子。
     * 由 {@code ShulkerBoxAccess} 在取物结束时写入，{@code ContainerOpener} 在容器打开后消费。
     */
    public int mixedBoxReturnSlot = -1;

    /** 待归还的杂盒原来所在的容器坐标；与 {@link #mixedBoxReturnSlot} 配对使用。 */
    public BlockPos mixedBoxReturnTarget;

    /**
     * 已经把盒子放回容器、正等关闭界面的那一拍。
     *
     * <p>点击和关闭是两个包，同 tick 连发有被服务端按"界面已关"处理掉的风险；
     * 由 {@code AutoMaterialGatherer} 在冷却结束后统一关界面。
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

    // 容器交互
    public int transferCooldown;
    public boolean containerJustOpened;
    public int openAttemptCount;
    public BlockPos currentContainerTarget;
    public List<BlockPos> adjacentContainerTargets;
    public int adjacentTryIndex;

    /**
     * @return 这一次采集关心的所有条目：缺失清单（常规备货），再加上"当前采集目标"。
     *
     * <p>原材料追溯派出去的子材料不在清单上，但容器开箱判据、取物匹配、整盒判断、
     * 存盒排除全都要把它当成目标，否则容器会被判成"没有需要的东西"而被反复开关
     * （v1.9 修复的实际故障）。清单为空、或当前目标在清单上且这次要的不比清单多时
     * 直接返回清单本身，不额外分配；只有"追溯要的量超过了清单给它的目标量"时才补一条
     * 按这次目标量算的条目——否则取物拿到清单量就停手，合成永远差料。
     */
    public java.util.List<MaterialItemEntry> wantedEntries() {
        if (currentTargetItem == null) {
            return missingItems;
        }
        MaterialItemEntry existing = missingEntry(currentTargetItem);
        // 清单上已经有这一项、而且这次要的不比清单给它的目标量多：直接用清单本身。
        if (existing != null && targetNeededTotal <= existing.neededCount) {
            return missingItems;
        }
        // 当前目标不在清单上（追溯派出去的中间材料），或虽然在家清单上、但这次要的更多
        // （清单预留量 + 合成要消耗的量）：临时补一条按这次目标量算的条目。
        // 少了这一步，取物会在拿到清单量时就停手，合成永远差料。
        List<MaterialItemEntry> entries = new ArrayList<>(missingItems);
        entries.removeIf(entry -> entry.item == currentTargetItem);
        entries.add(new MaterialItemEntry(currentTargetItem, targetNeededTotal, currentTargetItem.getDefaultMaxStackSize()));
        return entries;
    }

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

    /**
     * 清掉属于一次运行的所有状态。
     *
     * 客户端引用不属于运行状态，保留给下一轮任务使用。
     */
    public void reset() {

        mixedBoxOriginSlot = -1;
        mixedBoxIdentity = ItemStack.EMPTY;
        storageAfterExtraction = false;
        missingItems.clear();
        currentTargetItem = null;
        targetNeededTotal = 0;

        foundPositions.clear();
        currentPosIndex = 0;
        chestRetryCount = 0;
        mixedBoxReturnSlot = -1;
        mixedBoxReturnTarget = null;
        mixedBoxReturnClosing = false;
        adjacentOriginTarget = null;
        extractionBoxSlots.clear();
        extraStorableItems.clear();
        tracedItems.clear();
        exhaustedPositions.clear();
        drainedContainers.clear();

        transferCooldown = 0;
        containerJustOpened = false;
        openAttemptCount = 0;
        currentContainerTarget = null;
        adjacentContainerTargets = null;
        adjacentTryIndex = 0;

    }
}
