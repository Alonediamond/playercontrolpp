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
 * 一条巡逻路径：有序的导航点列表 + 回放选项 + 热键。
 *
 * <p>不变式：导航点数量始终不少于 {@link #MIN_NODES}，因为执行器和编辑界面都直接按下标取用。
 * 列表本身以只读方式交出，只能通过 {@link #insertNode} / {@link #removeNode} 修改，
 * 所以不变式无法从外部打破——早先 {@code getNodes()} 返回的是活列表，GUI 能把它删到只剩一项。
 */
public class Route {

    /** 一条路径至少要有起点和终点。 */
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

    /** @return 导航点列表（只读）。要改请用 {@link #insertNode} / {@link #removeNode}。 */
    public List<RouteNode> getNodes() { return Collections.unmodifiableList(nodes); }

    public int getNodeCount() { return nodes.size(); }

    /** @return 第 {@code index} 个导航点；它的坐标可以原地修改。 */
    public RouteNode getNode(int index) { return nodes.get(index); }

    /**
     * 插入一个导航点。
     *
     * @return 是否插入成功；下标越界会被拒绝
     */
    public boolean insertNode(int index, RouteNode node) {
        if (index < 0 || index > nodes.size()) return false;
        nodes.add(index, node);
        return true;
    }

    /**
     * 删除一个导航点。
     *
     * @return 是否删除成功；会让数量低于 {@link #MIN_NODES} 或下标越界时被拒绝
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
     * 一次完整运行由多少段「导航点到导航点」组成。
     *
     * <p>k 个导航点，单向走一趟是 k-1 段。
     * loopCount 为 1 表示只单向走一趟，N &gt; 1 表示往返 N 次，0 表示永不停止。
     *
     * @return 段数；无限循环返回 -1
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
            // 文件被手工改到导航点不足时，把不变式恢复回来。
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
