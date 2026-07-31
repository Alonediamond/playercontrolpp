package com.alonediamond.playercontrolpp.integration;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.lang.reflect.Method;
import java.util.*;

public class ChestTrackerIntegration implements ModIntegration {

    private static final ChestTrackerIntegration INSTANCE = new ChestTrackerIntegration();
    private boolean loaded;
    private Class<?> memoryBuilderClass;
    private Class<?> memoryClass;
    private Method createMemoryBuilderMethod;
    private Method builderInContainerMethod;
    private Method builderOtherPositionsMethod;
    private Method builderBuildMethod;
    private Object interactionTracker;
    private Method clearInteractionTrackerMethod;

    private ChestTrackerIntegration() {}

    public static ChestTrackerIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("chesttracker");
    }

    private Object getMemoryBank() throws Exception {
        Class<?> accessClass = Class.forName("red.jackf.chesttracker.api.memory.MemoryBankAccess");
        Object instance = accessClass.getField("INSTANCE").get(null);
        Optional<?> loaded = (Optional<?>) instance.getClass().getMethod("getLoaded").invoke(instance);
        return loaded.orElse(null);
    }

    /**
     * Get the search range from ChestTracker settings.
     * Returns -1 if ChestTracker is not loaded.
     */
    public int getSearchRange() {
        try {
            Object memoryBank = getMemoryBank();
            if (memoryBank == null) return -1;
            Object metadata = memoryBank.getClass().getMethod("getMetadata").invoke(memoryBank);
            Object searchSettings = metadata.getClass().getMethod("getSearchSettings").invoke(metadata);
            return searchSettings.getClass().getField("searchRange").getInt(searchSettings);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get the item list range from ChestTracker settings.
     * Returns -1 if ChestTracker is not loaded.
     */
    public int getListRange() {
        try {
            Object memoryBank = getMemoryBank();
            if (memoryBank == null) return -1;
            Object metadata = memoryBank.getClass().getMethod("getMetadata").invoke(memoryBank);
            Object searchSettings = metadata.getClass().getMethod("getSearchSettings").invoke(metadata);
            return searchSettings.getClass().getField("itemListRange").getInt(searchSettings);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Get the current dimension key for ChestTracker queries.
     */
    public Identifier getCurrentDimensionKey() {
        try {
            Class<?> utilsClass = Class.forName("red.jackf.chesttracker.api.providers.ProviderUtils");
            Optional<?> key = (Optional<?>) utilsClass.getMethod("getPlayersCurrentKey").invoke(null);
            return (Identifier) key.orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Search ChestTracker memories for container positions containing the target item.
     * Returns positions sorted by distance from the player.
     */
    public List<BlockPos> searchItem(Item targetItem, BlockPos playerPos, int effectiveRange) {
        List<BlockPos> positions = new ArrayList<>();
        try {
            Object memoryBank = getMemoryBank();
            if (memoryBank == null) return positions;

            Identifier currentDim = getCurrentDimensionKey();
            if (currentDim == null) return positions;

            Optional<?> memKeyOpt = (Optional<?>) memoryBank.getClass()
                    .getMethod("getKey", Identifier.class)
                    .invoke(memoryBank, currentDim);
            if (memKeyOpt.isEmpty()) return positions;

            Object memoryKey = memKeyOpt.get();
            Map<?, ?> memories = (Map<?, ?>) memoryKey.getClass()
                    .getMethod("getMemories").invoke(memoryKey);

            long rangeSq = (long) effectiveRange * effectiveRange;

            for (Map.Entry<?, ?> memEntry : memories.entrySet()) {
                BlockPos pos = (BlockPos) memEntry.getKey();
                if (pos.distSqr(playerPos) > rangeSq) continue;

                Object memory = memEntry.getValue();
                List<?> items = (List<?>) memory.getClass().getMethod("items").invoke(memory);

                for (Object itemObj : items) {
                    ItemStack stack = (ItemStack) itemObj;
                    if (stack.isEmpty()) continue;
                    if (stack.getItem() == targetItem ||
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(BuiltInRegistries.ITEM.getKey(targetItem))) {
                        positions.add(pos);
                        break;
                    }
                }
            }

            positions.sort(Comparator.comparingDouble(p -> p.distSqr(playerPos)));

        } catch (Exception e) {
            // ChestTracker is absent or its internals moved. Returning whatever was collected so
            // far degrades to "found nothing here", which the caller already handles by moving on
            // to the next item.
            Playercontrolpp.LOGGER.debug("ChestTracker memory lookup failed for {}", targetItem, e);
        }
        return positions;
    }

    /**
     * Check if a loaded memory bank exists.
     */
    public boolean hasLoadedMemoryBank() {
        try {
            return getMemoryBank() != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Save one remotely synchronized menu directly into Chest Tracker's currently loaded bank.
     * No secondary memory bank is created.
     */
    public boolean cacheContainer(
            Level level,
            BlockPos position,
            BlockState state,
            AbstractContainerMenu menu) {
        if (!loaded || level == null || position == null || state == null || menu == null) {
            return false;
        }

        try {
            Object memoryBank = getMemoryBank();
            Identifier memoryKey = getCurrentDimensionKey();
            if (memoryBank == null || memoryKey == null) return false;

            List<ItemStack> items = new ArrayList<>();
            for (Slot slot : menu.slots) {
                if (!(slot.container instanceof Inventory)) {
                    items.add(slot.getItem().copy());
                }
            }
            if (items.isEmpty()) return false;

            resolveMemoryBuilderApi();
            Object builder = createMemoryBuilderMethod.invoke(null, items);
            builder = builderInContainerMethod.invoke(builder, state.getBlock());
            builder = builderOtherPositionsMethod.invoke(
                    builder, connectedPositions(level, position, state));
            Object memory = builderBuildMethod.invoke(builder);

            Method addMemory = memoryBank.getClass().getMethod(
                    "addMemory", Identifier.class, BlockPos.class, memoryClass);
            addMemory.invoke(memoryBank, memoryKey, position.immutable(), memory);
            clearInteractionTracker();
            return true;
        } catch (Exception e) {
            Playercontrolpp.LOGGER.warn(
                    "Failed to write container {} to Chest Tracker's current memory bank",
                    position, e);
            return false;
        }
    }

    private void resolveMemoryBuilderApi() throws Exception {
        if (memoryBuilderClass != null) return;

        memoryBuilderClass = Class.forName("red.jackf.chesttracker.api.providers.MemoryBuilder");
        memoryClass = Class.forName("red.jackf.chesttracker.api.memory.Memory");
        createMemoryBuilderMethod = memoryBuilderClass.getMethod("create", List.class);
        builderInContainerMethod = memoryBuilderClass.getMethod("inContainer", Block.class);
        builderOtherPositionsMethod = memoryBuilderClass.getMethod("otherPositions", List.class);
        builderBuildMethod = memoryBuilderClass.getMethod("build");
    }

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

    /** Prevent Chest Tracker's normal screen-close provider from using a stale interaction. */
    public void clearInteractionTracker() {
        if (!loaded) return;
        try {
            if (clearInteractionTrackerMethod == null) {
                Class<?> trackerClass = Class.forName(
                        "red.jackf.chesttracker.api.providers.InteractionTracker");
                interactionTracker = trackerClass.getField("INSTANCE").get(null);
                clearInteractionTrackerMethod = trackerClass.getMethod("clear");
            }
            clearInteractionTrackerMethod.invoke(interactionTracker);
        } catch (Exception e) {
            Playercontrolpp.LOGGER.debug("Unable to clear Chest Tracker interaction state", e);
        }
    }
}
