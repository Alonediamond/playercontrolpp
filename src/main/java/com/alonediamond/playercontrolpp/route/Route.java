package com.alonediamond.playercontrolpp.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * One patrol route: an ordered list of waypoints plus its playback options and hotkey.
 *
 * <p>Invariant: there are always at least {@link #MIN_NODES} waypoints, because the executor and
 * the editor both index the list directly. The list itself is handed out read-only and can only be
 * changed through {@link #insertNode} / {@link #removeNode}, so the invariant cannot be broken from
 * outside — previously {@code getNodes()} returned the live list and the GUI could shrink it to one
 * entry.
 */
public class Route {

    /** A route needs a start and an end. */
    public static final int MIN_NODES = 2;

    private final String id;
    private String name;
    private boolean enabled;
    private final List<RouteNode> nodes = new ArrayList<>();
    private String dimensionId;
    private double arrivalRadius;
    private int loopCount;
    private int layerIncrement;
    private boolean sprintEnabled;
    private boolean layerControlEnabled;
    private ConfigHotkey hotkey;

    public Route(String name) {
        this(UUID.randomUUID().toString(), name);
    }

    private Route(String id, String name) {
        this.id = id;
        initDefaults(name);
    }

    private void initDefaults(String name) {
        this.name = name;
        this.enabled = false;
        this.nodes.clear();
        for (int i = 0; i < MIN_NODES; i++) {
            this.nodes.add(new RouteNode());
        }
        this.dimensionId = "";
        this.arrivalRadius = 1.0;
        this.loopCount = 1;
        this.layerIncrement = 1;
        this.sprintEnabled = false;
        this.layerControlEnabled = false;
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

    /** @return the waypoints, read-only. Use {@link #insertNode} / {@link #removeNode} to edit. */
    public List<RouteNode> getNodes() { return Collections.unmodifiableList(nodes); }

    public int getNodeCount() { return nodes.size(); }

    /** @return the waypoint at {@code index}; its coordinates are mutable in place. */
    public RouteNode getNode(int index) { return nodes.get(index); }

    /**
     * Insert a waypoint.
     *
     * @return whether it was inserted; refused for an index outside the list
     */
    public boolean insertNode(int index, RouteNode node) {
        if (index < 0 || index > nodes.size()) return false;
        nodes.add(index, node);
        return true;
    }

    /**
     * Remove a waypoint.
     *
     * @return whether it was removed; refused when it would drop below {@link #MIN_NODES}, or for
     *         an index outside the list
     */
    public boolean removeNode(int index) {
        if (nodes.size() <= MIN_NODES) return false;
        if (index < 0 || index >= nodes.size()) return false;
        nodes.remove(index);
        return true;
    }

    public String getDimensionId() { return dimensionId; }
    public void setDimensionId(String dimensionId) { this.dimensionId = dimensionId; }
    public void setDimension(ResourceKey<Level> dimension) {
        this.dimensionId = dimension != null ? dimension.identifier().toString() : "";
    }

    public double getArrivalRadius() { return arrivalRadius; }
    public void setArrivalRadius(double arrivalRadius) { this.arrivalRadius = arrivalRadius; }

    public int getLoopCount() { return loopCount; }
    public void setLoopCount(int loopCount) { this.loopCount = Math.max(0, loopCount); }

    public int getLayerIncrement() { return layerIncrement; }
    public void setLayerIncrement(int layerIncrement) { this.layerIncrement = layerIncrement == 0 ? 1 : layerIncrement; }

    public boolean isSprintEnabled() { return sprintEnabled; }
    public void setSprintEnabled(boolean v) { sprintEnabled = v; }

    public boolean isLayerControlEnabled() { return layerControlEnabled; }
    public void setLayerControlEnabled(boolean v) { layerControlEnabled = v; }

    public ConfigHotkey getHotkey() { return hotkey; }

    /**
     * How many waypoint-to-waypoint legs make up a full run.
     *
     * <p>With k waypoints one forward pass is k-1 legs.
     * loopCount 1 is a single forward pass, N &gt; 1 is N round trips, and 0 means never stop.
     *
     * @return the leg count, or -1 for an infinite route
     */
    public int getTotalSegments() {
        int waypointSegments = Math.max(1, nodes.size() - 1);
        if (loopCount == 0) return -1;
        if (loopCount == 1) return waypointSegments;
        return loopCount * 2 * waypointSegments;
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
        obj.addProperty("sprintEnabled", sprintEnabled);
        obj.addProperty("layerControlEnabled", layerControlEnabled);

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

        if (obj.has("enabled")) route.setEnabled(obj.get("enabled").getAsBoolean());
        if (obj.has("dimensionId")) route.dimensionId = obj.get("dimensionId").getAsString();
        if (obj.has("arrivalRadius")) route.setArrivalRadius(obj.get("arrivalRadius").getAsDouble());
        if (obj.has("loopCount")) route.setLoopCount(obj.get("loopCount").getAsInt());
        if (obj.has("layerIncrement")) route.setLayerIncrement(obj.get("layerIncrement").getAsInt());
        if (obj.has("sprintEnabled")) route.setSprintEnabled(obj.get("sprintEnabled").getAsBoolean());
        if (obj.has("layerControlEnabled")) route.setLayerControlEnabled(obj.get("layerControlEnabled").getAsBoolean());

        if (obj.has("nodes")) {
            JsonArray nodesArr = obj.getAsJsonArray("nodes");
            route.nodes.clear();
            for (int i = 0; i < nodesArr.size(); i++) {
                route.nodes.add(RouteNode.fromJson(nodesArr.get(i).getAsJsonObject()));
            }
            // Restore the invariant if the file was hand-edited down to too few waypoints.
            while (route.nodes.size() < MIN_NODES) {
                route.nodes.add(new RouteNode());
            }
        }

        if (obj.has("hotkey")) {
            route.hotkey.setValueFromString(obj.get("hotkey").getAsString());
        }

        return route;
    }
}
