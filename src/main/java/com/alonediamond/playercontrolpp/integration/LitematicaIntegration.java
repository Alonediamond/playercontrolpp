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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Litematica 联动，全部走反射，模组保持可选。
 *
 * <p>本类解析出的每个 {@link Method} 都会缓存。这很重要：{@code getPlacementBounds()} 处在
 * 每 tick 的扫描路径上，而早先的实现是对四个候选方法名逐个 {@code getMethod} 并吞掉失败——
 * 每 tick 构造并填充最多三个异常栈。现在改成把 {@code getMethods()} 走一遍，一个异常都不抛。
 */
public class LitematicaIntegration implements ModIntegration {

    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";
    private static final String WORLD_HANDLER = "fi.dy.masa.litematica.world.SchematicWorldHandler";

    /**
     * Litematica 在不同版本里给这个 getter 改过名，而且无法预知某个构建用的是哪个名字，
     * 所以所有已知拼写都接受。
     */
    private static final Set<String> PLACEMENTS_GETTERS = Set.of(
            "getAllSchematicPlacements",
            "getAllSchematicsPlacements",
            "getSchematicPlacements",
            "getLoadedSchematicPlacements");

    private static final LitematicaIntegration INSTANCE = new LitematicaIntegration();

    private boolean loaded;

    // 首次使用时惰性解析，之后复用。宿主类变了才清空——实践中只在开发期热替换 Litematica 时发生。
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
    private Method getSelectionManagerMethod;
    private Class<?> selectionManagerClass;
    private Method getCurrentSelectionMethod;
    private Class<?> areaSelectionClass;
    private Method getAllSelectionBoxesMethod;
    private Class<?> selectionBoxClass;
    private Method getBoxPos1Method;
    private Method getBoxPos2Method;

    private LitematicaIntegration() {}

