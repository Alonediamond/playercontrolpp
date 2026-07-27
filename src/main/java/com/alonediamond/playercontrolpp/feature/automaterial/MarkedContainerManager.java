package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.config.Configs;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.*;

/**
 * Manages the list of container positions the player has marked for the auto-restock feature.
 *
 * <p>Entries live in the main config as a {@code ConfigStringList} ({@code Restocks.MARKED_CONTAINERS}),
 * one per line in the form {@code "dimension x y z"} (e.g. {@code "minecraft:overworld 10 64 -20"}).
 * That makes them editable from the mod's config GUI without a separate file, and malilib's built-in
 * list editor handles add/remove/reorder.
 *
 * <p>Dimension keys are obtained from {@link Level#dimension()} and its {@code toString()}
 * representation; the same routine normalises the stored value so the two can be compared.
 */
public class MarkedContainerManager {

    private static final MarkedContainerManager INSTANCE = new MarkedContainerManager();

    private MarkedContainerManager() {}

    public static MarkedContainerManager getInstance() { return INSTANCE; }

    // ---- Public API ----

    public List<MarkedContainer> all() {
        return parseAll(Configs.Restocks.MARKED_CONTAINERS.getStrings());
    }

    public List<BlockPos> positionsInCurrentDimension(Level level) {
        String currentDim = dimensionIdOf(level);
        if (currentDim == null) return Collections.emptyList();
        List<BlockPos> result = new ArrayList<>();
        for (MarkedContainer mc : all()) {
            if (mc.dimension.equals(currentDim)) {
                result.add(mc.pos);
            }
        }
        return result;
    }

    /**
     * Mark the container at {@code pos} in {@code level}. Idempotent — if already marked,
     * returns {@code false} without creating a duplicate.
     */
    public boolean add(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        // Format: "dim x y z" — parseEntry() expects dimension first, coordinates after.
        String entry = dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        List<String> current = new ArrayList<>(Configs.Restocks.MARKED_CONTAINERS.getStrings());
        for (String s : current) {
            if (matches(s, pos, dim)) return false;
        }
        current.add(entry);
        Configs.Restocks.MARKED_CONTAINERS.setStrings(current);
        Configs.saveToFile();
        return true;
    }

    /** @return {@code true} when the position was present and removed. */
    public boolean remove(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        List<String> current = new ArrayList<>(Configs.Restocks.MARKED_CONTAINERS.getStrings());
        boolean removed = current.removeIf(s -> matches(s, pos, dim));
        if (removed) {
            Configs.Restocks.MARKED_CONTAINERS.setStrings(current);
            Configs.saveToFile();
        }
        return removed;
    }

    public boolean contains(BlockPos pos, Level level) {
        String dim = dimensionIdOf(level);
        if (dim == null) return false;
        for (String s : Configs.Restocks.MARKED_CONTAINERS.getStrings()) {
            if (matches(s, pos, dim)) return true;
        }
        return false;
    }

    public int size() { return Configs.Restocks.MARKED_CONTAINERS.getStrings().size(); }

    // ---- Parsing ----

    private static List<MarkedContainer> parseAll(List<String> strings) {
        List<MarkedContainer> out = new ArrayList<>();
        for (String s : strings) {
            MarkedContainer mc = parseEntry(s);
            if (mc != null) out.add(mc);
        }
        return out;
    }

    /** @return a parsed entry or {@code null} when the line is malformed. */
    static MarkedContainer parseEntry(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.split("\\s+");
        if (parts.length != 4) return null;
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            return new MarkedContainer(new BlockPos(x, y, z), parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean matches(String entry, BlockPos pos, String dim) {
        MarkedContainer mc = parseEntry(entry);
        return mc != null && mc.pos.equals(pos) && mc.dimension.equals(dim);
    }

    // ---- Dimension key extraction (version-agnostic) ----

    /**
     * ResourceKey.toString() gives a stable representation like
     * {@code "ResourceKey[minecraft:dimension / minecraft:overworld]"} on all versions we
     * target. We extract the namespace:path part after the final {@code "/ "}, which stays
     * constant in every Minecraft release from 1.21 through 26.2.
     */
    static String dimensionIdOf(Level level) {
        try {
            String raw = level.dimension().toString();
            int slash = raw.indexOf('/');
            if (slash >= 0) {
                String inner = raw.substring(slash + 1).trim();
                if (inner.endsWith("]")) {
                    inner = inner.substring(0, inner.length() - 1).trim();
                }
                return inner;
            }
            return raw;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Data class ----

    public record MarkedContainer(BlockPos pos, String dimension) {}
}
