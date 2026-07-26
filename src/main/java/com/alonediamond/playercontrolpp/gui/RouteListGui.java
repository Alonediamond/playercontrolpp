package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.compat.DrawCtx;

import com.alonediamond.playercontrolpp.compat.ScreenCompat;

import com.alonediamond.playercontrolpp.route.Route;
import com.alonediamond.playercontrolpp.route.RouteManager;
import com.alonediamond.playercontrolpp.route.RouteNode;
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

import java.util.ArrayList;
import java.util.List;

public class RouteListGui extends Screen {

    private static final int TOP = 40;
    private static final int LEFT_X = 10;
    private static final int LEFT_W = 180;
    private static final int RIGHT_X = 200;
    private static final int LEFT_ITEM_H = 20;
    private static final int WPT_ROW_H = 24;
    // Waypoint field layout: only X and Z (Y is ignored)
    private static final int FIELD_X = RIGHT_X + 50;
    private static final int FIELD_W = 62;
    private static final int FIELD_GAP = 12;

    private final Screen parent;
    private Route selectedRoute;
    private int leftScroll;
    private int rightScroll;

    private EditBox nameField;
    private final List<WaypointFields> waypointFields = new ArrayList<>();
    private EditBox radiusField;
    private EditBox loopField;
    private EditBox layerIncField;
    private boolean dirty;
    private final List<WptHitArea> wptHitAreas = new ArrayList<>();

