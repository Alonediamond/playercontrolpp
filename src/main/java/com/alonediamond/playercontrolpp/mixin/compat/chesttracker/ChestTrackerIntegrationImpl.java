package com.alonediamond.playercontrolpp.mixin.compat.chesttracker;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.integration.ChestTrackerIntegration;
import com.alonediamond.playercontrolpp.util.ItemUtil;

import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBank;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;
import red.jackf.chesttracker.api.memory.MemoryKey;
import red.jackf.chesttracker.api.providers.InteractionTracker;
import red.jackf.chesttracker.api.providers.MemoryBuilder;
import red.jackf.chesttracker.api.providers.ProviderUtils;
import red.jackf.chesttracker.impl.memory.MemoryBankImpl;
import red.jackf.chesttracker.impl.memory.metadata.Metadata;
import red.jackf.chesttracker.impl.memory.metadata.SearchSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * {@link ChestTrackerIntegration} 的直连实现，由 MixinPlugin 在 ChestTracker 在场时注入。
 *
 * <p>ChestTracker 从 2.6.7（1.21.1）到 2.8.4（26.2）逐版本核对过，这里用到的 API 完全一致。
 * 唯一例外是 {@code getMetadata()}：它不在 API 接口上，只在实现类 {@code MemoryBankImpl}
 * 里，但其类与 getter 全版本都是 public，直接引用即可（编译期绑定到各版本自己的 jar）。
 *
 * <p>方法体捕获 {@link Throwable} 的理由见 {@code LitematicaIntegrationImpl}：签名漂移会
 * 以 {@code Error} 形式出现，要与旧反射实现一样静默降级。
 */
@Mixin(ChestTrackerIntegration.class)
public abstract class ChestTrackerIntegrationImpl {

    @Unique
    private static MemoryBank loadedMemoryBank() {
        return MemoryBankAccess.INSTANCE.getLoaded().orElse(null);
    }

    /** @return 当前缓存库的搜索设置；没有缓存库时 {@code null}。 */
    @Unique
    private static SearchSettings searchSettings() {
        MemoryBank bank = loadedMemoryBank();
        if (bank == null) return null;
        Metadata metadata = ((MemoryBankImpl) bank).getMetadata();
        return metadata.getSearchSettings();
    }

    @Overwrite(remap = false)
    public boolean isLoaded() {
        return true;
    }

    @Overwrite(remap = false)
    public int getSearchRange() {
        try {
            SearchSettings settings = searchSettings();
            return settings == null ? -1 : settings.searchRange;
        } catch (Throwable e) {
            return -1;
        }
    }

