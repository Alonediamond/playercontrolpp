package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.WorldMarkScope;
import com.alonediamond.playercontrolpp.config.Configs;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** 在原配置列表中存储标记。未归属的旧行只通过显式导入迁移。 */
public final class ScopedBlockMarks {
    private static final String VERSION = "v2";
    private static final String DISABLED = "off";

    public record Mark(BlockPos pos, boolean enabled) {
        public String coordinates() {
            return pos.getX() + " " + pos.getY() + " " + pos.getZ() + (enabled ? "" : " " + DISABLED);
        }
    }

    public enum Availability { UNLOADED, VALID, INVALID }

    record ImportResult(List<String> rows, int imported) {}

    private record Entry(WorldMarkScope scope, Mark mark) {}
    private record Legacy(String dimension, Mark mark) {}

    private final ConfigStringList config;

    public ScopedBlockMarks(ConfigStringList config) {
        this.config = config;
    }

    public String name() { return config.getName(); }

    public List<Mark> entries(WorldMarkScope scope) {
        return entries(config.getStrings(), scope);
    }

    public void add(WorldMarkScope scope, BlockPos pos) {
        List<Mark> current = new ArrayList<>(entries(scope));
        current.add(new Mark(pos.immutable(), true));
        replace(scope, current);
    }

    public boolean remove(WorldMarkScope scope, BlockPos pos) {
        List<Mark> current = new ArrayList<>(entries(scope));
        if (!current.removeIf(mark -> mark.pos().equals(pos))) return false;
        replace(scope, current);
        return true;
    }

    public Boolean toggleEnabled(WorldMarkScope scope, BlockPos pos) {
        List<Mark> current = new ArrayList<>(entries(scope));
        for (int i = 0; i < current.size(); i++) {
            Mark mark = current.get(i);
            if (!mark.pos().equals(pos)) continue;
            boolean enabled = !mark.enabled();
            current.set(i, new Mark(mark.pos(), enabled));
            replace(scope, current);
            return enabled;
        }
        return null;
    }

    public void replace(WorldMarkScope scope, List<Mark> marks) {
        if (scope != null) save(replace(config.getStrings(), scope, marks));
    }

    public int importLegacy(WorldMarkScope scope, Predicate<BlockPos> verified) {
        if (scope == null) return 0;
        ImportResult result = importLegacy(config.getStrings(), scope, verified);
        if (result.imported() > 0) save(result.rows());
        return result.imported();
    }

    private void save(List<String> rows) {
        if (config.getStrings().equals(rows)) return;
        config.setStrings(rows);
        Configs.saveToFile();
    }

    public static Availability availability(Level level, BlockPos pos, Predicate<BlockPos> matches) {
        if (!level.isInWorldBounds(pos)) return Availability.INVALID;
        // ClientLevel.hasChunk 恒为真。只查询区块缓存，不能对占位空区块判定方块类型。
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return Availability.UNLOADED;
        }
        return matches.test(pos) ? Availability.VALID : Availability.INVALID;
    }

    static List<Mark> entries(List<String> rows, WorldMarkScope scope) {
        if (scope == null) return List.of();
        Map<BlockPos, Mark> marks = new LinkedHashMap<>();
        for (String row : rows) {
            Entry entry = scopedEntry(row);
            if (entry != null && entry.scope().equals(scope)) {
                marks.putIfAbsent(entry.mark().pos(), entry.mark());
            }
        }
        return List.copyOf(marks.values());
    }

    static List<String> replace(List<String> rows, WorldMarkScope scope, List<Mark> marks) {
        List<String> replacement = new ArrayList<>();
        for (String row : rows) {
            Entry entry = scopedEntry(row);
            if (entry == null || !entry.scope().equals(scope)) replacement.add(row);
        }
        Map<BlockPos, Mark> unique = new LinkedHashMap<>();
        for (Mark mark : marks) unique.putIfAbsent(mark.pos(), mark);
        for (Mark mark : unique.values()) replacement.add(format(scope, mark));
        return replacement;
    }

    static ImportResult importLegacy(List<String> rows, WorldMarkScope scope, Predicate<BlockPos> verified) {
        Map<BlockPos, Mark> existing = new LinkedHashMap<>();
        for (Mark mark : entries(rows, scope)) existing.put(mark.pos(), mark);
        List<String> kept = new ArrayList<>();
        List<String> converted = new ArrayList<>();
        int imported = 0;
        for (String row : rows) {
            Legacy legacy = legacyEntry(row);
            if (legacy == null || !scope.dimension().equals(legacy.dimension()) || !verified.test(legacy.mark().pos())) {
                kept.add(row);
                continue;
            }
            if (existing.putIfAbsent(legacy.mark().pos(), legacy.mark()) == null) {
                converted.add(format(scope, legacy.mark()));
            }
            imported++;
        }
        kept.addAll(converted);
        return new ImportResult(kept, imported);
    }

    public static Mark parseCoordinates(String coordinates, boolean allowDisabled) {
        return parseMark(coordinates.strip().split("\\s+"), 0, allowDisabled);
    }

    private static Entry scopedEntry(String line) {
        String[] tokens = line.strip().split("\\s+");
        if (tokens.length < 6 || !VERSION.equals(tokens[0])) return null;
        Mark mark = parseMark(tokens, 3, true);
        if (mark == null) return null;
        try {
            return new Entry(new WorldMarkScope(UUID.fromString(tokens[1]), tokens[2]), mark);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Legacy legacyEntry(String line) {
        String[] tokens = line.strip().split("\\s+");
        Mark mark = parseMark(tokens, 1, true);
        return mark == null ? null : new Legacy(tokens[0], mark);
    }

    private static Mark parseMark(String[] tokens, int start, boolean allowDisabled) {
        int end = start + 3;
        boolean disabled = tokens.length == end + 1 && allowDisabled && DISABLED.equalsIgnoreCase(tokens[end]);
        if (tokens.length != end && !disabled) return null;
        try {
            return new Mark(new BlockPos(Integer.parseInt(tokens[start]), Integer.parseInt(tokens[start + 1]),
                    Integer.parseInt(tokens[start + 2])), !disabled);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String format(WorldMarkScope scope, Mark mark) {
        return VERSION + " " + scope.world() + " " + scope.dimension() + " " + mark.coordinates();
    }
}
