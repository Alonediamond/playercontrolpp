package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.record.InputRecorder;
import com.alonediamond.playercontrolpp.record.RecordingFile;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

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

    private static boolean highPrecision; // persistent across GUI opens

    private final Screen parent;
    private RecordingFile selectedRecording;
    private int leftScroll;

    private TextFieldWidget nameField;
    private TextFieldWidget playCountField;
    private boolean dirty;

    public RecordingListGui(Screen parent) {
        super(Text.of("Recording & Playback"));
        this.parent = parent;
    }

    @Override
    public void close() {
        if (dirty) RecordingManager.getInstance().saveRecordings();
        if (parent != null) MinecraftClient.getInstance().setScreen(parent);
        else super.close();
    }

    @Override
    protected void init() {
        super.init();
        this.leftScroll = 0;

        // Start/Stop Rec
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.recording.start_recording")),
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
                                StringUtils.translate("playercontrolpp.gui.recording.new_recording"),
                                highPrecision);
                        MinecraftClient.getInstance().setScreen(null); // exit all GUIs
                    }
                })
                .dimensions(LEFT_X, TOP, 90, 20)
                .build());

        // Delete
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.remove")),
                btn -> {
                    if (selectedRecording != null) {
                        RecordingManager.getInstance().removeRecording(selectedRecording);
                        selectedRecording = null;
                        dirty = true;
                        refreshFields();
                    }
                })
                .dimensions(LEFT_X + 100, TOP, 90, 20)
                .build());

        // Back
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.back")),
                btn -> close())
                .dimensions(this.width - 55, 10, 45, 20)
                .build());

        // Play - hidden until selected
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.recording.play")),
                btn -> {
                    if (selectedRecording != null) {
                        int count = 1;
                        try { count = Integer.parseInt(playCountField.getText()); }
                        catch (NumberFormatException ignored) {}
                        RecordingManager.getInstance().getPlayer().start(selectedRecording, count);
                        MinecraftClient.getInstance().setScreen(null); // exit all GUIs
                    }
                })
                .dimensions(0, 0, 45, 20)
                .build()).visible = false;

        // Stop - hidden until playback starts
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.recording.stop")),
                btn -> RecordingManager.getInstance().getPlayer().stop())
                .dimensions(0, 0, 45, 20)
                .build()).visible = false;

        nameField = new TextFieldWidget(textRenderer, FIELD_X, 0, 130, 18, Text.empty());
        nameField.setChangedListener(s -> {
            if (selectedRecording != null) { selectedRecording.setName(s); dirty = true; }
        });
        this.addSelectableChild(nameField);

        playCountField = new TextFieldWidget(textRenderer, FIELD_X, 0, 50, 18, Text.empty());
        this.addSelectableChild(playCountField);

        refreshFields();
    }

    private void refreshFields() {
        boolean hasSel = selectedRecording != null;
        nameField.setEditable(hasSel);
        playCountField.setEditable(hasSel);

        if (hasSel) {
            nameField.setText(selectedRecording.getName());
            playCountField.setText("1");
        } else {
            nameField.setText("");
            playCountField.setText("");
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.recording.title")),
                this.width / 2, 12, 0xFFFFFFFF);

        // HP warning below title (bold red)
        if (highPrecision) {
            String warn = StringUtils.translate("playercontrolpp.gui.recording.hp_warn");
            int warnW = textRenderer.getWidth(warn);
            context.drawTextWithShadow(textRenderer, Text.of(warn),
                    this.width / 2 - warnW / 2, 24, 0xFFFF5555);
        }

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
            String text = rf.getName() + (rf.isHighPrecision() ? " [HP]" : "");
            context.drawTextWithShadow(textRenderer, Text.of(text), LEFT_X + 4, y + 5, color);
        }

        // --- Right panel ---
        // Status line
        InputRecorder recorder = RecordingManager.getInstance().getRecorder();
        String status;
        int statusColor = 0xFF55FFFF;
        if (recorder.isRecording()) {
            status = StringUtils.translate("playercontrolpp.gui.recording.recording");
            if (recorder.isHighPrecision()) status += " [HP]";
        } else if (RecordingManager.getInstance().getPlayer().isPlaying()) {
            status = StringUtils.translate("playercontrolpp.gui.recording.playing");
        } else {
            status = StringUtils.translate("playercontrolpp.gui.recording.idle");
            statusColor = 0xFF888888;
        }
        context.drawTextWithShadow(textRenderer, Text.of(status), RIGHT_X + 4, TOP + 4, statusColor);

        // HP toggle button (Litematica-style, always visible, top-right)
        String hpLabel = highPrecision
                ? StringUtils.translate("playercontrolpp.gui.recording.hp_on")
                : StringUtils.translate("playercontrolpp.gui.recording.hp_off");
        int hpColor = highPrecision ? 0xFFFFFF55 : 0xFF888888;
        int hpW = textRenderer.getWidth(hpLabel);
        int hpX = this.width - hpW - 14;
        context.drawTextWithShadow(textRenderer, Text.of(hpLabel), hpX, TOP + 4, hpColor);

        // Hide Play/Stop when nothing selected
        if (selectedRecording == null) {
            String playLabel = StringUtils.translate("playercontrolpp.gui.recording.play");
            String stopLabel = StringUtils.translate("playercontrolpp.gui.recording.stop");
            for (var child : this.children()) {
                if (child instanceof ButtonWidget btn) {
                    String msg = btn.getMessage().getString();
                    if (msg.equals(playLabel) || msg.equals(stopLabel)) btn.visible = false;
                }
            }
            return;
        }

        // Show Play/Stop for selected recording
        int ry = TOP + 30;

        // Name label + field
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        nameField.setX(FIELD_X);
        nameField.setY(ry + 2);
        nameField.render(context, mouseX, mouseY, delta);
        ry += ROW_H;

        // Frame info
        String info = StringUtils.translate("playercontrolpp.gui.recording.frames") + ": " +
                selectedRecording.getFrameCount();
        if (selectedRecording.isHighPrecision()) info += "  [HP]";
        context.drawTextWithShadow(textRenderer, Text.of(info), RIGHT_X + 4, ry + 4, 0xFFCCCCCC);
        ry += ROW_H;

        // Play count label + field
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.recording.play_count") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        playCountField.setX(FIELD_X);
        playCountField.setY(ry + 2);
        playCountField.render(context, mouseX, mouseY, delta);
        ry += ROW_H + 4;

        // Play / Stop buttons
        int btnY = ry;
        String playLabel = StringUtils.translate("playercontrolpp.gui.recording.play");
        String stopLabel = StringUtils.translate("playercontrolpp.gui.recording.stop");
        for (var child : this.children()) {
            if (child instanceof ButtonWidget btn) {
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
            context.drawTextWithShadow(textRenderer,
                    Text.of("Dim: " + selectedRecording.getDimension()),
                    RIGHT_X + 4, ry + 2, 0xFF888888);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // HP toggle click
        String hpLabel = highPrecision
                ? StringUtils.translate("playercontrolpp.gui.recording.hp_on")
                : StringUtils.translate("playercontrolpp.gui.recording.hp_off");
        int hpW = textRenderer.getWidth(hpLabel);
        int hpX = this.width - hpW - 14;
        if (mouseX >= hpX && mouseY >= TOP + 4 && mouseY <= TOP + 18) {
            highPrecision = !highPrecision;
            return true;
        }

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
        return super.mouseClicked(mouseX, mouseY, button);
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

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (nameField.isFocused()) return nameField.charTyped(chr, modifiers);
        if (playCountField.isFocused()) return playCountField.charTyped(chr, modifiers);
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == InputUtil.GLFW_KEY_ESCAPE
                && !nameField.isFocused() && !playCountField.isFocused()) {
            close(); return true;
        }
        if (nameField.isFocused()) return nameField.keyPressed(keyCode, scanCode, modifiers);
        if (playCountField.isFocused()) return playCountField.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