    @Overwrite(remap = false)
    public int getListRange() {
        try {
            SearchSettings settings = searchSettings();
            return settings == null ? -1 : settings.itemListRange;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** @return 供箱子追踪查询用的当前维度 key。 */
    @Overwrite(remap = false)
    public Identifier getCurrentDimensionKey() {
        try {
            return ProviderUtils.getPlayersCurrentKey().orElse(null);
        } catch (Throwable e) {
            return null;
        }
    }

    @Overwrite(remap = false)
    public boolean hasLoadedMemoryBank() {
        try {
            return MemoryBankAccess.INSTANCE.getLoaded().isPresent();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 在箱子追踪的记忆里搜含有目标物品的容器坐标，按距玩家由近到远返回。 */
    @Overwrite(remap = false)
    public List<BlockPos> searchItem(Item targetItem, BlockPos playerPos, int effectiveRange) {
        return searchMemories(playerPos, effectiveRange, targetItem, (stack, item) ->
                stack.getItem() == item
                        || BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .equals(BuiltInRegistries.ITEM.getKey(item)));
    }

    /**
     * 在箱子追踪的记忆里搜「装着目标物品的整盒（潜影盒）」所在的容器坐标，按距玩家由近到远返回。
     *
     * <p>记忆里存的是容器打开时的物品堆快照，潜影盒的内容物随物品堆组件一起落库，
     * 所以直接用 {@link ItemUtil#containsInside} 判断盒里有没有目标物品。
     */
    @Overwrite(remap = false)
    public List<BlockPos> searchShulkerBoxWithItem(Item targetItem, BlockPos playerPos, int effectiveRange) {
        return searchMemories(playerPos, effectiveRange, targetItem, (stack, item) ->
                ItemUtil.isShulkerBox(stack) && ItemUtil.containsInside(stack, item));
    }

    @Unique
    private static List<BlockPos> searchMemories(
            BlockPos playerPos, int effectiveRange, Item targetItem,
            BiPredicate<ItemStack, Item> match) {
        List<BlockPos> positions = new ArrayList<>();
        try {
            MemoryBank bank = loadedMemoryBank();
            if (bank == null) return positions;

            Identifier currentDim = ProviderUtils.getPlayersCurrentKey().orElse(null);
            if (currentDim == null) return positions;

            Optional<MemoryKey> memKeyOpt = bank.getKey(currentDim);
            if (memKeyOpt.isEmpty()) return positions;

            Map<BlockPos, Memory> memories = memKeyOpt.get().getMemories();

            long rangeSq = (long) effectiveRange * effectiveRange;

            for (Map.Entry<BlockPos, Memory> memEntry : memories.entrySet()) {
                BlockPos pos = memEntry.getKey();
                if (pos.distSqr(playerPos) > rangeSq) continue;

                for (ItemStack stack : memEntry.getValue().items()) {
                    if (stack.isEmpty()) continue;
                    if (match.test(stack, targetItem)) {
                        positions.add(pos);
                        break;
                    }
                }
            }

            positions.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));

        } catch (Throwable e) {
            // 箱子追踪的内部结构变了。返回已经收集到的部分，效果退化成「这里没找到」，
            // 调用方本来就会据此换下一个物品。
            Playercontrolpp.LOGGER.debug("ChestTracker memory lookup failed for {}", targetItem, e);
        }
        return positions;
    }

    /**
     * 把一个已同步的容器菜单直接写进箱子追踪当前加载的缓存库，不另建缓存库。
     */
    @Overwrite(remap = false)
    public boolean cacheContainer(
            Level level,
            BlockPos position,
            BlockState state,
            AbstractContainerMenu menu) {
        if (level == null || position == null || state == null || menu == null) {
            return false;
        }

        try {
            MemoryBank bank = loadedMemoryBank();
            Identifier memoryKey = ProviderUtils.getPlayersCurrentKey().orElse(null);
            if (bank == null || memoryKey == null) return false;

            List<ItemStack> items = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (!(slot.container instanceof Inventory)) {
                    items.add(slot.getItem().copy());
                }
            }
            if (items.isEmpty()) return false;

            Memory memory = MemoryBuilder.create(items)
                    .inContainer(state.getBlock())
                    .otherPositions(connectedPositions(level, position, state))
                    .build();
            bank.addMemory(memoryKey, position.immutable(), memory);
            clearInteractionTracker();
            return true;
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.warn(
                    "Failed to write container {} to Chest Tracker's current memory bank",
                    position, e);
            return false;
        }
    }

    @Unique
    private static List<BlockPos> connectedPositions(
            Level level, BlockPos position, BlockState state) {
        if (state.getBlock() instanceof ChestBlock
                && state.hasProperty(ChestBlock.TYPE)
                && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            BlockPos other = position.relative(ChestBlock.getConnectedDirection(state));
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() == state.getBlock()) {
                return List.of(other.immutable());
            }
        }
        return Collections.emptyList();
    }

    /** 清掉箱子追踪的交互记录，避免它在关界面时用一条过期的交互重复落库。 */
    @Overwrite(remap = false)
    public void clearInteractionTracker() {
        try {
            InteractionTracker.INSTANCE.clear();
        } catch (Throwable e) {
            Playercontrolpp.LOGGER.debug("Unable to clear Chest Tracker interaction state", e);
        }
    }
}