    public static LitematicaIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("litematica");
    }

    /** 一个投影 placement 的世界坐标外框。 */
    public record PlacementBounds(BlockPos origin, int sizeX, int sizeY, int sizeZ) {
        public boolean contains(BlockPos pos) {
            return pos.getX() >= origin.getX() && pos.getX() < origin.getX() + sizeX
                    && pos.getY() >= origin.getY() && pos.getY() < origin.getY() + sizeY
                    && pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + sizeZ;
        }
    }

    /** Litematica 当前区域选区里一个盒子的世界坐标范围（闭区间）。 */
    public record SelectionBounds(BlockPos min, BlockPos max) {
        public long volume() {
            long sizeX = (long) max.getX() - min.getX() + 1L;
            long sizeY = (long) max.getY() - min.getY() + 1L;
            long sizeZ = (long) max.getZ() - min.getZ() + 1L;
            try {
                return Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    /**
     * 读出 Litematica 当前区域选区的所有盒子。
     *
     * <p>刻意不用投影 placement 的范围：容器缓存功能操作的是玩家当前的区域选区，
     * 读的是选区内真实世界的方块，与 Litematica Printer 的 {@code Printer.siftBlock()} 一致。
     */
    public List<SelectionBounds> getCurrentSelectionBounds() {
        if (!loaded) return Collections.emptyList();

        try {
            Object manager = selectionManager();
            if (manager == null) return Collections.emptyList();

            if (selectionManagerClass != manager.getClass()) {
                selectionManagerClass = manager.getClass();
                getCurrentSelectionMethod = selectionManagerClass.getMethod("getCurrentSelection");
                areaSelectionClass = null;
                getAllSelectionBoxesMethod = null;
            }

            Object selection = getCurrentSelectionMethod.invoke(manager);
            if (selection == null) return Collections.emptyList();

            if (areaSelectionClass != selection.getClass()) {
                areaSelectionClass = selection.getClass();
                getAllSelectionBoxesMethod = findNoArgMethod(
                        areaSelectionClass, Set.of("getAllSubRegionBoxes", "getAllSubRegions"));
                selectionBoxClass = null;
                getBoxPos1Method = null;
                getBoxPos2Method = null;
            }
            if (getAllSelectionBoxesMethod == null) return Collections.emptyList();

            Object rawBoxes = getAllSelectionBoxesMethod.invoke(selection);
            Collection<?> boxes;
            if (rawBoxes instanceof Map<?, ?> map) {
                boxes = map.values();
            } else if (rawBoxes instanceof Collection<?> collection) {
                boxes = collection;
            } else {
                return Collections.emptyList();
            }

            List<SelectionBounds> result = new ArrayList<>(boxes.size());
            for (Object box : boxes) {
                SelectionBounds bounds = selectionBoundsOf(box);
                if (bounds != null) result.add(bounds);
            }
            return result;
        } catch (Exception e) {
            Playercontrolpp.LOGGER.debug("Unable to read Litematica's current area selection", e);
            return Collections.emptyList();
        }
    }

    private Object selectionManager() throws Exception {
        if (getSelectionManagerMethod == null) {
            getSelectionManagerMethod = dataManagerClass().getMethod("getSelectionManager");
        }
        return getSelectionManagerMethod.invoke(null);
    }

    private SelectionBounds selectionBoundsOf(Object box) {
        if (box == null) return null;
        try {
            if (selectionBoxClass != box.getClass()) {
                selectionBoxClass = box.getClass();
                getBoxPos1Method = selectionBoxClass.getMethod("getPos1");
                getBoxPos2Method = selectionBoxClass.getMethod("getPos2");
            }
            BlockPos pos1 = (BlockPos) getBoxPos1Method.invoke(box);
            BlockPos pos2 = (BlockPos) getBoxPos2Method.invoke(box);
            if (pos1 == null || pos2 == null) return null;

            return new SelectionBounds(
                    new BlockPos(
                            Math.min(pos1.getX(), pos2.getX()),
                            Math.min(pos1.getY(), pos2.getY()),
                            Math.min(pos1.getZ(), pos2.getZ())),
                    new BlockPos(
                            Math.max(pos1.getX(), pos2.getX()),
                            Math.max(pos1.getY(), pos2.getY()),
                            Math.max(pos1.getZ(), pos2.getZ())));
        } catch (Exception e) {
            return null;
        }
    }

    /** @return 是否至少加载了一个投影 placement。 */
    public boolean isSchematicLoaded() {
        return !getAllPlacements().isEmpty();
    }

    /**
     * @return Litematica 的投影世界（一个 {@code BlockGetter}），用来读投影方块状态；
     *         没加载投影时返回 {@code null}。
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

    /** @return 所有已加载的 {@code SchematicPlacement}；没有则空列表。 */
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
     * @return 所有已加载 placement 的世界坐标范围。Litematica 不在、没加载投影、
     *         或它的内部结构不再符合本代码预期时返回空。
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
            // 一个畸形的 placement 不该把其余的一起抹掉。
            return null;
        }
    }

    /** DataManager 的访问器都是静态的——没有 getInstance()。 */
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
     * 试若干个名字来解析一个无参方法，且不为每次未命中付一个
     * {@code NoSuchMethodException} 的代价。
     */
    private static Method findNoArgMethod(Class<?> owner, Set<String> candidateNames) {
        for (Method method : owner.getMethods()) {
            if (method.getParameterCount() == 0 && candidateNames.contains(method.getName())) {
                return method;
            }
        }
        return null;
    }

    /** @return Litematica 当前的 MaterialList；没有则 {@code null}。 */
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
     * @return Litematica 内部的「已忽略条目」集合；读不到时返回空集。
     *
     * <p>需要 {@code getDeclaredField} + {@code setAccessible}，因为 1.21.11 起该字段在
     * MaterialListBase 里是 {@code protected}。还必须沿继承链往上找：{@code getMaterialList()}
     * 返回的是 {@code MaterialListPlacement} 或 {@code MaterialListSchematic}，
     * 而 {@code getDeclaredField} 不看父类，只问具体类必然找不到并静默返回空。
     */
    @SuppressWarnings("unchecked")
    public Set<Object> getIgnoredSet(Object materialList) {
        for (Class<?> c = materialList.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField("ignored");
                field.setAccessible(true);
                Object value = field.get(materialList);
                return value instanceof Set<?> set ? (Set<Object>) set : Collections.emptySet();
            } catch (NoSuchFieldException ignored) {
                // 声明在更上层；继续往上找。
            } catch (Exception e) {
                return Collections.emptySet();
            }
        }
        return Collections.emptySet();
    }

    /** 移动渲染层，行为与 Litematica 自己的 PageUp/PageDown 热键一致。 */
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
