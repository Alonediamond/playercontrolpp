package com.alonediamond.playercontrolpp.gui;

import com.alonediamond.playercontrolpp.route.Route;
import com.alonediamond.playercontrolpp.route.RouteManager;
import com.alonediamond.playercontrolpp.route.RouteNode;
import fi.dy.masa.malilib.util.StringUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class RouteListGui extends Screen {

    private static final int TOP = 40;
    private static final int LEFT_X = 10;
    private static final int LEFT_W = 180;
    private static final int RIGHT_X = 200;
    private static final int LEFT_ITEM_H = 20;
    private static final int WPT_ROW_H = 24;

    private final Screen parent;
    private Route selectedRoute;
    private int leftScroll;
    private int rightScroll;

    private TextFieldWidget nameField;
    private final List<WaypointFields> waypointFields = new ArrayList<>();
    private TextFieldWidget radiusField;
    private TextFieldWidget loopField;
    private boolean dirty;

    // Pre-computed hit areas for waypoint buttons (per-frame, for click detection)
    private final List<WptHitArea> wptHitAreas = new ArrayList<>();

    public RouteListGui(Screen parent) {
        super(Text.of("Route Flow System"));
        this.parent = parent;
    }

    @Override
    public void close() {
        if (dirty) {
            RouteManager.getInstance().saveRoutes();
        }
        if (parent != null) {
            MinecraftClient.getInstance().setScreen(parent);
        } else {
            super.close();
        }
    }

    @Override
    protected void init() {
        super.init();
        this.leftScroll = 0;
        this.rightScroll = 0;
        this.waypointFields.clear();
        this.wptHitAreas.clear();

        // --- Left panel buttons ---
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.add")),
                btn -> {
                    Route route = RouteManager.getInstance().addRoute(
                            StringUtils.translate("playercontrolpp.gui.route.new_route"));
                    selectedRoute = route;
                    dirty = true;
                    rebuildWaypointFields();
                    refreshFieldValues();
                })
                .dimensions(LEFT_X, TOP, 85, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.remove")),
                btn -> {
                    if (selectedRoute != null) {
                        RouteManager.getInstance().removeRoute(selectedRoute);
                        selectedRoute = null;
                        dirty = true;
                        rebuildWaypointFields();
                    }
                })
                .dimensions(LEFT_X + 90, TOP, 85, 20)
                .build());

        // --- Back button ---
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.back")),
                btn -> close())
                .dimensions(this.width - 55, 10, 45, 20)
                .build());

        // --- Name field ---
        int fieldX = RIGHT_X + 55;
        nameField = new TextFieldWidget(textRenderer, fieldX, TOP, 140, 18, Text.empty());
        nameField.setChangedListener(s -> {
            if (selectedRoute != null) { selectedRoute.setName(s); dirty = true; }
        });
        this.addSelectableChild(nameField);

        // --- Radius and Loop fields ---
        radiusField = new TextFieldWidget(textRenderer, fieldX, 0, 60, 18, Text.empty());
        radiusField.setChangedListener(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setArrivalRadius(Double.parseDouble(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addSelectableChild(radiusField);

        loopField = new TextFieldWidget(textRenderer, fieldX + 115, 0, 50, 18, Text.empty());
        loopField.setChangedListener(s -> {
            if (selectedRoute != null) try {
                selectedRoute.setLoopCount(Integer.parseInt(s)); dirty = true;
            } catch (NumberFormatException ignored) {}
        });
        this.addSelectableChild(loopField);

        rebuildWaypointFields();
        refreshFieldValues();
    }

    // --- Waypoint row management ---

    private void rebuildWaypointFields() {
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                this.remove(tf);
            }
        }
        waypointFields.clear();

        if (selectedRoute == null) return;

        for (int i = 0; i < selectedRoute.getNodes().size(); i++) {
            addWaypointRow(i);
        }
    }

    private void addWaypointRow(int index) {
        WaypointFields wf = new WaypointFields(index);
        waypointFields.add(index, wf);
        for (TextFieldWidget tf : wf.fields) {
            this.addSelectableChild(tf);
        }
    }

    private void removeWaypointRow(int index) {
        if (index < waypointFields.size()) {
            WaypointFields wf = waypointFields.get(index);
            for (TextFieldWidget tf : wf.fields) this.remove(tf);
            waypointFields.remove(index);
        }
        for (int i = 0; i < waypointFields.size(); i++) {
            waypointFields.get(i).nodeIndex = i;
        }
    }

    private void rebuildAllWaypointRows() {
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) this.remove(tf);
        }
        waypointFields.clear();
        if (selectedRoute != null) {
            for (int i = 0; i < selectedRoute.getNodes().size(); i++) {
                addWaypointRow(i);
            }
        }
    }

    // --- Field value sync ---

    private void refreshFieldValues() {
        boolean hasSel = selectedRoute != null;
        nameField.setEditable(hasSel);
        radiusField.setEditable(hasSel);
        loopField.setEditable(hasSel);

        if (hasSel) {
            nameField.setText(selectedRoute.getName());
            radiusField.setText(String.format("%.1f", selectedRoute.getArrivalRadius()));
            loopField.setText(String.valueOf(selectedRoute.getLoopCount()));
        } else {
            nameField.setText("");
            radiusField.setText("");
            loopField.setText("");
        }

        for (WaypointFields wf : waypointFields) {
            RouteNode node = selectedRoute.getNodes().get(wf.nodeIndex);
            wf.fields.get(0).setText(String.format("%.1f", node.x));
            wf.fields.get(1).setText(String.format("%.1f", node.y));
            wf.fields.get(2).setText(String.format("%.1f", node.z));
        }
    }

    private int getRightContentHeight() {
        if (selectedRoute == null) return 0;
        int n = selectedRoute.getNodes().size();
        return 26 + 18 + n * WPT_ROW_H + 22 + 46 + 14;
    }

    // --- Render ---

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);

        // Title
        context.drawCenteredTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.title")),
                this.width / 2, 12, 0xFFFFFFFF);

        // --- Left panel: route list ---
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
            context.drawTextWithShadow(textRenderer,
                    Text.of(route.getName()), LEFT_X + 4, y + 5, color);
        }

        // --- Right panel ---
        if (selectedRoute == null) {
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.no_selection")),
                    RIGHT_X + 10, TOP + 10, 0xFF888888);
            return;
        }

        int rightH = this.height - TOP - 10;
        int contentH = getRightContentHeight();
        int maxRightScroll = Math.max(0, contentH - rightH);
        if (rightScroll > maxRightScroll) rightScroll = maxRightScroll;
        if (rightScroll < 0) rightScroll = 0;

        int ry = TOP - rightScroll;
        int fieldX = RIGHT_X + 55;

        // --- Name row ---
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        nameField.setX(fieldX);
        nameField.setY(ry + 2);
        nameField.render(context, mouseX, mouseY, delta);
        ry += 26;

        // --- Waypoints header ---
        context.drawTextWithShadow(textRenderer,
                Text.of("-- " + StringUtils.translate("playercontrolpp.gui.route.waypoints") + " --"),
                RIGHT_X + 10, ry + 2, 0xFFAAAAAA);
        ry += 18;

        // --- Waypoint rows ---
        wptHitAreas.clear();
        List<RouteNode> nodes = selectedRoute.getNodes();

        for (int i = 0; i < nodes.size(); i++) {
            RouteNode node = nodes.get(i);
            String label;
            if (i == 0) label = StringUtils.translate("playercontrolpp.gui.route.node_start");
            else if (i == nodes.size() - 1) label = StringUtils.translate("playercontrolpp.gui.route.node_end");
            else label = StringUtils.translate("playercontrolpp.gui.route.node_mid") + " " + i;

            // Label
            context.drawTextWithShadow(textRenderer, Text.of(label),
                    RIGHT_X, ry + 4, 0xFFFFFFFF);

            // X, Y, Z fields
            for (int j = 0; j < 3; j++) {
                String prefix = j == 0 ? "X:" : j == 1 ? "Y:" : "Z:";
                context.drawTextWithShadow(textRenderer, Text.of(prefix),
                        fieldX + j * 72 - 14, ry + 4, 0xFFCCCCCC);

                if (i < waypointFields.size()) {
                    TextFieldWidget tf = waypointFields.get(i).fields.get(j);
                    tf.setX(fieldX + j * 72);
                    tf.setY(ry + 2);
                    tf.render(context, mouseX, mouseY, delta);
                }
            }

            // [Set] button position
            int setBtnX = fieldX + 3 * 72 + 4;
            int setBtnW = textRenderer.getWidth("[Set]");
            int setColor = 0xFF55FFFF;
            // Highlight on hover
            if (mouseX >= setBtnX && mouseX <= setBtnX + setBtnW
                    && mouseY >= ry && mouseY <= ry + WPT_ROW_H) {
                setColor = 0xFFFFFF55;
            }
            context.drawTextWithShadow(textRenderer, Text.of("[Set]"), setBtnX, ry + 4, setColor);

            // [X] remove button (intermediate nodes only)
            int xBtnX = setBtnX + setBtnW + 8;
            int xBtnW = 0;
            if (i > 0 && i < nodes.size() - 1) {
                xBtnW = textRenderer.getWidth("[X]");
                int xColor = 0xFFFF5555;
                if (mouseX >= xBtnX && mouseX <= xBtnX + xBtnW
                        && mouseY >= ry && mouseY <= ry + WPT_ROW_H) {
                    xColor = 0xFFFFFF55;
                }
                context.drawTextWithShadow(textRenderer, Text.of("[X]"), xBtnX, ry + 4, xColor);
            }

            // Record hit area for this row
            wptHitAreas.add(new WptHitArea(ry, setBtnX, setBtnW, xBtnX, xBtnW, i));

            ry += WPT_ROW_H;
        }

        // --- Add Node button (single, below waypoints) ---
        String addLabel = "[+ " + StringUtils.translate("playercontrolpp.gui.route.add_node") + "]";
        int addBtnW = textRenderer.getWidth(addLabel);
        int addBtnX = fieldX + 3 * 72;
        int addBtnY = ry + 2;
        int addColor = 0xFF55FF55;
        if (mouseX >= addBtnX && mouseX <= addBtnX + addBtnW
                && mouseY >= addBtnY - 2 && mouseY <= addBtnY + 14) {
            addColor = 0xFFFFFF55;
        }
        context.drawTextWithShadow(textRenderer, Text.of(addLabel), addBtnX, addBtnY, addColor);
        // Record the add button hit area
        wptHitAreas.add(new WptHitArea(addBtnX, addBtnY, addBtnW, -1, 0, 0, -1));

        ry += 22;

        // --- Settings ---
        int setLabelX = RIGHT_X;
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.arrival_radius") + ":"),
                setLabelX, ry + 4, 0xFFFFFFFF);
        radiusField.setX(fieldX);
        radiusField.setY(ry + 2);
        radiusField.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.loop_count") + ":"),
                fieldX + 70, ry + 4, 0xFFFFFFFF);
        loopField.setX(fieldX + 135);
        loopField.setY(ry + 2);
        loopField.render(context, mouseX, mouseY, delta);
        ry += 24;

        // --- Dimension ---
        if (!selectedRoute.getDimensionId().isEmpty()) {
            context.drawTextWithShadow(textRenderer,
                    Text.of("Dim: " + selectedRoute.getDimensionId()),
                    RIGHT_X, ry + 2, 0xFF888888);
        }
    }

    // --- Mouse input ---

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Left panel route list clicks
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

        // Right panel waypoint buttons
        if (selectedRoute != null) {
            for (WptHitArea area : wptHitAreas) {
                if (area.isAddButton) {
                    // [+] Add Node button
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y - 2 && mouseY <= area.y + 14) {
                        addWaypointAtEnd();
                        return true;
                    }
                } else {
                    // [Set] button
                    if (mouseX >= area.setBtnX && mouseX <= area.setBtnX + area.setBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        var player = MinecraftClient.getInstance().player;
                        if (player != null) {
                            RouteNode node = selectedRoute.getNodes().get(area.nodeIndex);
                            node.x = player.getX();
                            node.y = player.getY();
                            node.z = player.getZ();
                            selectedRoute.setDimension(player.getWorld().getRegistryKey());
                            dirty = true;
                            refreshFieldValues();
                        }
                        return true;
                    }
                    // [X] button (only if present: xBtnW > 0)
                    if (area.xBtnW > 0
                            && mouseX >= area.xBtnX && mouseX <= area.xBtnX + area.xBtnW
                            && mouseY >= area.y && mouseY <= area.y + WPT_ROW_H) {
                        removeWaypoint(area.nodeIndex);
                        return true;
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void addWaypointAtEnd() {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        // Insert new node before the end node
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

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (nameField.isFocused()) return nameField.charTyped(chr, modifiers);
        if (radiusField.isFocused()) return radiusField.charTyped(chr, modifiers);
        if (loopField.isFocused()) return loopField.charTyped(chr, modifiers);
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                if (tf.isFocused()) return tf.charTyped(chr, modifiers);
            }
        }
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC closes the GUI when no text field is focused
        if (keyCode == 256 && !nameField.isFocused() && !radiusField.isFocused()
                && !loopField.isFocused()) {
            boolean anyWptFocused = false;
            for (WaypointFields wf : waypointFields) {
                for (TextFieldWidget tf : wf.fields) {
                    if (tf.isFocused()) { anyWptFocused = true; break; }
                }
                if (anyWptFocused) break;
            }
            if (!anyWptFocused) { close(); return true; }
        }

        if (nameField.isFocused()) return nameField.keyPressed(keyCode, scanCode, modifiers);
        if (radiusField.isFocused()) return radiusField.keyPressed(keyCode, scanCode, modifiers);
        if (loopField.isFocused()) return loopField.keyPressed(keyCode, scanCode, modifiers);
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                if (tf.isFocused()) return tf.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- Helper classes ---

    /** Records the clickable hit area for a waypoint row's buttons. */
    private static class WptHitArea {
        final int y;
        final int setBtnX, setBtnW;
        final int xBtnX, xBtnW;  // [X] remove button (0 width if hidden)
        final int nodeIndex;
        final boolean isAddButton;

        // Waypoint row hit area
        WptHitArea(int y, int setBtnX, int setBtnW, int xBtnX, int xBtnW, int nodeIndex) {
            this.y = y;
            this.setBtnX = setBtnX;
            this.setBtnW = setBtnW;
            this.xBtnX = xBtnX;
            this.xBtnW = xBtnW;
            this.nodeIndex = nodeIndex;
            this.isAddButton = false;
        }

        // Add button hit area
        WptHitArea(int x, int y, int w, int nodeIndex, int dummy, int dummy2, int dummy3) {
            this.y = y;
            this.setBtnX = x;
            this.setBtnW = w;
            this.xBtnX = 0;
            this.xBtnW = 0;
            this.nodeIndex = -1;
            this.isAddButton = true;
        }
    }

    private class WaypointFields {
        int nodeIndex;
        List<TextFieldWidget> fields = new ArrayList<>(3);

        WaypointFields(int nodeIndex) {
            this.nodeIndex = nodeIndex;
            int fieldX = RIGHT_X + 55;
            for (int j = 0; j < 3; j++) {
                TextFieldWidget tf = new TextFieldWidget(textRenderer,
                        fieldX + j * 72, 0, 52, 18, Text.empty());
                final int nodeIdx = nodeIndex;
                final int coord = j;
                tf.setChangedListener(s -> {
                    if (selectedRoute != null && nodeIdx < selectedRoute.getNodes().size()) {
                        RouteNode node = selectedRoute.getNodes().get(nodeIdx);
                        try {
                            double v = Double.parseDouble(s);
                            if (coord == 0) node.x = v;
                            else if (coord == 1) node.y = v;
                            else node.z = v;
                            dirty = true;
                        } catch (NumberFormatException ignored) {}
                    }
                });
                fields.add(tf);
            }
        }
    }
}
