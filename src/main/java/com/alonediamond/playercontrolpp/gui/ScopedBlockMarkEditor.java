package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.compat.WorldMarkScope;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks;
import com.alonediamond.playercontrolpp.feature.automaterial.ScopedBlockMarks.Mark;
import com.google.common.collect.ImmutableList;
import fi.dy.masa.malilib.config.options.ConfigStringList;
import fi.dy.masa.malilib.gui.GuiStringListEdit;
import fi.dy.masa.malilib.gui.Message.MessageType;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** 原编辑器会直接修改字符串列表，关闭时才把这份草稿写回进入界面时的世界。 */
final class ScopedBlockMarkEditor extends GuiStringListEdit {
    private final PlayerControlppConfigGui configScreen;
    private final ScopedBlockMarks marks;
    private final WorldMarkScope scope;
    private final List<Mark> original;
    private final boolean allowDisabled;
    private final Predicate<BlockPos> verified;

    ScopedBlockMarkEditor(PlayerControlppConfigGui parent, ScopedBlockMarks marks, WorldMarkScope scope,
                          boolean allowDisabled, Predicate<BlockPos> verified) {
        super(new ConfigStringList(marks.name(), ImmutableList.of()), parent, null, parent);
        this.configScreen = parent;
        this.marks = marks;
        this.scope = scope;
        this.original = marks.entries(scope);
        this.allowDisabled = allowDisabled;
        this.verified = verified;
        getConfig().setStrings(original.stream().map(Mark::coordinates).toList());
        this.title = StringUtils.translate(allowDisabled
                ? "playercontrolpp.gui.marks.container_format" : "playercontrolpp.gui.marks.station_format");
    }

    @Override
    public void removed() {
        super.removed();
        List<String> rows = getConfig().getStrings();
        if (rows.equals(original.stream().map(Mark::coordinates).toList())) return;

        Minecraft mc = Minecraft.getInstance();
        if (!scope.equals(WorldMarkScope.current(mc)) || !original.equals(marks.entries(scope))) {
            configScreen.addMessage(MessageType.ERROR, "playercontrolpp.message.marks.editor_stale");
            return;
        }
        Predicate<BlockPos> existed = original.stream().map(Mark::pos).collect(Collectors.toSet())::contains;
        List<Mark> replacements = new ArrayList<>();
        for (String row : rows) {
            if (row.isBlank()) continue;
            Mark mark = ScopedBlockMarks.parseCoordinates(row, allowDisabled);
            if (mark == null) {
                configScreen.addMessage(MessageType.ERROR, "playercontrolpp.message.marks.bad_coordinates", row);
                return;
            }
            if (!existed.test(mark.pos()) && !verified.test(mark.pos())) {
                configScreen.addMessage(MessageType.ERROR, "playercontrolpp.message.marks.unverified_block", row);
                return;
            }
            replacements.add(mark);
        }
        marks.replace(scope, replacements);
    }
}
