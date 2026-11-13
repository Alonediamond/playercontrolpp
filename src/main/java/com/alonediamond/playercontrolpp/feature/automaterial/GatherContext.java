package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialRequirements;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialTarget;
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
    public final MaterialRequirements requirements = new MaterialRequirements();
    private MaterialTarget target;

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
        target = new MaterialTarget(item, neededTotal);
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
        target = null;

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