    public RouteListGui(Screen parent) {
        super(Component.nullToEmpty("Route Flow System"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (dirty) RouteManager.getInstance().saveRoutes();
        if (parent != null) {
            ScreenCompat.setScreen(Minecraft.getInstance(), parent);
        } else {
            super.onClose();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.leftScroll = 0;
        this.rightScroll = 0;
        this.waypointFields.clear();
        this.wptHitAreas.clear();

        // Left panel: add/remove
        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.add")),
                btn -> {
                    Route route = RouteManager.getInstance().addRoute(
                            StringUtils.translate("playercontrolpp.gui.route.new_route"));
                    selectedRoute = route;
                    dirty = true;
                    rebuildWaypointFields();
                    refreshFieldValues();
                })
                .bounds(LEFT_X, TOP, 85, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.remove")),
                btn -> {
                    if (selectedRoute != null) {
                        RouteManager.getInstance().removeRoute(selectedRoute);
                        selectedRoute = null;
                        dirty = true;
                        rebuildWaypointFields();
                    }
                })
                .bounds(LEFT_X + 90, TOP, 85, 20)
                .build());

        this.addRenderableWidget(Button.builder(
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.back")),
                btn -> onClose())
                .bounds(this.width - 55, 10, 45, 20)
                .build());

        // Name field
        nameField = new EditBox(font, FIELD_X, TOP, 140, 18, Component.empty());
        nameField.setResponder(s -> {
            if (selectedRoute != null) { selectedRoute.setName(s); dirty = true; }
        });
        this.addWidget(nameField);

        // Settings fields
        radiusField = new EditBox(font, FIELD_X, 0, 55, 18, Component.empty());
        radiusField.setResponder(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setArrivalRadius(Double.parseDouble(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addWidget(radiusField);

        loopField = new EditBox(font, 0, 0, 45, 18, Component.empty());
        loopField.setResponder(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setLoopCount(Integer.parseInt(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addWidget(loopField);

        layerIncField = new EditBox(font, 0, 0, 40, 18, Component.empty());
        layerIncField.setResponder(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setLayerIncrement(Integer.parseInt(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addWidget(layerIncField);

        rebuildWaypointFields();
        refreshFieldValues();
    }

    // --- Waypoint management ---

    private void rebuildWaypointFields() {
        for (WaypointFields wf : waypointFields) {
            for (EditBox tf : wf.fields) this.removeWidget(tf);
        }
        waypointFields.clear();
        if (selectedRoute == null) return;
        for (int i = 0; i < selectedRoute.getNodes().size(); i++) addWaypointRow(i);
    }

    private void addWaypointRow(int index) {
        WaypointFields wf = new WaypointFields(index);
        waypointFields.add(index, wf);
        for (EditBox tf : wf.fields) this.addWidget(tf);
    }

    private void rebuildAllWaypointRows() {
        for (WaypointFields wf : waypointFields) {
            for (EditBox tf : wf.fields) this.removeWidget(tf);
        }
        waypointFields.clear();
        if (selectedRoute != null) {
            for (int i = 0; i < selectedRoute.getNodes().size(); i++) addWaypointRow(i);
        }
    }

    private void refreshFieldValues() {
        boolean hasSel = selectedRoute != null;
        boolean showLayer = hasSel && selectedRoute.isLayerControlEnabled();
        nameField.setEditable(hasSel);
        radiusField.setEditable(hasSel);
        loopField.setEditable(hasSel);
        layerIncField.setEditable(showLayer);

        if (hasSel) {
            nameField.setValue(selectedRoute.getName());
            radiusField.setValue(String.format("%.1f", selectedRoute.getArrivalRadius()));
            loopField.setValue(String.valueOf(selectedRoute.getLoopCount()));
            layerIncField.setValue(String.valueOf(selectedRoute.getLayerIncrement()));
        } else {
            nameField.setValue("");
            radiusField.setValue("");
            loopField.setValue("");
            layerIncField.setValue("");
        }

        for (WaypointFields wf : waypointFields) {
            RouteNode node = selectedRoute.getNodes().get(wf.nodeIndex);
            wf.fields.get(0).setValue(String.format("%.1f", node.x));
            wf.fields.get(1).setValue(String.format("%.1f", node.z));
        }
    }

    private int getRightContentHeight() {
        if (selectedRoute == null) return 0;
        int n = selectedRoute.getNodes().size();
        return 26 + 18 + n * WPT_ROW_H + 22 + 46 + 24 + 14;
    }

    // --- Render ---

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

    /** Prevent double-blur crash in 1.21.10 — draw simple dark background instead */
    private void renderDimBackground(DrawCtx context) {
        context.fill(0, 0, this.width, this.height, 0x80000000);
    }

    private void renderContent(DrawCtx context, int mouseX, int mouseY, float delta) {
        context.centeredText(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.title")),
                this.width / 2, 12, 0xFFFFFFFF);

        // --- Left panel ---
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int listTop = TOP + 30;
        int maxLeftVisible = (this.height - listTop - 10) / LEFT_ITEM_H;
        if (leftScroll < 0) leftScroll = 0;

        context.fill(LEFT_X, listTop, LEFT_X + LEFT_W, listTop + maxLeftVisible * LEFT_ITEM_H, 0x20FFFFFF);

        for (int i = leftScroll; i < Math.min(routes.size(), leftScroll + maxLeftVisible); i++) {
            int y = listTop + (i - leftScroll) * LEFT_ITEM_H;
            Route route = routes.get(i);
            boolean isSelected = route == selectedRoute;
            int bg = isSelected ? 0x40FFFFFF : 0x0;
            int color = isSelected ? 0xFF55FF55 : 0xFFCCCCCC;
            context.fill(LEFT_X + 1, y, LEFT_X + LEFT_W - 1, y + LEFT_ITEM_H - 1, bg);
            context.text(font, Component.nullToEmpty(route.getName()), LEFT_X + 4, y + 5, color,true);
        }

        // --- Right panel ---
        if (selectedRoute == null) {
            context.text(font,
                    Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.no_selection")),
                    RIGHT_X + 10, TOP + 10, 0xFF888888,true);
            return;
        }

        int rightH = this.height - TOP - 10;
        int contentH = getRightContentHeight();
        int maxRightScroll = Math.max(0, contentH - rightH);
        if (rightScroll > maxRightScroll) rightScroll = maxRightScroll;
        if (rightScroll < 0) rightScroll = 0;

        int ry = TOP - rightScroll;

        // Name
        context.text(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF,true);
        nameField.setX(FIELD_X);
        nameField.setY(ry + 2);
        context.renderWidget(nameField, mouseX, mouseY, delta);
        ry += 26;

        // Waypoints header
        context.text(font,
                Component.nullToEmpty("-- " + StringUtils.translate("playercontrolpp.gui.route.waypoints") + " --"),
                RIGHT_X + 10, ry + 2, 0xFFAAAAAA,true);
        ry += 18;

        // Waypoint rows (X + Z only, no Y)
        wptHitAreas.clear();
        List<RouteNode> nodes = selectedRoute.getNodes();
        int xFieldX = FIELD_X;
        int zFieldX = FIELD_X + FIELD_W + FIELD_GAP;

        for (int i = 0; i < nodes.size(); i++) {
            String label;
            if (i == 0) label = StringUtils.translate("playercontrolpp.gui.route.node_start");
            else if (i == nodes.size() - 1) label = StringUtils.translate("playercontrolpp.gui.route.node_end");
            else label = StringUtils.translate("playercontrolpp.gui.route.node_mid") + " " + i;

            context.text(font, Component.nullToEmpty(label),
                    RIGHT_X, ry + 4, 0xFFFFFFFF,true);

            // X field
            context.text(font, Component.nullToEmpty("X:"), xFieldX - 12, ry + 4, 0xFFCCCCCC,true);
            if (i < waypointFields.size()) {
                EditBox tf = waypointFields.get(i).fields.get(0);
                tf.setX(xFieldX);
                tf.setY(ry + 2);
                context.renderWidget(tf, mouseX, mouseY, delta);
            }

            // Z field
            context.text(font, Component.nullToEmpty("Z:"), zFieldX - 12, ry + 4, 0xFFCCCCCC,true);
            if (i < waypointFields.size()) {
                EditBox tf = waypointFields.get(i).fields.get(1);
                tf.setX(zFieldX);
                tf.setY(ry + 2);
                context.renderWidget(tf, mouseX, mouseY, delta);
            }

            // [Set] button (i18n)
            String setLabel = "[" + StringUtils.translate("playercontrolpp.gui.route.set_current") + "]";
            int setBtnX = zFieldX + FIELD_W + 8;
            int setBtnW = font.width(setLabel);
            int setColor = 0xFF55FFFF;
            if (mouseX >= setBtnX && mouseX <= setBtnX + setBtnW
                    && mouseY >= ry && mouseY <= ry + WPT_ROW_H) {
                setColor = 0xFFFFFF55;
            }
            context.text(font, Component.nullToEmpty(setLabel), setBtnX, ry + 4, setColor,true);

            // [X] button (intermediate only)
            int xBtnX = setBtnX + setBtnW + 8;
            int xBtnW = 0;
            if (i > 0 && i < nodes.size() - 1) {
                xBtnW = font.width("[X]");
                int xColor = 0xFFFF5555;
                if (mouseX >= xBtnX && mouseX <= xBtnX + xBtnW
                        && mouseY >= ry && mouseY <= ry + WPT_ROW_H) {
                    xColor = 0xFFFFFF55;
                }
                context.text(font, Component.nullToEmpty("[X]"), xBtnX, ry + 4, xColor,true);
            }

            wptHitAreas.add(new WptHitArea(ry, setBtnX, setBtnW, xBtnX, xBtnW, i));
            ry += WPT_ROW_H;
        }

        // [+ Add Node] button
        String addLabel = "[+ " + StringUtils.translate("playercontrolpp.gui.route.add_node") + "]";
        int addBtnW = font.width(addLabel);
        int addBtnX = zFieldX + FIELD_W + 10;
        int addBtnY = ry + 2;
        int addColor = 0xFF55FF55;
        if (mouseX >= addBtnX && mouseX <= addBtnX + addBtnW
                && mouseY >= addBtnY - 2 && mouseY <= addBtnY + 14) {
            addColor = 0xFFFFFF55;
        }
        context.text(font, Component.nullToEmpty(addLabel), addBtnX, addBtnY, addColor,true);
        wptHitAreas.add(new WptHitArea(addBtnX, addBtnY, addBtnW, -1, 0, 0, -1));
        ry += 22;

        // Settings row 1: arrival radius + loop count
        context.text(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.arrival_radius") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF,true);
        radiusField.setX(FIELD_X);
        radiusField.setY(ry + 2);
        context.renderWidget(radiusField, mouseX, mouseY, delta);

        int lcLabelX = FIELD_X + 70;
        context.text(font,
                Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.loop_count") + ":"),
                lcLabelX, ry + 4, 0xFFFFFFFF,true);
        loopField.setX(lcLabelX + 48);
        loopField.setY(ry + 2);
        context.renderWidget(loopField, mouseX, mouseY, delta);
        ry += 24;

        // Settings row 2: layer increment (only shown when LayerCtrl is ON)
        if (selectedRoute.isLayerControlEnabled()) {
            context.text(font,
                    Component.nullToEmpty(StringUtils.translate("playercontrolpp.gui.route.layer_increment") + ":"),
                    RIGHT_X, ry + 4, 0xFFFFFFFF,true);
            layerIncField.setX(FIELD_X);
            layerIncField.setY(ry + 2);
            context.renderWidget(layerIncField, mouseX, mouseY, delta);
            ry += 24;
        }

        // Settings row 3: Sprint + LayerCtrl toggles
        int toggleY = ry + 4;

        String sprintLabel = "[" + StringUtils.translate("playercontrolpp.gui.route.sprint") + ": "
                + (selectedRoute.isSprintEnabled()
                    ? StringUtils.translate("playercontrolpp.gui.route.on")
                    : StringUtils.translate("playercontrolpp.gui.route.nullToEmptyf")) + "]";
        int sprintW = font.width(sprintLabel);
        int sprintColor = selectedRoute.isSprintEnabled() ? 0xFF55FF55 : 0xFF888888;
        context.text(font, Component.nullToEmpty(sprintLabel), RIGHT_X, toggleY, sprintColor,true);

        String lcLabel = "[" + StringUtils.translate("playercontrolpp.gui.route.layerctrl") + ": "
                + (selectedRoute.isLayerControlEnabled()
                    ? StringUtils.translate("playercontrolpp.gui.route.on")
                    : StringUtils.translate("playercontrolpp.gui.route.nullToEmptyf")) + "]";
        int lcW = font.width(lcLabel);
        int lcX = RIGHT_X + sprintW + 20;
        int lcColor = selectedRoute.isLayerControlEnabled() ? 0xFF55FF55 : 0xFF888888;
        context.text(font, Component.nullToEmpty(lcLabel), lcX, toggleY, lcColor,true);

        // Record toggle hit areas
        wptHitAreas.add(new WptHitArea(ry, RIGHT_X, sprintW, lcX, lcW, -2));

        ry += 24;

        if (!selectedRoute.getDimensionId().isEmpty()) {
            context.text(font,
                    Component.nullToEmpty("Dim: " + selectedRoute.getDimensionId()),
                    RIGHT_X, ry + 2, 0xFF888888,true);
        }
    }

    // --- Mouse ---

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
        List<Route> routes = RouteManager.getInstance().getRoutes();
        int listTop = TOP + 30;
        int maxLeftVisible = (this.height - listTop - 10) / LEFT_ITEM_H;

        for (int i = leftScroll; i < Math.min(routes.size(), leftScroll + maxLeftVisible); i++) {
            int y = listTop + (i - leftScroll) * LEFT_ITEM_H;
            if (mouseX >= LEFT_X && mouseX <= LEFT_X + LEFT_W
                    && mouseY >= y && mouseY <= y + LEFT_ITEM_H) {
                selectedRoute = routes.get(i);
                rebuildWaypointFields();
                refreshFieldValues();
                return true;
            }
        }

        if (selectedRoute != null) {
            for (WptHitArea area : wptHitAreas) {
                // Sprint / LayerCtrl toggles (nodeIndex == -2)
                if (area.nodeIndex == -2) {
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        selectedRoute.setSprintEnabled(!selectedRoute.isSprintEnabled());
                        dirty = true;
                        return true;
                    }
                    if (area.xBtnW > 0 && mouseX >= area.xBtnX && mouseX <= area.xBtnX + area.xBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        selectedRoute.setLayerControlEnabled(!selectedRoute.isLayerControlEnabled());
                        dirty = true;
                        refreshFieldValues();
                        return true;
                    }
                    continue;
                }
                if (area.isAddButton) {
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y - 2 && mouseY <= area.y + 14) {
                        addWaypointAtEnd();
                        return true;
                    }
                } else {
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        var client = Minecraft.getInstance();
                        var player = client.player;
                        if (player != null) {
                            RouteNode node = selectedRoute.getNodes().get(area.nodeIndex);
                            node.x = player.getX();
                            node.y = player.getY();
                            node.z = player.getZ();
                            selectedRoute.setDimension(client.level.dimension());
                            dirty = true;
                            refreshFieldValues();
                        }
                        return true;
                    }
                    if (area.xBtnW > 0
                            && mouseX >= area.xBtnX && mouseX <= area.xBtnX + area.xBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        removeWaypoint(area.nodeIndex);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void addWaypointAtEnd() {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        int insertIdx = nodes.size() - 1;
        RouteNode newNode = new RouteNode();
        if (insertIdx > 0) {
            RouteNode cur = nodes.get(insertIdx - 1);
            RouteNode next = nodes.get(insertIdx);
            newNode.x = (cur.x + next.x) / 2.0;
            newNode.y = (cur.y + next.y) / 2.0;
            newNode.z = (cur.z + next.z) / 2.0;
        }
        nodes.add(insertIdx, newNode);
        dirty = true;
        rebuildAllWaypointRows();
        refreshFieldValues();
    }

    private void removeWaypoint(int index) {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        if (nodes.size() <= 2) return;
        if (index <= 0 || index >= nodes.size() - 1) return;
        nodes.remove(index);
        dirty = true;
        rebuildAllWaypointRows();
        refreshFieldValues();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double hAmount, double vAmount) {
        if (mouseX >= RIGHT_X) {
            int maxScroll = Math.max(0, getRightContentHeight() - (this.height - TOP - 10));
            rightScroll = Math.max(0, Math.min(rightScroll - (int) vAmount * 20, maxScroll));
        } else {
            List<Route> routes = RouteManager.getInstance().getRoutes();
            int listTop = TOP + 30;
            int maxVisible = (this.height - listTop - 10) / LEFT_ITEM_H;
            int maxScroll = Math.max(0, routes.size() - maxVisible);
            leftScroll = Math.max(0, Math.min(leftScroll - (int) vAmount, maxScroll));
        }
        return true;
    }

    // --- Keyboard ---

    /** GLFW_KEY_ESCAPE */
    private static final int KEY_ESCAPE = 256;

    /**
     * @return the text field that currently has focus, checked in the same order the
     *         per-field {@code isFocused()} chain used to use, or {@code null} if none has.
     */
    private EditBox focusedField() {
        if (nameField.isFocused()) return nameField;
        if (radiusField.isFocused()) return radiusField;
        if (loopField.isFocused()) return loopField;
        if (layerIncField.isFocused()) return layerIncField;
        for (WaypointFields wf : waypointFields) {
            for (EditBox tf : wf.fields) {
                if (tf.isFocused()) return tf;
            }
        }
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

    // --- Helpers ---

    private static class WptHitArea {
        final int y, setBtnX, setBtnW, xBtnX, xBtnW, nodeIndex;
        final boolean isAddButton;
        WptHitArea(int y, int setBtnX, int setBtnW, int xBtnX, int xBtnW, int nodeIndex) {
            this.y = y; this.setBtnX = setBtnX; this.setBtnW = setBtnW;
            this.xBtnX = xBtnX; this.xBtnW = xBtnW; this.nodeIndex = nodeIndex;
            this.isAddButton = false;
        }
        WptHitArea(int x, int y, int w, int nodeIndex, int a, int b, int c) {
            this.y = y; this.setBtnX = x; this.setBtnW = w;
            this.xBtnX = 0; this.xBtnW = 0; this.nodeIndex = -1;
            this.isAddButton = true;
        }
    }

    private class WaypointFields {
        int nodeIndex;
        List<EditBox> fields = new ArrayList<>(2);

        WaypointFields(int nodeIndex) {
            this.nodeIndex = nodeIndex;
            int xFieldX = FIELD_X;
            int zFieldX = FIELD_X + FIELD_W + FIELD_GAP;

            // X field
            EditBox xf = new EditBox(font, xFieldX, 0, FIELD_W, 18, Component.empty());
            final int idx = nodeIndex;
            xf.setResponder(s -> {
                if (selectedRoute != null && idx < selectedRoute.getNodes().size()) {
                    try { selectedRoute.getNodes().get(idx).x = Double.parseDouble(s); dirty = true; }
                    catch (NumberFormatException ignored) {}
                }
            });
            fields.add(xf);

            // Z field
            EditBox zf = new EditBox(font, zFieldX, 0, FIELD_W, 18, Component.empty());
            zf.setResponder(s -> {
                if (selectedRoute != null && idx < selectedRoute.getNodes().size()) {
                    try { selectedRoute.getNodes().get(idx).z = Double.parseDouble(s); dirty = true; }
                    catch (NumberFormatException ignored) {}
                }
            });
            fields.add(zf);
        }
    }
}
