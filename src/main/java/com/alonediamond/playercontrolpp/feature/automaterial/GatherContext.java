package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialRequirements;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialTarget;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.RemainderStock;
import com.alonediamond.playercontrolpp.inventory.ContainerMenus;
import com.alonediamond.playercontrolpp.util.ItemUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            if (slot >= 0 && slot < Inventory.INVENTORY_SIZE) {
                count -= ItemUtil.countInside(client.player.getInventory().getItem(slot), item);
            }
        }
        return Math.max(0, count);
    }

    public Minecraft client;

    // 材料清单数据
    public final MaterialRequirements requirements = new MaterialRequirements();
    /** 仅当前投影或手工批次使用。交接后即使下一项仍然缺货，也不重复启动同一个整单。 */
    public RemainderStock remainderStock;
    private MaterialTarget target;
    private LooseAcquisition looseAcquisition;

    private record LooseQuota(int target, int remaining) {}

    private static final class LooseAcquisition {
        final BlockPos source;
        final ClientLevel level;
        final Map<Item, LooseQuota> quotas = new LinkedHashMap<>();
        Block block;
        AbstractContainerMenu menu;

        LooseAcquisition(BlockPos source, ClientLevel level) {
            this.source = source.immutable();
            this.level = level;
        }
    }

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

    public void setTarget(Item item, int neededTotal) {
        clearLooseAcquisition();
        target = new MaterialTarget(item, neededTotal);
    }

    /** 固定来源各物品分别计算散装目标和点击额度，顶层备货目标无需切换。 */
    public void bindLooseAcquisition(BlockPos source, Map<Item, Integer> amounts) {
        looseAcquisition = new LooseAcquisition(source, client.level);
        amounts.forEach((item, amount) -> {
            int held = ItemUtil.countPlainLoose(client.player, item);
            looseAcquisition.quotas.put(item, new LooseQuota(held + amount, amount));
        });
    }

    public void clearLooseAcquisition() {
        looseAcquisition = null;
    }

    public BlockPos boundLooseSource() {
        return looseAcquisition == null ? null : looseAcquisition.source;
    }

    public boolean isTargetSatisfied() {
        if (looseAcquisition == null) return target != null && countHeld(targetItem()) >= targetTotal();
        for (Map.Entry<Item, LooseQuota> entry : looseAcquisition.quotas.entrySet()) {
            if (ItemUtil.countPlainLoose(client.player, entry.getKey()) < entry.getValue().target()) return false;
        }
        return true;
    }

    public Map<Item, Integer> boundLooseGaps() {
        Map<Item, Integer> gaps = new LinkedHashMap<>();
        looseAcquisition.quotas.forEach((item, quota) -> {
            int gap = quota.target() - ItemUtil.countPlainLoose(client.player, item);
            int allowed = Math.min(quota.remaining(), gap);
            if (allowed > 0) gaps.put(item, allowed);
        });
        return gaps;
    }

    /** 先扣点击上限，预测未生效或玩家移走散装时也不会再从箱里拿超。 */
    public void spendBoundLooseQuota(Item item, int amount) {
        looseAcquisition.quotas.computeIfPresent(item,
                (key, quota) -> new LooseQuota(quota.target(), quota.remaining() - amount));
    }

    public boolean beginBoundLooseOpen(BlockPos position) {
        looseAcquisition.menu = null;
        looseAcquisition.block = null;
        if (client.level != looseAcquisition.level || !looseAcquisition.source.equals(position)
                || ContainerMenus.isOpen(client) || client.player.isSecondaryUseActive()) return false;
        Block block = client.level.getBlockState(position).getBlock();
        if (!ContainerMenus.isPlainStorageBlock(block)) return false;
        looseAcquisition.block = block;
        return true;
    }

    public boolean isBoundLooseBlockCurrent() {
        return looseAcquisition.block != null && client.level == looseAcquisition.level
                && looseAcquisition.source.equals(currentContainerTarget)
                && client.level.getBlockState(looseAcquisition.source).getBlock() == looseAcquisition.block;
    }

    public void acceptBoundLooseMenu(AbstractContainerMenu menu) {
        looseAcquisition.menu = menu;
    }

    public boolean ownsBoundLooseMenu(AbstractContainerMenu menu) {
        return looseAcquisition.menu == menu;
    }

    public Item targetItem() {
        return target == null ? null : target.item();
    }

    public int targetTotal() {
        return target == null ? 0 : target.neededTotal();
    }

    public List<MaterialItemEntry> wantedEntries() {
        return requirements.includingTarget(targetItem(), targetTotal());
    }

    public boolean isWantedNow(Item item) {
        return item != null && (item == targetItem() || requirements.contains(item));
    }

    /** 清单预留量不随追溯子材料的临时目标变化。 */
    public int targetFor(Item item) {
        return requirements.targetFor(item);
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
        requirements.clear();
        remainderStock = null;
        target = null;
        clearLooseAcquisition();

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

        transferCooldown = 0;
        containerJustOpened = false;
        openAttemptCount = 0;
        currentContainerTarget = null;
        adjacentContainerTargets = null;
        adjacentTryIndex = 0;

    }
}
