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
 * Owns the route list and its persistence.
 *
 * <p>Route hotkeys reach malilib's key map through {@code KeybindProvider}, which enumerates this
 * list on demand. Adding or removing a route therefore only has to ask malilib to rebuild the map
 * — there is no second registration path to keep in sync.
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
     * Ask malilib to rebuild its key map from every registered provider.
     *
     * <p>{@code IKeybindManager} has no way to remove a single keybind, so a deleted route's
     * hotkey used to stay in the map: pressing the old key still fired the callback for a route
     * that no longer existed, and kept the {@code Route} object alive. {@code updateUsedKeys()}
     * discards the map and re-asks the providers, and {@code KeybindProvider} already enumerates
     * the live route list — so this is all that was ever needed.
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
     * Read the route file. Safe to call repeatedly: only a successful read is remembered, so a
     * parse failure does not leave the session permanently routeless <em>and</em> then let the
     * next save overwrite the file it failed to read.
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
     * Adapter that lets a route's hotkey appear in malilib's hotkey GUI, delegating every
     * {@code IConfigBase} call to the route's own {@code ConfigHotkey}.
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

        // malilib added dirty tracking to IConfigBase in 0.27.x (shipped with MC 1.21.11);
        // malilib 0.21.10 / 0.23.5 have neither the interface methods nor the delegate targets.
        //#if MC >= 12111
        @Override public void checkIfClean() { route.getHotkey().checkIfClean(); }
        @Override public boolean isDirty() { return route.getHotkey().isDirty(); }
        @Override public void markDirty() { route.getHotkey().markDirty(); }
        @Override public void markClean() { route.getHotkey().markClean(); }
        //#endif
    }
}
