package com.alonediamond.playercontrolpp.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;

import java.util.*;

public class ChestTrackerIntegration implements ModIntegration {

    private static final ChestTrackerIntegration INSTANCE = new ChestTrackerIntegration();
    private boolean loaded;

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

        } catch (Exception ignored) {}
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
}
