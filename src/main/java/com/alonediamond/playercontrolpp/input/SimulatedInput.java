package com.alonediamond.playercontrolpp.input;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模拟按键的唯一写入点。
 *
 * <p>输入回放、自动前进、路径跟随、容器开启、潜影盒挖掘都需要按住移动键或点击键。
 * 各自直接调 {@code KeyMapping.setDown()} 的话后写的会覆盖前写的——别人还要用的键被松开，
 * 或者按下去再没人松（早期 {@code ContainerOpener} 就把右键按死过）。
 *
 * <p>现在功能只<b>声明意图</b>：按 owner 引用计数，{@link #apply()} 是唯一碰 {@code setDown}
 * 的地方，每 tick 末尾在所有功能 tick 完之后跑一次。只要还有一个 owner 要这个键就保持按下，
 * 最后一个 owner 松手的那一 tick 才释放。
 *
 * <p>从未被声明过的键永不写入，玩家真实输入不受影响。仅客户端线程使用，无需同步。
 */
public final class SimulatedInput {

    /** 键 → 当前要求它按下的 owner 集合。KeyMapping 是单例，所以按引用哈希。 */
    private static final Map<KeyMapping, Set<Object>> HOLDERS = new IdentityHashMap<>();
    /** 本类按下且还没松开的键——只有 {@link #apply()} 能清。 */
    private static final Set<KeyMapping> PRESSED = newIdentitySet();

    private SimulatedInput() {}

    private static <T> Set<T> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** 声明 {@code owner} 需要按住 {@code key}。可重复调用。 */
    public static void hold(KeyMapping key, Object owner) {
        HOLDERS.computeIfAbsent(key, k -> newIdentitySet()).add(owner);
    }

    /** 撤销 {@code owner} 对 {@code key} 的要求；其他 owner 仍可让它保持按下。 */
    public static void release(KeyMapping key, Object owner) {
        Set<Object> owners = HOLDERS.get(key);
        if (owners != null && owners.remove(owner) && owners.isEmpty()) {
            HOLDERS.remove(key);
        }
    }

    /** 每 tick 重算布尔值的调用方（回放、自动前进）直接传结果，不用自己分支。 */
    public static void set(KeyMapping key, Object owner, boolean held) {
        if (held) {
            hold(key, owner);
        } else {
            release(key, owner);
        }
    }

    /** 撤销 {@code owner} 的全部要求。功能停止/中断/失败时调一行即可，不会留下卡键。 */
    public static void releaseAll(Object owner) {
        List<KeyMapping> emptied = null;
        for (Map.Entry<KeyMapping, Set<Object>> entry : HOLDERS.entrySet()) {
            Set<Object> owners = entry.getValue();
            if (owners.remove(owner) && owners.isEmpty()) {
                if (emptied == null) emptied = new ArrayList<>(2);
                emptied.add(entry.getKey());
            }
        }
        if (emptied != null) {
            emptied.forEach(HOLDERS::remove);
        }
    }

    /** 丢弃所有 owner 的所有要求。玩家离开世界时用。 */
    public static void clear() {
        HOLDERS.clear();
    }

    /** @return 当前是否有 owner 要求按住 {@code key}。 */
    public static boolean isHeld(KeyMapping key) {
        return HOLDERS.containsKey(key);
    }

    /** 把声明状态落到真正的 {@link KeyMapping} 上。每 tick 一次，在所有功能 tick 完之后。 */
    public static void apply() {
        for (KeyMapping key : HOLDERS.keySet()) {
            key.setDown(true);
            PRESSED.add(key);
        }
        PRESSED.removeIf(key -> {
            if (HOLDERS.containsKey(key)) return false;
            key.setDown(false);
            return true;
        });
    }
}
