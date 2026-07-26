package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.compat.DrawCtx;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.record.InputRecorder;
import com.alonediamond.playercontrolpp.record.RecordingFile;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.Minecraft;
//#if MC >= 260000
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
//$$ import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import net.minecraft.network.chat.Component;

import java.util.List;

public class RecordingListGui extends Screen {

    private static final int TOP = 40;
    private static final int LEFT_X = 10;
    private static final int LEFT_W = 200;
    private static final int RIGHT_X = 220;
    private static final int LABEL_W = 58;
    private static final int FIELD_X = RIGHT_X + LABEL_W + 4;
    private static final int ITEM_H = 20;
    private static final int ROW_H = 24;

    private final Screen parent;
    private RecordingFile selectedRecording;
    private int leftScroll;

    private EditBox nameField;
    private EditBox playCountField;
    private boolean dirty;

    public RecordingListGui(Screen parent) {
        super(Component.nullToEmpty("Recording & Playback"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (dirty) RecordingManager.getInstance().saveRecordings();
        if (parent != null) ScreenCompat.setScreen(Minecraft.getInstance(), parent);
        else super.onClose();
    }

    @Override
    protected void init() {
        super.init();
        this.leftScroll = 0;

        // Start/Stop Rec
        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.recording.start_recording")),
                btn -> {
                    InputRecorder rec = RecordingManager.getInstance().getRecorder();
                    if (rec.isRecording()) {
                        RecordingFile rf = rec.stopRecording();
                        RecordingManager.getInstance().addRecording(rf);
                        selectedRecording = rf;
                        dirty = true;
                        refreshFields();
                    } else {
                        if (RecordingManager.getInstance().getPlayer().isPlaying()) return;
                        rec.startRecording(
                                StringUtils.translate("playercontrolpp.gui.recording.new_recording"));
                        ScreenCompat.setScreen(Minecraft.getInstance(), null); // exit all GUIs
                    }
                })
                .bounds(LEFT_X, TOP, 90, 20)
                .build());

        // Delete
        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.remove")),
                btn -> {
                    if (selectedRecording != null) {
                        RecordingManager.getInstance().removeRecording(selectedRecording);
                        selectedRecording = null;
                        dirty = true;
                        refreshFields();
                    }
                })
                .bounds(LEFT_X + 100, TOP, 90, 20)
                .build());

        // Back
        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.back")),
                btn -> onClose())
                .bounds(this.width - 55, 10, 45, 20)
                .build());

        // Play - hidden until selected
        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.recording.play")),
                btn -> {
                    if (selectedRecording != null) {
                        int count = 1;
                        try { count = Integer.parseInt(playCountField.getValue()); }
                        catch (NumberFormatException ignored) {}
                        RecordingManager.getInstance().getPlayer().start(selectedRecording, count);
                        ScreenCompat.setScreen(Minecraft.getInstance(), null); // exit all GUIs
                    }
                })
                .bounds(0, 0, 45, 20)
                .build()).visible = false;

        // Stop - hidden until playback starts
        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.recording.stop")),
                btn -> RecordingManager.getInstance().getPlayer().stop())
                .bounds(0, 0, 45, 20)
                .build()).visible = false;

        nameField = new EditBox(font, FIELD_X, 0, 130, 18, Component.empty());
        nameField.setResponder(s -> {
            if (selectedRecording != null) { selectedRecording.setName(s); dirty = true; }
        });
        this.addWidget(nameField);

        playCountField = new EditBox(font, FIELD_X, 0, 50, 18, Component.empty());
        this.addWidget(playCountField);

        refreshFields();
    }

    private void refreshFields() {
        boolean hasSel = selectedRecording != null;
        nameField.setEditable(hasSel);
        playCountField.setEditable(hasSel);

        if (hasSel) {
            nameField.setValue(selectedRecording.getName());
            playCountField.setValue("1");
        } else {
            nameField.setValue("");
            playCountField.setValue("");
        }
    }

    //#if MC >= 260000
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        renderDimBackground(new DrawCtx(context));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        renderContent(new DrawCtx(context), mouseX, mouseY, delta);
    }
    //#else
    //$$ @Override
    //$$ public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
    //$$     renderDimBackground(new DrawCtx(context));
    //$$ }
    //$$
    //$$ @Override
    //$$ public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
    //$$     super.render(context, mouseX, mouseY, delta);
    //$$     renderContent(new DrawCtx(context), mouseX, mouseY, delta);
    //$$ }
    //#endif

    /** Prevent double-blur crash in 1.21.11 — draw simple dark background instead */
    private void renderDimBackground(DrawCtx context) {
        context.fill(0, 0, this.width, this.height, 0x80000000);
    }

    private void renderContent(DrawCtx context, int mouseX, int mouseY, float delta) {
        // Title
        context.text(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.recording.title")),
                this.width / 2, 12, 0xFFFFFFFF,true);

        // --- Left panel ---
        List<RecordingFile> recs = RecordingManager.getInstance().getRecordings();
        int listTop = TOP + 30;
        int maxVisible = (this.height - listTop - 10) / ITEM_H;
        if (leftScroll < 0) leftScroll = 0;

        context.fill(LEFT_X, listTop, LEFT_X + LEFT_W, listTop + maxVisible * ITEM_H, 0x20FFFFFF);

        for (int i = leftScroll; i < Math.min(recs.size(), leftScroll + maxVisible); i++) {
            int y = listTop + (i - leftScroll) * ITEM_H;
            RecordingFile rf = recs.get(i);
            boolean isSel = rf == selectedRecording;
            int bg = isSel ? 0x40FFFFFF : 0x0;
            int color = isSel ? 0xFF55FF55 : 0xFFCCCCCC;
            context.fill(LEFT_X + 1, y, LEFT_X + LEFT_W - 1, y + ITEM_H - 1, bg);
            String text = rf.getName();
            context.text(font, Component.nullToEmpty(text), LEFT_X + 4, y + 5, color,true);
        }

        // --- Right panel ---
        // Status line
        InputRecorder recorder = RecordingManager.getInstance().getRecorder();
        String status;
        int statusColor = 0xFF55FFFF;
        if (recorder.isRecording()) {
            status = StringUtils.translate("playercontrolpp.gui.recording.recording");
        } else if (RecordingManager.getInstance().getPlayer().isPlaying()) {
            status = StringUtils.translate("playercontrolpp.gui.recording.playing");
        } else {
            status = StringUtils.translate("playercontrolpp.gui.recording.idle");
            statusColor = 0xFF888888;
        }
        context.text(font, Component.nullToEmpty(status), RIGHT_X + 4, TOP + 4, statusColor,true);

        // Hide Play/Stop when nothing selected
        if (selectedRecording == null) {
            String playLabel = StringUtils.translate("playercontrolpp.gui.recording.play");
            String stopLabel = StringUtils.translate("playercontrolpp.gui.recording.stop");
            for (var child : this.children()) {
                if (child instanceof Button btn) {
                    String msg = btn.getMessage().getString();
                    if (msg.equals(playLabel) || msg.equals(stopLabel)) btn.visible = false;
                }
            }
            return;
        }

        // Show Play/Stop for selected recording
        int ry = TOP + 30;

        // Name label + field
        context.text(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF,true);
        nameField.setX(FIELD_X);
        nameField.setY(ry + 2);
        context.renderWidget(nameField, mouseX, mouseY, delta);
        ry += ROW_H;

        // Duration info
        int durationSecs = selectedRecording.getDurationTicks() / 20;
        String info = StringUtils.translate("playercontrolpp.gui.recording.frames") + ": " +
                durationSecs + "s  (" + selectedRecording.getDurationTicks() + " ticks)";
        context.text(font, Component.nullToEmpty(info), RIGHT_X + 4, ry + 4, 0xFFCCCCCC,true);
        ry += ROW_H;

        // Play count label + field
        context.text(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.recording.play_count") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF,true);
        playCountField.setX(FIELD_X);
        playCountField.setY(ry + 2);
        context.renderWidget(playCountField, mouseX, mouseY, delta);
        ry += ROW_H + 4;

        // Play / Stop buttons
        int btnY = ry;
        String playLabel = StringUtils.translate("playercontrolpp.gui.recording.play");
        String stopLabel = StringUtils.translate("playercontrolpp.gui.recording.stop");
        for (var child : this.children()) {
            if (child instanceof Button btn) {
                String msg = btn.getMessage().getString();
                if (msg.equals(playLabel)) {
                    btn.setX(FIELD_X); btn.setY(btnY); btn.visible = true;
                } else if (msg.equals(stopLabel)) {
                    btn.setX(FIELD_X + 55); btn.setY(btnY); btn.visible = true;
                }
            }
        }
        ry += ROW_H;

        // Dimension
        if (!selectedRecording.getDimension().isEmpty()) {
            context.text(font,
                    Component.nullToEmpty("Dim: " + selectedRecording.getDimension()),
                    RIGHT_X + 4, ry + 2, 0xFF888888,true);
        }
    }

    //#if MC >= 12111
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent click, boolean isDragging) {
        if (handleClick(click.x(), click.y(), click.button())) return true;
        return super.mouseClicked(click, isDragging);
    }
    //#else
    //$$ @Override
    //$$ public boolean mouseClicked(double mouseX, double mouseY, int button) {
    //$$     if (handleClick(mouseX, mouseY, button)) return true;
    //$$     return super.mouseClicked(mouseX, mouseY, button);
    //$$ }
    //#endif

    /** Version-agnostic click handling. @return {@code true} when the click was consumed. */
    private boolean handleClick(double mouseX, double mouseY, int button) {
        // Left panel clicks
        List<RecordingFile> recs = RecordingManager.getInstance().getRecordings();
        int listTop = TOP + 30;
        int maxVisible = (this.height - listTop - 10) / ITEM_H;

        for (int i = leftScroll; i < Math.min(recs.size(), leftScroll + maxVisible); i++) {
            int y = listTop + (i - leftScroll) * ITEM_H;
            if (mouseX >= LEFT_X && mouseX <= LEFT_X + LEFT_W
                    && mouseY >= y && mouseY <= y + ITEM_H) {
                selectedRecording = recs.get(i);
                refreshFields();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        List<RecordingFile> recs = RecordingManager.getInstance().getRecordings();
        int listTop = TOP + 30;
        int maxVisible = (this.height - listTop - 10) / ITEM_H;
        leftScroll = Math.max(0, Math.min(leftScroll - (int) vAmount,
                Math.max(0, recs.size() - maxVisible)));
        return true;
    }

    /** GLFW_KEY_ESCAPE */
    private static final int KEY_ESCAPE = 256;

    /**
     * @return the text field that currently has focus, checked in the same order the
     *         per-field {@code isFocused()} chain used to use, or {@code null} if none has.
     */
    private EditBox focusedField() {
        if (nameField.isFocused()) return nameField;
        if (playCountField.isFocused()) return playCountField;
        return null;
    }

    //#if MC >= 12111
    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
        EditBox focused = focusedField();
        return focused != null ? focused.charTyped(input) : super.charTyped(input);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent input) {
        EditBox focused = focusedField();
        if (input.key() == KEY_ESCAPE && focused == null) {
            onClose();
            return true;
        }
        return focused != null ? focused.keyPressed(input) : super.keyPressed(input);
    }
    //#else
    //$$ @Override
    //$$ public boolean charTyped(char chr, int modifiers) {
    //$$     EditBox focused = focusedField();
    //$$     return focused != null ? focused.charTyped(chr, modifiers) : super.charTyped(chr, modifiers);
    //$$ }
    //$$
    //$$ @Override
    //$$ public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
    //$$     EditBox focused = focusedField();
    //$$     if (keyCode == KEY_ESCAPE && focused == null) {
    //$$         onClose();
    //$$         return true;
    //$$     }
    //$$     return focused != null
    //$$             ? focused.keyPressed(keyCode, scanCode, modifiers)
    //$$             : super.keyPressed(keyCode, scanCode, modifiers);
    //$$ }
    //#endif
}
