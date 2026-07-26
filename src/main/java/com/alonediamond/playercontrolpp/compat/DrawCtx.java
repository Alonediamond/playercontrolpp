package com.alonediamond.playercontrolpp.compat;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;

//#if MC >= 260000
import net.minecraft.client.gui.GuiGraphicsExtractor;
//#else
//$$ import net.minecraft.client.gui.GuiGraphics;
//#endif

/**
 * A thin wrapper over the per-version 2D drawing context.
 *
 * <p>Minecraft 26.1 replaced the immediate-mode {@code GuiGraphics} with
 * {@code GuiGraphicsExtractor}, which records into a render state instead of drawing
 * directly, and renamed the drawing methods:
 * <table border="1">
 *   <tr><th>&le; 1.21.11 ({@code GuiGraphics})</th><th>&ge; 26.1 ({@code GuiGraphicsExtractor})</th></tr>
 *   <tr><td>{@code drawString(font, text, x, y, color, shadow)}</td><td>{@code text(font, text, x, y, color, shadow)}</td></tr>
 *   <tr><td>{@code drawCenteredString(font, text, cx, y, color)}</td><td>{@code centeredText(font, text, cx, y, color)}</td></tr>
 *   <tr><td>{@code fill(x1, y1, x2, y2, color)}</td><td>{@code fill(x1, y1, x2, y2, color)}</td></tr>
 * </table>
 *
 * <p>Only the three operations PlayerControl++ actually uses are exposed. The screens
 * keep their version-agnostic render bodies and receive a {@code DrawCtx}; only the
 * overridden entry points need a {@code //#if}.
 */
public final class DrawCtx {

    //#if MC >= 260000
    private final GuiGraphicsExtractor delegate;

    public DrawCtx(GuiGraphicsExtractor delegate) {
        this.delegate = delegate;
    }
    //#else
    //$$ private final GuiGraphics delegate;
    //$$
    //$$ public DrawCtx(GuiGraphics delegate) {
    //$$     this.delegate = delegate;
    //$$ }
    //#endif

    /** Fills the rectangle {@code [x1,y1)-(x2,y2)} with an ARGB colour. */
    public void fill(int x1, int y1, int x2, int y2, int color) {
        this.delegate.fill(x1, y1, x2, y2, color);
    }

    /** Draws {@code text} with its left edge at {@code x}. */
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        //#if MC >= 260000
        this.delegate.text(font, text, x, y, color, shadow);
        //#else
        //$$ this.delegate.drawString(font, text, x, y, color, shadow);
        //#endif
    }

    /** Draws {@code text} horizontally centred on {@code centerX}, always with a shadow. */
    public void centeredText(Font font, Component text, int centerX, int y, int color) {
        //#if MC >= 260000
        this.delegate.centeredText(font, text, centerX, y, color);
        //#else
        //$$ this.delegate.drawCenteredString(font, text, centerX, y, color);
        //#endif
    }

    /**
     * Renders a widget the screen manages by hand (the mod's {@code EditBox}es are
     * added via {@code addWidget}, so the screen has to draw them itself).
     *
     * <p>{@code AbstractWidget.render} became {@code extractRenderState} in 26.1.
     */
    public void renderWidget(AbstractWidget widget, int mouseX, int mouseY, float delta) {
        //#if MC >= 260000
        widget.extractRenderState(this.delegate, mouseX, mouseY, delta);
        //#else
        //$$ widget.render(this.delegate, mouseX, mouseY, delta);
        //#endif
    }
}
