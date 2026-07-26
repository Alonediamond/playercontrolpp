package com.alonediamond.playercontrolpp.integration;

import com.alonediamond.playercontrolpp.Playercontrolpp;
import com.alonediamond.playercontrolpp.compat.PlayerCompat;

import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Litematica integration, entirely through reflection so the mod stays optional.
 *
 * <p>Every {@link Method} this class resolves is cached. That matters because
 * {@code getPlacementBounds()} sits in a per-tick scan path, and the previous version probed four
 * candidate method names by calling {@code getMethod} on each and catching the failures —
 * building and filling up to three exception stack traces every tick. Resolution now walks
 * {@code getMethods()} once and throws nothing.
 */
public class LitematicaIntegration implements ModIntegration {

    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";
    private static final String WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler";

    /**
     * Litematica has renamed this getter across releases and there is no way to know which name
     * a given build carries, so all known spellings are accepted.
     */
    private static final Set<String> PLACEMENTS_GETTERS = Set.of(
            "getAllSchematicPlacements",
            "getAllSchematicsPlacements",
            "getSchematicPlacements",
            "getLoadedSchematicPlacements");

    private static final LitematicaIntegration INSTANCE = new LitematicaIntegration();

    private boolean loaded;

    // Resolved lazily on first use, then reused. Cleared if the owning class ever changes,
    // which in practice only happens across a Litematica hot-swap during development.
    private Class<?> dataManagerClass;
    private Method getPlacementManagerMethod;
    private Method getMaterialListMethod;
    private Method getSchematicWorldMethod;
    private Class<?> placementManagerClass;
    private Method placementsGetter;
    private Class<?> placementClass;
    private Method getOriginMethod;
    private Method getSchematicMethod;
    private Class<?> schematicClass;
    private Method getTotalSizeMethod;

    private LitematicaIntegration() {}

