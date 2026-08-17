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
 * 各版本 2D 绘制上下文的薄封装。
 *
 * <p>26.1 把即时绘制的 {@code GuiGraphics} 换成了记录到 render state 的
 * {@code GuiGraphicsExtractor}，同时改了方法名：
 * <table border="1">
 *   <tr><th>&le; 1.21.11 ({@code GuiGraphics})</th><th>&ge; 26.1 ({@code GuiGraphicsExtractor})</th></tr>
 *   <tr><td>{@code drawString(font, text, x, y, color, shadow)}</td><td>{@code text(font, text, x, y, color, shadow)}</td></tr>
 *   <tr><td>{@code drawCenteredString(font, text, cx, y, color)}</td><td>{@code centeredText(font, text, cx, y, color)}</td></tr>
 *   <tr><td>{@code fill(x1, y1, x2, y2, color)}</td><td>{@code fill(x1, y1, x2, y2, color)}</td></tr>
 * </table>
 *
 * <p>只暴露模组真正用到的三个操作。各界面的 render 主体保持版本无关、只收一个 {@code DrawCtx}，
 * 需要 {@code //#if} 的仅剩被重写的入口方法。
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

    /** 用 ARGB 颜色填充矩形 {@code [x1,y1)-(x2,y2)}。 */
    public void fill(int x1, int y1, int x2, int y2, int color) {
        this.delegate.fill(x1, y1, x2, y2, color);
    }

    /** 画文本，左边缘对齐 {@code x}。 */
    public void text(Font font, Component text, int x, int y, int color, boolean shadow) {
        //#if MC >= 260000
        this.delegate.text(font, text, x, y, color, shadow);
        //#else
        //$$ this.delegate.drawString(font, text, x, y, color, shadow);
        //#endif
    }

    /** 画文本，水平居中于 {@code centerX}，始终带阴影。 */
    public void centeredText(Font font, Component text, int centerX, int y, int color) {
        //#if MC >= 260000
        this.delegate.centeredText(font, text, centerX, y, color);
        //#else
        //$$ this.delegate.drawCenteredString(font, text, centerX, y, color);
        //#endif
    }

    /**
     * 画需要界面自己管的控件（模组的 {@code EditBox} 是用 {@code addWidget} 加的，得自己画）。
     *
     * <p>{@code AbstractWidget.render} 在 26.1 改名为 {@code extractRenderState}。
     */
    public void renderWidget(AbstractWidget widget, int mouseX, int mouseY, float delta) {
        //#if MC >= 260000
        widget.extractRenderState(this.delegate, mouseX, mouseY, delta);
        //#else
        //$$ widget.render(this.delegate, mouseX, mouseY, delta);
        //#endif
    }
}
