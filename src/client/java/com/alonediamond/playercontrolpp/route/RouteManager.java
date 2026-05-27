package com.alonediamond.playercontrolpp.route;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.util.FileUtils;
import net.minecraft.client.MinecraftClient;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RouteManager {
    private static final RouteManager INSTANCE = new RouteManager();
    private static final String ROUTES_FILE = "playercontrolpp_routes.json";

    private final List<Route> routes = new ArrayList<>();
    private final List<RouteHotkey> routeHotkeys = new ArrayList<>();
    private boolean loaded = false;

    private RouteManager() {}

    public static RouteManager getInstance() { return INSTANCE; }

    public List<Route> getRoutes() { return Collections.unmodifiableList(routes); }

    public Route addRoute(String name) {
        Route route = new Route(name);
        routes.add(route);
        RouteHotkey rh = new RouteHotkey(route);
        routeHotkeys.add(rh);
        registerRouteKeybind(route);
        registerRouteCallback(rh);
        saveRoutes();
        return route;
    }

    public void removeRoute(Route route) {
        RouteFlowRuntime.getInstance().stopRoute(route);
        unregisterRouteKeybind(route);
        routes.remove(route);
        routeHotkeys.removeIf(rh -> rh.route == route);
        saveRoutes();
    }

    public List<RouteHotkey> getRouteHotkeyList() {
        return Collections.unmodifiableList(routeHotkeys);
    }

    public List<IHotkey> getRouteHotkeysAsIHotkey() {
        List<IHotkey> list = new ArrayList<>();
        for (RouteHotkey rh : routeHotkeys) {
            list.add(rh);
        }
        return list;
    }

    public void registerAllKeybinds() {
        for (RouteHotkey rh : routeHotkeys) {
            InputEventHandler.getKeybindManager().addKeybindToMap(rh.getKeybind());
        }
    }

    private void registerRouteKeybind(Route route) {
        InputEventHandler.getKeybindManager().addKeybindToMap(route.getHotkey().getKeybind());
    }

    private void registerRouteCallback(RouteHotkey rh) {
        rh.getKeybind().setCallback(new fi.dy.masa.malilib.hotkeys.IHotkeyCallback() {
            @Override
            public boolean onKeyAction(fi.dy.masa.malilib.hotkeys.KeyAction action,
                                        fi.dy.masa.malilib.hotkeys.IKeybind key) {
                if (action != fi.dy.masa.malilib.hotkeys.KeyAction.PRESS) return false;
                if (MinecraftClient.getInstance().player == null) return false;
                RouteFlowRuntime.getInstance().toggleRoute(rh.getRoute());
                return true;
            }
        });
    }

    private void unregisterRouteKeybind(Route route) {
        // KeybindManager doesn't explicitly unregister, but removing from list is enough
    }

    public void loadRoutes() {
        if (loaded) return;
        loaded = true;

        Path configFile = FileUtils.getConfigDirectoryAsPath().resolve(ROUTES_FILE);
        if (!Files.exists(configFile) || Files.isDirectory(configFile)) return;

        try (Reader reader = new InputStreamReader(
                new FileInputStream(configFile.toFile()), StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (element == null || !element.isJsonObject()) return;

            JsonObject root = element.getAsJsonObject();
            if (root.has("routes")) {
                JsonArray arr = root.getAsJsonArray("routes");
                for (int i = 0; i < arr.size(); i++) {
                    JsonObject routeObj = arr.get(i).getAsJsonObject();
                    Route route = Route.fromJson(routeObj);
                    routes.add(route);
                    RouteHotkey rh = new RouteHotkey(route);
                    routeHotkeys.add(rh);
                    registerRouteCallback(rh);
                }
            }
        } catch (Exception e) {
            System.err.println("[PlayerControl++] Failed to load routes: " + e.getMessage());
        }
    }

    public void saveRoutes() {
        Path dir = FileUtils.getConfigDirectoryAsPath();
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
            return;
        }

        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Route route : routes) {
            arr.add(route.toJson());
        }
        root.add("routes", arr);

        Path configFile = dir.resolve(ROUTES_FILE);
        try (Writer writer = new OutputStreamWriter(
                new FileOutputStream(configFile.toFile()), StandardCharsets.UTF_8)) {
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            writer.write(gson.toJson(root));
        } catch (Exception e) {
            System.err.println("[PlayerControl++] Failed to save routes: " + e.getMessage());
        }
    }

    /**
     * Wrapper class so route hotkeys show up in malilib's hotkey GUI.
     * Delegates IConfigBase methods to the underlying ConfigHotkey.
     */
    public static class RouteHotkey implements IHotkey {
        private final Route route;

        RouteHotkey(Route route) {
            this.route = route;
        }

        @Override
        public fi.dy.masa.malilib.hotkeys.IKeybind getKeybind() {
            return route.getHotkey().getKeybind();
        }

        public Route getRoute() { return route; }

        // IConfigBase delegation
        @Override public fi.dy.masa.malilib.config.ConfigType getType() { return route.getHotkey().getType(); }
        @Override public String getName() { return route.getHotkey().getName(); }
        @Override public String getComment() { return route.getHotkey().getComment(); }
        @Override public String getTranslatedName() { return route.getHotkey().getTranslatedName(); }
        @Override public com.google.gson.JsonElement getAsJsonElement() { return route.getHotkey().getAsJsonElement(); }
        @Override public void setValueFromJsonElement(com.google.gson.JsonElement element) { route.getHotkey().setValueFromJsonElement(element); }
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
    }
}