    public static LitematicaIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** World-space bounding box of one schematic placement. */
    public record PlacementBounds(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + sizeX
                    && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + sizeY
                    && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + sizeZ;
        }
    }

    /** @return whether at least one schematic placement is loaded. */
    public boolean isSchematicLoaded() {
        return !getAllPlacements().isEmpty();
    }

    /**
     * @return Litematica's schematic world (a {@code BlockGetter}) for reading schematic block
     *         states, or {@code null} when no schematic is loaded.
     */
    public Object getSchematicWorld() {
        if (!loaded) return null;
        try {
            if (getSchematicWorldMethod == null) {
                getSchematicWorldMethod = Class.forName(WORLD_HANDLER).getMethod("getSchematicWorld");
            }
            return getSchematicWorldMethod.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** @return every loaded {@code SchematicPlacement}, or an empty list. */
    public List<?> getAllPlacements() {
        if (!loaded) return Collections.emptyList();
        try {
            Object manager = placementManager();
            if (manager == null) return Collections.emptyList();

            Method getter = placementsGetter(manager.getClass());
            if (getter == null) return Collections.emptyList();

            Object result = getter.invoke(manager);
            return result instanceof List<?> list ? list : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * @return the world-space bounds of every loaded placement. Empty when Litematica is absent,
     *         nothing is loaded, or its internals no longer match what this code expects.
     */
    public List<PlacementBounds> getPlacementBounds() {
        List<?> placements = getAllPlacements();
        if (placements.isEmpty()) return Collections.emptyList();

        List<PlacementBounds> result = new ArrayList<>(placements.size());
        for (Object placement : placements) {
            PlacementBounds bounds = boundsOf(placement);
            if (bounds != null) {
                result.add(bounds);
            }
        }
        return result;
    }

    private PlacementBounds boundsOf(Object placement) {
        try {
            if (placementClass != placement.getClass()) {
                placementClass = placement.getClass();
                getOriginMethod = placementClass.getMethod("getOrigin");
                getSchematicMethod = placementClass.getMethod("getSchematic");
                schematicClass = null;
                getTotalSizeMethod = null;
            }

            BlockPos origin = (BlockPos) getOriginMethod.invoke(placement);
            Object schematic = getSchematicMethod.invoke(placement);
            if (origin == null || schematic == null) return null;

            if (schematicClass != schematic.getClass()) {
                schematicClass = schematic.getClass();
                getTotalSizeMethod = schematicClass.getMethod("getTotalSize");
            }

            Vec3i size = (Vec3i) getTotalSizeMethod.invoke(schematic);
            if (size == null) return null;
            return new PlacementBounds(origin, size.getX(), size.getY(), size.getZ());
        } catch (Exception e) {
            // One malformed placement should not blank out the others.
            return null;
        }
    }

    /** All DataManager accessors are static — there is no getInstance(). */
    private Class<?> dataManagerClass() throws ClassNotFoundException {
        if (dataManagerClass == null) {
            dataManagerClass = Class.forName(DATA_MANAGER);
        }
        return dataManagerClass;
    }

    private Object placementManager() throws Exception {
        if (getPlacementManagerMethod == null) {
            getPlacementManagerMethod = dataManagerClass().getMethod("getSchematicPlacementManager");
        }
        return getPlacementManagerMethod.invoke(null);
    }

    private Method placementsGetter(Class<?> managerClass) {
        if (placementManagerClass != managerClass) {
            placementManagerClass = managerClass;
            placementsGetter = findNoArgMethod(managerClass, PLACEMENTS_GETTERS);
            if (placementsGetter == null) {
                Playercontrolpp.LOGGER.warn(
                        "Litematica {} exposes none of the known placement getters {}; "
                                + "schematic-aware features are disabled",
                        managerClass.getName(), PLACEMENTS_GETTERS);
            }
        }
        return placementsGetter;
    }

    /**
     * Resolve a zero-argument method by trying several names, without the cost of a thrown
     * {@code NoSuchMethodException} per miss.
     */
    private static Method findNoArgMethod(Class<?> owner, Set<String> candidateNames) {
        for (Method method : owner.getMethods()) {
            if (method.getParameterCount() == 0 && candidateNames.contains(method.getName())) {
                return method;
            }
        }
        return null;
    }

    /** @return Litematica's current MaterialList, or {@code null}. */
    public Object getMaterialList() {
        if (!loaded) return null;
        try {
            if (getMaterialListMethod == null) {
                getMaterialListMethod = dataManagerClass().getMethod("getMaterialList");
            }
            return getMaterialListMethod.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return Litematica's internal ignored-entries set.
     *
     * <p>Needs {@code getDeclaredField} + {@code setAccessible} because the field is
     * {@code protected} in MaterialListBase from 1.21.11 onwards.
     */
    @SuppressWarnings("unchecked")
    public Set<Object> getIgnoredSet(Object materialList) {
        try {
            Field field = materialList.getClass().getDeclaredField("ignored");
            field.setAccessible(true);
            return (Set<Object>) field.get(materialList);
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    /** Move the render layer, exactly as Litematica's own PageUp/PageDown hotkeys do. */
    public boolean incrementLayer(int amount) {
        if (amount == 0 || !loaded) return false;

        try {
            Class<?> dmClass = Class.forName(DATA_MANAGER);
            Object range = dmClass.getMethod("getRenderLayerRange").invoke(null);
            if (range == null) return false;

            Object mode = range.getClass().getMethod("getLayerMode").invoke(range);
            if (!"SINGLE_LAYER".equals(((Enum<?>) mode).name())) return false;

            boolean ok = (Boolean) range.getClass()
                    .getMethod("moveLayer", int.class).invoke(range, amount);
            if (!ok) return false;

            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                String layerStr = (String) range.getClass()
                        .getMethod("getCurrentLayerString").invoke(range);
                PlayerCompat.sendOverlayMessage(client.player,
                        StringUtils.translateAsText("playercontrolpp.message.litematica.layer", layerStr));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
