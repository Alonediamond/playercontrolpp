package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.compat.DrawCtx;
import com.alonediamond.playercontrolpp.compat.ScreenCompat;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualMaterialBatch;
import com.alonediamond.playercontrolpp.feature.automaterial.manual.ManualRawMaterialPlan;
import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import com.alonediamond.playercontrolpp.util.ItemUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
//#if MC >= 260000
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
//$$ import net.minecraft.client.gui.GuiGraphics;
//#endif

/** 预览独立分析器生成的临时原料表；关闭界面即丢弃，不改任一模组的清单。 */
public final class ManualRawMaterialPreviewGui extends Screen {
    private final Screen parent;
    private final ManualMaterialBatch batch;
    private ManualRawMaterialPlan plan;
    private String error;
    private int scroll;

    public ManualRawMaterialPreviewGui(Screen parent, ManualMaterialBatch batch) {
        super(Component.translatable("playercontrolpp.gui.manual.raw_preview_title", batch.name()));
        this.parent = parent;
        this.batch = batch;
    }

    @Override
    protected void init() {
        super.init();
        if (plan == null && error == null) {
            try { plan = ManualRawMaterialPlan.analyze(batch); }
            catch (RuntimeException failure) { error = failure.getMessage(); }
        }
        addRenderableWidget(Button.builder(Component.translatable("playercontrolpp.gui.manual.back"), b -> onClose())
                .bounds(width - 70, 8, 60, 20).build());
    }

    @Override
    public void onClose() { ScreenCompat.setScreen(Minecraft.getInstance(), parent); }

    //#if MC >= 260000
    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        new DrawCtx(graphics).fill(0, 0, width, height, 0xE0202530);
    }
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int x, int y, float delta) {
        super.extractRenderState(graphics, x, y, delta);
        draw(new DrawCtx(graphics));
    }
    //#else
    //$$ @Override
    //$$ public void renderBackground(GuiGraphics graphics, int x, int y, float delta) {
    //$$     new DrawCtx(graphics).fill(0, 0, width, height, 0xE0202530);
    //$$ }
    //$$ @Override
    //$$ public void render(GuiGraphics graphics, int x, int y, float delta) {
    //$$     super.render(graphics, x, y, delta);
    //$$     draw(new DrawCtx(graphics));
    //$$ }
    //#endif

    private void draw(DrawCtx graphics) {
        graphics.text(font, title, 12, 14, 0xFFFFFFFF, true);
        if (error != null) {
            Component text = Component.translatable("playercontrolpp.gui.manual.raw_preview_failed", error);
            graphics.text(font, Component.literal(font.plainSubstrByWidth(text.getString(), width - 24)), 12, 40, 0xFFFF7777, false);
            return;
        }
        Component note = Component.translatable(plan.unsupportedRecipe()
                ? "playercontrolpp.gui.manual.raw_preview_unsupported" : "playercontrolpp.gui.manual.raw_preview_note");
        graphics.text(font, Component.literal(font.plainSubstrByWidth(note.getString(), width - 24)), 12, 37, 0xFFCCCCCC, false);
        int visible = Math.max(1, (height - 68) / 20);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, plan.materials().size() - visible)));
        Minecraft mc = Minecraft.getInstance();
        for (int index = scroll; index < Math.min(plan.materials().size(), scroll + visible); index++) {
            MaterialItemEntry entry = plan.materials().get(index);
            int y = 56 + (index - scroll) * 20;
            graphics.item(entry.item.getDefaultInstance(), 12, y);
            Component text = Component.translatable("playercontrolpp.gui.manual.raw_preview_row",
                    entry.item.getDefaultInstance().getHoverName(), entry.neededCount, ItemUtil.countEverywhere(mc.player, entry.item));
            graphics.text(font, Component.literal(font.plainSubstrByWidth(text.getString(), width - 46)), 34, y + 4, 0xFFFFFFFF, false);
        }
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll += vertical > 0 ? -1 : 1;
        return true;
    }
}
