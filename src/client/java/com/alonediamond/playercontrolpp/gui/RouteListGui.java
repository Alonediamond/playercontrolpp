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
    private static final int ROW_H = 22;
    private static final int LEFT_ITEM_H = 20;

    private final Screen parent;
    private Route selectedRoute;
    private int leftScroll;
    private int rightScroll;

    // Route name field
    private TextFieldWidget nameField;

    // Per-waypoint fields
    private final List<WaypointFields> waypointFields = new ArrayList<>();

    // Settings fields
    private TextFieldWidget radiusField;
    private TextFieldWidget loopField;
    private boolean dirty;

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

        int rightW = Math.max(200, this.width - RIGHT_X - 10);

        // --- Left panel buttons ---
        this.addDrawableChild(ButtonWidget.builder(
                Text.of(StringUtils.translate("playercontrolpp.gui.route.add")),
                btn -> {
                    Route route = RouteManager.getInstance().addRoute(
                            StringUtils.translate("playercontrolpp.gui.route.new_route"));
                    selectedRoute = route;
                    dirty = true;
                    rebuildWaypointFields();
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
        int fieldX = RIGHT_X + 50;
        nameField = new TextFieldWidget(textRenderer, fieldX, TOP + 2, 140, 18, Text.empty());
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

        loopField = new TextFieldWidget(textRenderer, fieldX + 90, 0, 50, 18, Text.empty());
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
        // Remove old waypoint children from screen
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                this.remove(tf);
            }
            for (ButtonWidget btn : wf.buttons) {
                this.remove(btn);
            }
        }
        waypointFields.clear();

        if (selectedRoute == null) return;

        List<RouteNode> nodes = selectedRoute.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            addWaypointRow(i);
        }
    }

    private void addWaypointRow(int index) {
        WaypointFields wf = new WaypointFields(index);
        waypointFields.add(index, wf);

        for (TextFieldWidget tf : wf.fields) {
            this.addSelectableChild(tf);
        }
        for (ButtonWidget btn : wf.buttons) {
            this.addDrawableChild(btn);
        }
    }

    private void removeWaypointRow(int index) {
        if (index < waypointFields.size()) {
            WaypointFields wf = waypointFields.get(index);
            for (TextFieldWidget tf : wf.fields) {
                this.remove(tf);
            }
            for (ButtonWidget btn : wf.buttons) {
                this.remove(btn);
            }
            waypointFields.remove(index);
        }
        // Re-index remaining rows
        for (int i = 0; i < waypointFields.size(); i++) {
            waypointFields.get(i).nodeIndex = i;
        }
    }

    private void rebuildAllWaypointRows() {
        // Rebuild all waypoint rows with updated positions
        for (WaypointFields wf : waypointFields) {
            for (TextFieldWidget tf : wf.fields) {
                this.remove(tf);
            }
            for (ButtonWidget btn : wf.buttons) {
                this.remove(btn);
            }
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

        // Refresh waypoint field values
        for (WaypointFields wf : waypointFields) {
            RouteNode node = selectedRoute.getNodes().get(wf.nodeIndex);
            wf.fields.get(0).setText(String.format("%.2f", node.x));
            wf.fields.get(1).setText(String.format("%.2f", node.y));
            wf.fields.get(2).setText(String.format("%.2f", node.z));
        }
    }

    // --- Render ---

    private int getRightContentHeight() {
        if (selectedRoute == null) return 0;
        // Name row + waypoints (each with + button) + gap + settings row
        int n = selectedRoute.getNodes().size();
        return 30 + n * 42 + 30 + 30;
    }

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

        // Draw left panel background
        context.fill(LEFT_X, listTop, LEFT_X + LEFT_W, listTop + maxLeftVisible * LEFT_ITEM_H, 0x20FFFFFF);

        for (int i = leftScroll; i < Math.min(routes.size(), leftScroll + maxLeftVisible); i++) {
            int y = listTop + (i - leftScroll) * LEFT_ITEM_H;
            Route route = routes.get(i);
            boolean isSelected = route == selectedRoute;
            int bg = isSelected ? 0x40FFFFFF : 0x0;
            int color = isSelected ? 0xFF55FF55 : 0xFFCCCCCC;
            context.fill(LEFT_X + 1, y, LEFT_X + LEFT_W - 1, y + LEFT_ITEM_H - 1, bg);
            context.drawTextWithShadow(textRenderer,
                    Text.of(route.getName()),
                    LEFT_X + 4, y + 5, color);
        }

        // --- Right panel ---
        if (selectedRoute == null) {
            context.drawTextWithShadow(textRenderer,
                    Text.of(StringUtils.translate("playercontrolpp.gui.route.no_selection")),
                    RIGHT_X + 10, TOP + 10, 0xFF888888);
            return;
        }

        int rightW = Math.max(200, this.width - RIGHT_X - 10);
        int rightH = this.height - TOP - 10;
        int contentH = getRightContentHeight();
        int maxRightScroll = Math.max(0, contentH - rightH);
        if (rightScroll > maxRightScroll) rightScroll = maxRightScroll;
        if (rightScroll < 0) rightScroll = 0;

        int ry = TOP - rightScroll;
        int fieldX = RIGHT_X + 55;
        int fieldW = 55;

        // Name
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.name") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        nameField.setX(fieldX);
        nameField.setY(ry + 2);
        nameField.render(context, mouseX, mouseY, delta);

        ry += 26;

        // Waypoints header
        context.drawTextWithShadow(textRenderer,
                Text.of("-- " + StringUtils.translate("playercontrolpp.gui.route.waypoints") + " --"),
                RIGHT_X + 10, ry + 2, 0xFFAAAAAA);
        ry += 18;

        // Waypoint rows
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

            ry += 22;

            // + button (add node after this one)
            int plusBtnX = fieldX + 3 * 72 + 6;
            context.drawTextWithShadow(textRenderer, Text.of("[+]"), plusBtnX, ry - 20, 0xFF55FF55);

            // Remove button (only for intermediate nodes)
            if (i > 0 && i < nodes.size() - 1) {
                context.drawTextWithShadow(textRenderer, Text.of("[X]"), plusBtnX + 28, ry - 20, 0xFFFF5555);
            }

            // Set Current Position button
            context.drawTextWithShadow(textRenderer,
                    Text.of("[" + StringUtils.translate("playercontrolpp.gui.route.set_current") + "]"),
                    plusBtnX + 50, ry - 20, 0xFF55FFFF);
        }

        // ry is now after the last waypoint's + button line
        ry += 8;

        // Settings
        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.arrival_radius") + ":"),
                RIGHT_X, ry + 4, 0xFFFFFFFF);
        radiusField.setX(fieldX);
        radiusField.setY(ry + 2);
        radiusField.render(context, mouseX, mouseY, delta);

        context.drawTextWithShadow(textRenderer,
                Text.of(StringUtils.translate("playercontrolpp.gui.route.loop_count") + ":"),
                fieldX + 75, ry + 4, 0xFFFFFFFF);
        loopField.setX(fieldX + 140);
        loopField.setY(ry + 2);
        loopField.render(context, mouseX, mouseY, delta);

        ry += 24;

        // Dimension info
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

        // Right panel waypoint buttons (only if route selected)
        if (selectedRoute != null) {
            List<RouteNode> nodes = selectedRoute.getNodes();
            int ry = TOP - rightScroll + 26 + 18; // after name + header

            for (int i = 0; i < nodes.size(); i++) {
                int rowY = ry + i * 42;
                int plusBtnX = RIGHT_X + 55 + 3 * 72 + 6;

                // [+] button
                if (mouseX >= plusBtnX && mouseX <= plusBtnX + 22
                        && mouseY >= rowY && mouseY <= rowY + 20) {
                    addWaypointAfter(i);
                    return true;
                }

                // [X] button (intermediate nodes only)
                if (i > 0 && i < nodes.size() - 1) {
                    if (mouseX >= plusBtnX + 28 && mouseX <= plusBtnX + 48
                            && mouseY >= rowY && mouseY <= rowY + 20) {
                        removeWaypoint(i);
                        return true;
                    }
                }

                // [Set Current] button
                if (mouseX >= plusBtnX + 50 && mouseX <= plusBtnX + 100
                        && mouseY >= rowY && mouseY <= rowY + 20) {
                    var player = MinecraftClient.getInstance().player;
                    if (player != null) {
                        RouteNode node = nodes.get(i);
                        node.x = player.getX();
                        node.y = player.getY();
                        node.z = player.getZ();
                        selectedRoute.setDimension(player.getWorld().getRegistryKey());
                        dirty = true;
                        refreshFieldValues();
                    }
                    return true;
                }

                rowY += 22; // skip the + button line for next check
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void addWaypointAfter(int index) {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        // Insert a new node after this one
        RouteNode newNode = new RouteNode();
        if (index + 1 < nodes.size()) {
            // Average between current and next
            RouteNode cur = nodes.get(index);
            RouteNode next = nodes.get(index + 1);
            newNode.x = (cur.x + next.x) / 2.0;
            newNode.y = (cur.y + next.y) / 2.0;
            newNode.z = (cur.z + next.z) / 2.0;
        }
        nodes.add(index + 1, newNode);
        dirty = true;
        rebuildAllWaypointRows();
        refreshFieldValues();
    }

    private void removeWaypoint(int index) {
        if (selectedRoute == null) return;
        List<RouteNode> nodes = selectedRoute.getNodes();
        if (nodes.size() <= 2) return; // must keep at least start and end
        if (index <= 0 || index >= nodes.size() - 1) return; // can't remove start or end
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

    // --- Helper class ---

    private class WaypointFields {
        int nodeIndex;
        List<TextFieldWidget> fields = new ArrayList<>(3);
        List<ButtonWidget> buttons = new ArrayList<>();

        WaypointFields(int nodeIndex) {
            this.nodeIndex = nodeIndex;
            int fieldX = RIGHT_X + 55;
            int fieldW = 52;
            for (int j = 0; j < 3; j++) {
                TextFieldWidget tf = new TextFieldWidget(textRenderer,
                        fieldX + j * 72, 0, fieldW, 18, Text.empty());
                final int nodeIdx = nodeIndex;
                final int coord = j; // 0=x, 1=y, 2=z
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
