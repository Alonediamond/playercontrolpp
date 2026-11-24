package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.compat.WorldMarkScope;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.ImportResult;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.Mark;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

final class ScopedBlockMarksRegression {
    private int checks;

    static int run() {
        ScopedBlockMarksRegression regression = new ScopedBlockMarksRegression();
        regression.checkIsolationAndMigration();
        return regression.checks;
    }

    private void checkIsolationAndMigration() {
        String overworld = "minecraft:overworld";
        UUID firstWorld = new UUID(0, 1);
        WorldMarkScope first = new WorldMarkScope(firstWorld, overworld);
        WorldMarkScope second = new WorldMarkScope(new UUID(0, 2), overworld);
        WorldMarkScope nether = new WorldMarkScope(firstWorld, "minecraft:the_nether");
        BlockPos station = new BlockPos(257, -60, -467);
        BlockPos oldBox = new BlockPos(10, 64, -20);
        BlockPos unavailable = new BlockPos(64, 64, 64);
        List<String> legacy = List.of(
                "minecraft:overworld 10 64 -20 off",
                "minecraft:overworld 64 64 64",
                "minecraft:the_nether 10 64 -20",
                "minecraft:overworld 257 -60 -467 off",
                "unrecognized row");
        List<String> rows = ScopedBlockMarks.replace(legacy, first, List.of(new Mark(station, true)));
        rows = ScopedBlockMarks.replace(rows, second, List.of(new Mark(station, false)));
        rows = ScopedBlockMarks.replace(rows, nether, List.of(new Mark(oldBox, true)));

        check(ScopedBlockMarks.entries(rows, first).equals(List.of(new Mark(station, true))), "旧坐标不能直接参与寻路");
        check(ScopedBlockMarks.entries(rows, second).equals(List.of(new Mark(station, false))), "另一个世界保有独立启用状态");
        check(ScopedBlockMarks.entries(rows, nether).equals(List.of(new Mark(oldBox, true))), "同一世界的不同维度隔离");
        check(ScopedBlockMarks.entries(rows, null).isEmpty(), "无身份时不使用任何标记");

        ImportResult imported = ScopedBlockMarks.importLegacy(rows, first,
                pos -> pos.equals(oldBox) || pos.equals(station));
        check(imported.imported() == 2, "只导入当前维度实际可核验的旧记录");
        check(ScopedBlockMarks.entries(imported.rows(), first).equals(List.of(new Mark(station, true), new Mark(oldBox, false))),
                "迁移保留旧禁用状态和目标已存在的标记");
        check(imported.rows().containsAll(List.of("minecraft:overworld 64 64 64", "minecraft:the_nether 10 64 -20",
                "unrecognized row")), "保留未核验、其他维度和无法解析的记录");
        check(ScopedBlockMarks.importLegacy(imported.rows(), first, oldBox::equals).imported() == 0, "再次导入不会重复使用旧记录");

        List<String> edited = ScopedBlockMarks.replace(imported.rows(), first,
                List.of(new Mark(unavailable, true), new Mark(unavailable, false)));
        check(ScopedBlockMarks.entries(edited, first).equals(List.of(new Mark(unavailable, true))), "编辑后同坐标去重");
        check(ScopedBlockMarks.entries(edited, second).equals(ScopedBlockMarks.entries(rows, second)),
                "编辑当前世界不覆盖其他世界");
        check(new Mark(oldBox, false).equals(ScopedBlockMarks.parseCoordinates("10 64 -20 OFF", true)), "容器可编辑禁用状态");
        check(ScopedBlockMarks.parseCoordinates("10 64 -20 off", false) == null, "工作站编辑器只接受坐标");
        check(ScopedBlockMarks.parseCoordinates("minecraft:overworld 10 64 -20", false) == null, "编辑器坐标不含维度");
    }

    private void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
        checks++;
    }
}
