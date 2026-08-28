package com.alonediamond.playercontrolpp.integration;

import com.alonediamond.playercontrolpp.Playercontrolpp;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * LitematList 联动，全部走反射，模组保持可选（它只发布了 26.1.2 版，本项目还要编译更老的 MC）。
 *
 * <p>只读它的公开 API {@code com.litematlist.api.LitematListAPI}：返回的是上传区域里
 * 已经处理完替换、忽略与项目聚合的清单，因此这里不需要再关心它的替换/忽略规则。
 */
public class LitematListIntegration implements ModIntegration {

    private static final String API_CLASS = "com.litematlist.api.LitematListAPI";

    private static final LitematListIntegration INSTANCE = new LitematListIntegration();

    private boolean loaded;

    // 与 LitematicaIntegration 相同的惰性解析模式：方法句柄只解析一次，条目访问器
    // 在条目类变了才重解析，避免每次调用都抛 NoSuchMethodException。
    private boolean getMaterialListResolved;
    private Method getMaterialListMethod;
    private Class<?> entryClass;
    private Method itemStackMethod;
    private Method totalCountMethod;
    private Method missingCountMethod;

    private LitematListIntegration() {}

    public static LitematListIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("litematlist");
    }

    /**
     * LitematList 上传清单里的一条：物品堆、需求总量，以及按它的算法算出的还缺多少。
     */
    public record MaterialEntry(ItemStack stack, int totalCount, int missingCount) {}

    /**
     * 读取 LitematList 上传区域的材料列表（已含替换、忽略与项目聚合处理）。
     *
     * @return 条目列表；模组不在、上传区域为空或读取失败时为空列表
     */
    public List<MaterialEntry> getMaterialEntries() {
        if (!loaded) return Collections.emptyList();

        try {
            if (!resolveApiMethods()) return Collections.emptyList();

            List<?> items = (List<?>) getMaterialListMethod.invoke(null);
            if (items == null || items.isEmpty()) return Collections.emptyList();

            Object first = items.get(0);
            if (entryClass != first.getClass()) {
                entryClass = first.getClass();
                itemStackMethod = entryClass.getMethod("itemStack");
                totalCountMethod = entryClass.getMethod("totalCount");
                missingCountMethod = entryClass.getMethod("missingCount");
            }

            List<MaterialEntry> result = new ArrayList<>(items.size());
            for (Object item : items) {
                ItemStack stack = (ItemStack) itemStackMethod.invoke(item);
                if (stack == null || stack.isEmpty()) continue;
                int total = (Integer) totalCountMethod.invoke(item);
                int missing = (Integer) missingCountMethod.invoke(item);
                result.add(new MaterialEntry(stack, total, missing));
            }
            return result;
        } catch (Exception e) {
            Playercontrolpp.LOGGER.debug("Unable to read the LitematList material list", e);
            return Collections.emptyList();
        }
    }

    private boolean resolveApiMethods() {
        if (getMaterialListResolved) return getMaterialListMethod != null;

        getMaterialListResolved = true;
        try {
            Class<?> api = Class.forName(API_CLASS);
            getMaterialListMethod = api.getMethod("getMaterialList");
            return true;
        } catch (Exception e) {
            Playercontrolpp.LOGGER.debug("LitematList API class not found", e);
            return false;
        }
    }
}
