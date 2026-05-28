package com.alonediamond.playercontrolpp.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Route {
    private String id;
    private String name;
    private boolean enabled;
    private final List<RouteNode> nodes = new ArrayList<>();
    private String dimensionId;
    private double arrivalRadius;
    private int loopCount;
    private int layerIncrement;
    private ConfigHotkey hotkey;

    public Route(String name) {
        this.id = UUID.randomUUID().toString();
        initDefaults(name);
    }

    private Route(String id, String name) {
        this.id = id;
        initDefaults(name);
    }

    private void initDefaults(String name) {
        this.name = name;
        this.enabled = false;
        this.nodes.clear();
        this.nodes.add(new RouteNode());
        this.nodes.add(new RouteNode());
        this.dimensionId = "";
        this.arrivalRadius = 1.0;
        this.loopCount = 1;
        this.layerIncrement = 1;
        this.hotkey = new ConfigHotkey("route_" + this.id, "",
                KeybindSettings.PRESS_ALLOWEXTRA,
                "Hotkey for route: " + name,
                "Route: " + name,
                name);
    }

    public String getId() { return id; }

    public String getName() { return name; }
    public void setName(String name) {
        this.name = name;
        this.hotkey.setPrettyName("Route: " + name);
        this.hotkey.setTranslatedName(name);
        this.hotkey.setComment("Hotkey for route: " + name);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<RouteNode> getNodes() { return nodes; }

    public RouteNode getStartPos() { return nodes.get(0); }
    public void setStartPos(double x, double y, double z) {
        nodes.get(0).x = x;
        nodes.get(0).y = y;
        nodes.get(0).z = z;
    }

    public RouteNode getEndPos() { return nodes.get(1); }
    public void setEndPos(double x, double y, double z) {
        nodes.get(1).x = x;
        nodes.get(1).y = y;
        nodes.get(1).z = z;
    }

    public String getDimensionId() { return dimensionId; }
    public void setDimensionId(String dimensionId) { this.dimensionId = dimensionId; }
    public void setDimension(RegistryKey<World> dimension) {
        this.dimensionId = dimension != null ? dimension.getValue().toString() : "";
    }

    public double getArrivalRadius() { return arrivalRadius; }
    public void setArrivalRadius(double arrivalRadius) { this.arrivalRadius = arrivalRadius; }

    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = Math.max(0, loopCount); }

    public int getLayerIncrement() { return layerIncrement; }
    public void setLayerIncrement(int layerIncrement) { this.layerIncrement = layerIncrement == 0 ? 1 : layerIncrement; }

    public ConfigHotkey getHotkey() { return hotkey; }

    /**
     * Calculate the total number of segments to traverse.
     * With k nodes: one forward traversal = k-1 segments.
     * loopCount 1 = single forward pass through all waypoints
     * loopCount N>1 = N round trips (forward + backward)
     * loopCount 0 = infinite (returns -1)
     */
    public int getTotalSegments() {
        int waypointSegments = Math.max(1, nodes.size() - 1);
        if (loopCount == 0) return -1;    // infinite
        if (loopCount == 1) return waypointSegments; // single forward pass
        return loopCount * 2 * waypointSegments;     // N round trips
    }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("name", name);
        obj.addProperty("enabled", enabled);
        obj.addProperty("dimensionId", dimensionId);
        obj.addProperty("arrivalRadius", arrivalRadius);
        obj.addProperty("loopCount", loopCount);
        obj.addProperty("layerIncrement", layerIncrement);

        JsonArray nodesArr = new JsonArray();
        for (RouteNode node : nodes) {
            nodesArr.add(node.toJson());
        }
        obj.add("nodes", nodesArr);

        obj.addProperty("hotkey", hotkey.getStringValue());
        return obj;
    }

    public static Route fromJson(JsonObject obj) {
        String routeId = obj.has("id") ? obj.get("id").getAsString() : UUID.randomUUID().toString();
        String routeName = obj.has("name") ? obj.get("name").getAsString() : "Unnamed Route";
        Route route = new Route(routeId, routeName);

        if (obj.has("name")) route.name = obj.get("name").getAsString();
        if (obj.has("enabled")) route.setEnabled(obj.get("enabled").getAsBoolean());
        if (obj.has("dimensionId")) route.dimensionId = obj.get("dimensionId").getAsString();
        if (obj.has("arrivalRadius")) route.setArrivalRadius(obj.get("arrivalRadius").getAsDouble());
        if (obj.has("loopCount")) route.setLoopCount(obj.get("loopCount").getAsInt());
        if (obj.has("layerIncrement")) route.setLayerIncrement(obj.get("layerIncrement").getAsInt());

        if (obj.has("nodes")) {
            JsonArray nodesArr = obj.getAsJsonArray("nodes");
            route.nodes.clear();
            for (int i = 0; i < nodesArr.size(); i++) {
                route.nodes.add(RouteNode.fromJson(nodesArr.get(i).getAsJsonObject()));
            }
            // Ensure at least 2 nodes
            while (route.nodes.size() < 2) {
                route.nodes.add(new RouteNode());
            }
        }

        if (obj.has("hotkey")) {
            route.hotkey.setValueFromString(obj.get("hotkey").getAsString());
        }

        return route;
    }
}
