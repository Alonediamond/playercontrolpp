package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.compat.DrawCtx;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.feature.automaterial.AutoMaterialGatherer;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualMaterialBatch;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualMaterialQueue;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualMaterialSearch;
import net.minecraft.client.Minecraft;
//#if MC >= 260000
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
//$$ import net.minecraft.client.gui.GuiGraphics;
//#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 仅编辑手工备货批次；开始、失败恢复和世界切换由备货主状态机接线。 */
public final class ManualMaterialQueueGui extends Screen {
    private static final int ROW = 18;
    private static final int LEFT = 10;
    private static final int LEFT_WIDTH = 128;
    private static final int RIGHT = LEFT + LEFT_WIDTH + 10;
    private static final int LIST_TOP = 90;
    private final Screen parent;
    private final ManualMaterialQueue queue = ManualMaterialQueue.getInstance();
    private final List<Candidate> catalog = new ArrayList<>();
    private List<Candidate> results = List.of();
    private ManualMaterialBatch selectedBatch;
    private String selectedItemId;
    private Candidate selectedCandidate;
    private EditBox batchName;
    private EditBox search;
    private EditBox quantity;
    private Button addBatchButton;
    private Button removeBatchButton;
    private Button upButton;
    private Button downButton;
    private Button addItemButton;
    private Button removeItemButton;
    private Button startButton;
    private Button resumeButton;
    private Button stopButton;
    private int batchScroll;
    private int resultScroll;
    private int materialScroll;
    private boolean refreshing;
    private boolean dirty;

    private record Candidate(Item item, String id, String name) {}

