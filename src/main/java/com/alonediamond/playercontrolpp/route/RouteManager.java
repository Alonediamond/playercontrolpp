package com.alonediamond.playercontrolpp.route;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.MaLiLibCompat;
import com.alonediamond.playercontrolpp.util.AtomicFiles;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 持有路径列表并负责它的持久化。
 *
 * <p>路径热键是通过 {@code KeybindProvider} 进入 malilib 按键映射的，而它是按需枚举这个列表的。
 * 所以增删路径只需要让 malilib 重建映射——不存在第二条需要同步的注册路径。
 */
public class RouteManager {
    private static final RouteManager INSTANCE = new RouteManager();
    private static final String ROUTES_FILE = "playercontrolpp_routes.json";

    private final List<Route> routes = new ArrayList<>();
    private final List<RouteHotkey> routeHotkeys = new ArrayList<>();
    private boolean loaded;

    private RouteManager() {}

    public static RouteManager getInstance() { return INSTANCE; }

    public List<Route> getRoutes() { return Collections.unmodifiableList(routes); }

    public Route addRoute(String name) {
        Route route = new Route(name);
        routes.add(route);
        RouteHotkey rh = new RouteHotkey(route);
        routeHotkeys.add(rh);
        registerRouteCallback(rh);
        refreshKeybinds();
        saveRoutes();
        return route;
    }

    public void removeRoute(Route route) {
        RouteFlowRuntime.getInstance().stopRoute(route);
        routes.remove(route);
        routeHotkeys.removeIf(rh -> rh.route == route);
        refreshKeybinds();
        saveRoutes();
    }

    public List<RouteHotkey> getRouteHotkeyList() {
        return Collections.unmodifiableList(routeHotkeys);
    }

    /**
     * 让 malilib 从所有已注册的 provider 重建按键映射。
     *
     * <p>{@code IKeybindManager} 没有移除单个按键的接口，所以被删掉的路径的热键早先会留在映射里：
     * 按下旧键仍会触发一个已经不存在的路径的回调，还让那个 {@code Route} 对象一直活着。
     * {@code updateUsedKeys()} 丢掉映射重新问一遍 provider，而 {@code KeybindProvider}
     * 本来就是枚举当前路径列表的——所以这一行就是全部需要做的事。
     */
    public void refreshKeybinds() {
        InputEventHandler.getKeybindManager().updateUsedKeys();
    }

    private void registerRouteCallback(RouteHotkey rh) {
        rh.getKeybind().setCallback(new IHotkeyCallback() {
            @Override
            public boolean onKeyAction(KeyAction action, IKeybind key) {
                if (action != KeyAction.PRESS) return false;
                if (Minecraft.getInstance().player == null) return false;
                RouteFlowRuntime.getInstance().toggleRoute(rh.getRoute());
                return true;
            }
        });
    }

    /**
     * 读路径文件。可以反复调用：只有成功读取才会被记住，所以解析失败不会让本局永久没有路径，
     * <em>而且</em>也不会让下一次保存覆盖掉它没读成功的那个文件。
     */
    public void loadRoutes() {
        if (loaded) return;

        Path configFile = MaLiLibCompat.configDirectory().resolve(ROUTES_FILE);
        if (!Files.exists(configFile) || Files.isDirectory(configFile)) {
            loaded = true;
            return;
        }

        List<Route> parsed = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) {
                throw new IOException("routes file does not contain a JSON object");
            }
            JsonObject root = element.getAsJsonObject();
            if (root.has("routes")) {
                JsonArray arr = root.getAsJsonArray("routes");
                for (int i = 0; i < arr.size(); i++) {
                    parsed.add(Route.fromJson(arr.get(i).getAsJsonObject()));
                }
            }
        } catch (Exception e) {
            Path quarantined = AtomicFiles.quarantine(configFile);
            Playercontrolpp.LOGGER.warn("Failed to read routes; moved the file to {}",
                    quarantined != null ? quarantined.getFileName() : "(move failed)", e);
            return;
        }

        for (Route route : parsed) {
            routes.add(route);
            RouteHotkey rh = new RouteHotkey(route);
            routeHotkeys.add(rh);
            registerRouteCallback(rh);
        }
        loaded = true;
    }

    public void saveRoutes() {
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Route route : routes) {
            arr.add(route.toJson());
        }
        root.add("routes", arr);

        Path configFile = MaLiLibCompat.configDirectory().resolve(ROUTES_FILE);
        try {
            AtomicFiles.writeString(configFile,
                    new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (IOException e) {
            Playercontrolpp.LOGGER.warn("Failed to save routes", e);
        }
    }

    /**
     * 适配器：让一条路径的热键出现在 malilib 的热键界面里，所有 {@code IConfigBase} 调用
     * 都转交给该路径自己的 {@code ConfigHotkey}。
     */
    public static class RouteHotkey implements IHotkey {
        private final Route route;

        RouteHotkey(Route route) {
            this.route = route;
        }

        @Override
        public IKeybind getKeybind() {
            return route.getHotkey().getKeybind();
        }

        public Route getRoute() { return route; }

        // IConfigBase delegation
        @Override public fi.dy.masa.malilib.config.ConfigType getType() { return route.getHotkey().getType(); }
        @Override public String getName() { return route.getHotkey().getName(); }
        @Override public String getComment() { return route.getHotkey().getComment(); }
        @Override public String getTranslatedName() { return route.getHotkey().getTranslatedName(); }
        @Override public JsonElement getAsJsonElement() { return route.getHotkey().getAsJsonElement(); }
        @Override public void setValueFromJsonElement(JsonElement element) { route.getHotkey().setValueFromJsonElement(element); }
        @Override public void setPrettyName(String prettyName) { route.getHotkey().setPrettyName(prettyName); }
        @Override public void setTranslatedName(String translatedName) { route.getHotkey().setTranslatedName(translatedName); }
        @Override public void setComment(String comment) { route.getHotkey().setComment(comment); }

        // IConfigResettable delegation
        @Override public void resetToDefault() { route.getHotkey().resetToDefault(); }

        // IStringRepresentable delegation
        @Override public String getStringValue() { return route.getHotkey().getStringValue(); }
        @Override public String getDefaultStringValue() { return route.getHotkey().getDefaultStringValue(); }
        @Override public void setValueFromString(String value) { route.getHotkey().setValueFromString(value); }
        @Override public boolean isModified() { return route.getHotkey().isModified(); }
        @Override public boolean isModified(String newValue) { return route.getHotkey().isModified(newValue); }

        // malilib 在 0.27.x（随 MC 1.21.11 发布）才给 IConfigBase 加了 dirty 追踪；
        // malilib 0.21.10 / 0.23.5 / 0.25.7 既没有这些接口方法，也没有可转交的目标。
        //#if MC >= 12111
        @Override public void checkIfClean() { route.getHotkey().checkIfClean(); }
        @Override public boolean isDirty() { return route.getHotkey().isDirty(); }
        @Override public void markDirty() { route.getHotkey().markDirty(); }
        @Override public void markClean() { route.getHotkey().markClean(); }
        //#endif
    }
}