    public ManualMaterialQueueGui(Screen parent) {
        super(Component.translatable("playercontrolpp.gui.manual.title"));
        this.parent = parent;
        queue.load();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            String name = item.getDefaultInstance().getHoverName().getString();
            catalog.add(new Candidate(item, id, name));
        }
        catalog.sort(Comparator.comparing(Candidate::id));
        results = catalog;
    }

    @Override
    protected void init() {
        super.init();
        int rightWidth = Math.max(120, width - RIGHT - 10);
        addBatchButton = addRenderableWidget(Button.builder(label("add_batch"), b -> {
            if (!queue.isEditingAllowed()) return;
            selectedBatch = queue.addBatch("Batch " + (queue.batches().size() + 1));
            selectedItemId = null;
            dirty = true;
            refreshFields();
        }).bounds(LEFT, 33, 62, 20).build());
        removeBatchButton = addRenderableWidget(Button.builder(label("remove_batch"), b -> {
            if (selectedBatch == null || !queue.isEditingAllowed()) return;
            queue.removeBatch(selectedBatch);
            selectedBatch = null;
            selectedItemId = null;
            dirty = true;
            refreshFields();
        }).bounds(LEFT + 65, 33, 62, 20).build());
        upButton = addRenderableWidget(Button.builder(label("move_up"), b -> move(-1))
                .bounds(LEFT, 56, 62, 20).build());
        downButton = addRenderableWidget(Button.builder(label("move_down"), b -> move(1))
                .bounds(LEFT + 65, 56, 62, 20).build());
        addRenderableWidget(Button.builder(label("back"), b -> onClose())
                .bounds(width - 62, 8, 52, 20).build());
        startButton = addRenderableWidget(Button.builder(label("start"), b -> {
            if (dirty) {
                if (!queue.save()) return;
                dirty = false;
            }
            if (AutoMaterialGatherer.getInstance().startManualQueue())
                ScreenCompat.setScreen(Minecraft.getInstance(), null);
        }).bounds(LEFT, height - 29, 40, 20).build());
        resumeButton = addRenderableWidget(Button.builder(label("resume"), b -> {
            if (AutoMaterialGatherer.getInstance().resumeManualQueue())
                ScreenCompat.setScreen(Minecraft.getInstance(), null);
        }).bounds(LEFT + 43, height - 29, 40, 20).build());
        stopButton = addRenderableWidget(Button.builder(label("stop"), b -> {
            if (AutoMaterialGatherer.getInstance().isActive()) AutoMaterialGatherer.getInstance().stop();
            else queue.stop();
        })
                .bounds(LEFT + 86, height - 29, 40, 20).build());

        batchName = new EditBox(font, RIGHT + 48, 34, rightWidth - 48, 18, label("batch_name"));
        batchName.setMaxLength(80);
        batchName.setResponder(value -> {
            if (!refreshing && selectedBatch != null && queue.isEditingAllowed()) {
                selectedBatch.setName(value);
                dirty = true;
            }
        });
        addRenderableWidget(batchName);

        search = new EditBox(font, RIGHT + 48, 57, rightWidth - 48, 18, label("search"));
        search.setMaxLength(100);
        search.setResponder(value -> filter());
        addRenderableWidget(search);

        int footerY = height - 29;
        quantity = new EditBox(font, RIGHT + 48, height - 50, 72, 18, label("quantity"));
        quantity.setMaxLength(10);
        quantity.setResponder(value -> {
            if (refreshing || selectedBatch == null || selectedItemId == null || !queue.isEditingAllowed()) return;
            int amount = parseAmount(value);
            if (amount > 0) {
                selectedBatch.setAmount(selectedItemId, amount);
                dirty = true;
            }
        });
        addRenderableWidget(quantity);
        addItemButton = addRenderableWidget(Button.builder(label("add_item"), b -> addSelectedItem())
                .bounds(RIGHT, footerY, 74, 20).build());
        removeItemButton = addRenderableWidget(Button.builder(label("remove_item"), b -> {
            if (selectedBatch == null || selectedItemId == null || !queue.isEditingAllowed()) return;
            selectedBatch.removeItem(selectedItemId);
            selectedItemId = null;
            dirty = true;
            refreshFields();
        }).bounds(RIGHT + 78, footerY, 74, 20).build());
        refreshFields();
        updateActions();
    }

    private static Component label(String suffix) {
        return Component.translatable("playercontrolpp.gui.manual." + suffix);
    }

    private void move(int offset) {
        if (selectedBatch != null && queue.isEditingAllowed() && queue.moveBatch(selectedBatch, offset)) dirty = true;
    }

    private void addSelectedItem() {
        if (selectedBatch == null || selectedCandidate == null || !queue.isEditingAllowed()) return;
        int amount = parseAmount(quantity.getValue());
        if (quantity.getValue().isBlank()) amount = 64;
        if (amount <= 0) return;
        selectedBatch.setAmount(selectedCandidate.id(), amount);
        selectedItemId = selectedCandidate.id();
        dirty = true;
        refreshFields();
    }

    private static int parseAmount(String value) {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { return -1; }
    }

    private void refreshFields() {
        if (batchName == null || quantity == null) return;
        refreshing = true;
        batchName.setValue(selectedBatch == null ? "" : selectedBatch.name());
        batchName.setEditable(selectedBatch != null && queue.isEditingAllowed());
        quantity.setValue(selectedBatch == null || selectedItemId == null
                ? "" : String.valueOf(selectedBatch.amount(selectedItemId)));
        quantity.setEditable(selectedBatch != null && queue.isEditingAllowed());
        refreshing = false;
    }

    private void filter() {
        String query = search.getValue();
        if (query.isBlank()) results = catalog;
        else results = catalog.stream()
                .filter(candidate -> ManualMaterialSearch.matches(query, candidate.name(), candidate.id()))
                .toList();
        resultScroll = 0;
    }

    @Override
    public void onClose() {
        if (dirty) {
            if (!queue.save()) return;
            dirty = false;
        }
        if (parent == null) super.onClose();
        else ScreenCompat.setScreen(Minecraft.getInstance(), parent);
    }

    private void updateActions() {
        if (startButton == null) return;
        boolean editable = queue.isEditingAllowed();
        List<ManualMaterialBatch> batches = queue.batches();
        int index = selectedBatch == null ? -1 : batches.indexOf(selectedBatch);
        if (batchName != null) batchName.setEditable(editable && index >= 0);
        if (quantity != null) quantity.setEditable(editable && index >= 0);
        addBatchButton.active = editable;
        removeBatchButton.active = editable && index >= 0;
        upButton.active = editable && index > 0;
        downButton.active = editable && index >= 0 && index < batches.size() - 1;
        addItemButton.active = editable && index >= 0 && selectedCandidate != null;
        removeItemButton.active = editable && index >= 0 && selectedItemId != null;
        AutoMaterialGatherer gatherer = AutoMaterialGatherer.getInstance();
        ManualMaterialQueue.State state = queue.state();
        startButton.active = !gatherer.isActive()
                && (state == ManualMaterialQueue.State.IDLE || state == ManualMaterialQueue.State.COMPLETE)
                && batches.stream().anyMatch(batch -> !batch.isEmpty());
        resumeButton.active = !gatherer.isActive() && state == ManualMaterialQueue.State.PAUSED;
        stopButton.active = gatherer.isActive()
                || state == ManualMaterialQueue.State.PAUSED || state == ManualMaterialQueue.State.RUNNING;
    }

    //#if MC >= 260000
    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        new DrawCtx(context).fill(0, 0, width, height, 0xC0202530);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        updateActions();
        super.extractRenderState(context, mouseX, mouseY, delta);
        renderContent(new DrawCtx(context));
    }
    //#else
    //$$ @Override
    //$$ public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
    //$$     new DrawCtx(context).fill(0, 0, width, height, 0xC0202530);
    //$$ }
    //$$
    //$$ @Override
    //$$ public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
    //$$     updateActions();
    //$$     super.render(context, mouseX, mouseY, delta);
    //$$     renderContent(new DrawCtx(context));
    //$$ }
    //#endif

    private void renderContent(DrawCtx ctx) {
        ctx.centeredText(font, label("title"), width / 2, 10, 0xFFFFFFFF);
        ctx.text(font, label("batch_name"), RIGHT, 39, 0xFFDDDDDD, false);
        ctx.text(font, label("search"), RIGHT, 62, 0xFFDDDDDD, false);
        ctx.text(font, label("quantity"), RIGHT, height - 45, 0xFFDDDDDD, false);
        String hint = label("hint").getString();
        String firstLine = font.plainSubstrByWidth(hint, LEFT_WIDTH);
        String secondLine = font.plainSubstrByWidth(hint.substring(firstLine.length()), LEFT_WIDTH);
        ctx.text(font, Component.literal(firstLine), LEFT, height - 53, 0xFFAAAAAA, false);
        ctx.text(font, Component.literal(secondLine), LEFT, height - 43, 0xFFAAAAAA, false);
        int middle = Math.max(LIST_TOP + 35, (height + LIST_TOP) / 2);
        int bottom = height - 55;
        drawPanel(ctx, LEFT, LIST_TOP, LEFT_WIDTH, bottom - LIST_TOP);
        drawPanel(ctx, RIGHT, LIST_TOP, width - RIGHT - 10, middle - LIST_TOP - 3);
        drawPanel(ctx, RIGHT, middle, width - RIGHT - 10, bottom - middle);
        drawBatches(ctx, bottom);
        drawCandidates(ctx, middle);
        drawMaterials(ctx, middle, bottom);
        Component status = statusText();
        int statusColor = queue.state() == ManualMaterialQueue.State.PAUSED ? 0xFFFF7777 : 0xFFB8D8FF;
        ctx.text(font, Component.literal(font.plainSubstrByWidth(status.getString(), width - 20)),
                LEFT, 78, statusColor, false);
    }

    private Component statusText() {
        ManualMaterialQueue.State state = queue.state();
        if (state == ManualMaterialQueue.State.IDLE) return label("idle");
        if (state == ManualMaterialQueue.State.COMPLETE) return label("complete");
        String stateText = label(state == ManualMaterialQueue.State.PAUSED ? "paused" : "running").getString();
        int index = queue.activeIndex();
        if (index < 0 || index >= queue.batches().size()) return Component.literal(stateText);
        ManualMaterialBatch batch = queue.batches().get(index);
        return Component.literal(stateText + "  ").append(Component.translatable(
                "playercontrolpp.gui.manual.active_batch", index + 1, queue.batches().size(), batch.name()));
    }

    private static void drawPanel(DrawCtx ctx, int x, int y, int w, int h) {
        if (w > 0 && h > 0) ctx.fill(x, y, x + w, y + h, 0x6030333A);
    }

    private void drawBatches(DrawCtx ctx, int bottom) {
        List<ManualMaterialBatch> batches = queue.batches();
        int visible = Math.max(0, (bottom - LIST_TOP) / ROW);
        batchScroll = clamp(batchScroll, Math.max(0, batches.size() - visible));
        for (int i = batchScroll; i < Math.min(batches.size(), batchScroll + visible); i++) {
            int y = LIST_TOP + (i - batchScroll) * ROW;
            ManualMaterialBatch batch = batches.get(i);
            if (batch == selectedBatch) ctx.fill(LEFT, y, LEFT + LEFT_WIDTH, y + ROW - 1, 0x705588CC);
            String text = (i + 1) + ". " + batch.name();
            ctx.text(font, Component.literal(font.plainSubstrByWidth(text, LEFT_WIDTH - 8)),
                    LEFT + 4, y + 4, 0xFFFFFFFF, false);
        }
    }

    private void drawCandidates(DrawCtx ctx, int middle) {
        int visible = Math.max(0, (middle - LIST_TOP - 3) / ROW);
        resultScroll = clamp(resultScroll, Math.max(0, results.size() - visible));
        int panelWidth = width - RIGHT - 10;
        for (int i = resultScroll; i < Math.min(results.size(), resultScroll + visible); i++) {
            int y = LIST_TOP + (i - resultScroll) * ROW;
            Candidate candidate = results.get(i);
            if (candidate == selectedCandidate) ctx.fill(RIGHT, y, RIGHT + panelWidth, y + ROW - 1, 0x705588CC);
            ctx.item(candidate.item().getDefaultInstance(), RIGHT + 2, y + 1);
            String text = candidate.name() + "  " + candidate.id();
            ctx.text(font, Component.literal(font.plainSubstrByWidth(text, panelWidth - 26)),
                    RIGHT + 22, y + 4, 0xFFE5E5E5, false);
        }
    }

    private void drawMaterials(DrawCtx ctx, int middle, int bottom) {
        if (selectedBatch == null) return;
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(selectedBatch.amounts().entrySet());
        int visible = Math.max(0, (bottom - middle) / ROW);
        materialScroll = clamp(materialScroll, Math.max(0, entries.size() - visible));
        int panelWidth = width - RIGHT - 10;
        for (int i = materialScroll; i < Math.min(entries.size(), materialScroll + visible); i++) {
            int y = middle + (i - materialScroll) * ROW;
            Map.Entry<String, Integer> entry = entries.get(i);
            if (entry.getKey().equals(selectedItemId)) ctx.fill(RIGHT, y, RIGHT + panelWidth, y + ROW - 1, 0x705588CC);
            Item item;
            try { item = com.alonediamond.playercontrolpp.compat.RegistryCompat.item(
                    net.minecraft.resources.Identifier.parse(entry.getKey())); }
            catch (RuntimeException e) { item = null; }
            if (item != null && BuiltInRegistries.ITEM.getKey(item).toString().equals(entry.getKey()))
                ctx.item(item.getDefaultInstance(), RIGHT + 2, y + 1);
            String text = entry.getKey() + "  +" + entry.getValue();
            ctx.text(font, Component.literal(font.plainSubstrByWidth(text, panelWidth - 26)),
                    RIGHT + 22, y + 4, 0xFFAAFFAA, false);
        }
    }

    private static int clamp(int value, int max) { return Math.max(0, Math.min(value, max)); }

    //#if MC >= 12110
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

    private boolean handleClick(double x, double y, int button) {
        if (button != 0 || y < LIST_TOP || y >= height - 55) return false;
        int middle = Math.max(LIST_TOP + 35, (height + LIST_TOP) / 2);
        if (x >= LEFT && x < LEFT + LEFT_WIDTH) {
            int i = batchScroll + ((int) y - LIST_TOP) / ROW;
            if (i < queue.batches().size()) {
                selectedBatch = queue.batches().get(i);
                selectedItemId = null;
                materialScroll = 0;
                refreshFields();
            }
            return true;
        }
        if (x < RIGHT || x >= width - 10) return false;
        if (y < middle - 3) {
            int i = resultScroll + ((int) y - LIST_TOP) / ROW;
            if (i < results.size()) {
                selectedCandidate = results.get(i);
                selectedItemId = null;
                refreshFields();
            }
        } else if (y >= middle && selectedBatch != null) {
            int i = materialScroll + ((int) y - middle) / ROW;
            List<String> ids = new ArrayList<>(selectedBatch.amounts().keySet());
            if (i < ids.size()) {
                selectedItemId = ids.get(i);
                refreshFields();
            }
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        int direction = vAmount > 0 ? -1 : 1;
        int middle = Math.max(LIST_TOP + 35, (height + LIST_TOP) / 2);
        if (mouseX < RIGHT) batchScroll += direction;
        else if (mouseY < middle) resultScroll += direction;
        else materialScroll += direction;
        return true;
    }
}
