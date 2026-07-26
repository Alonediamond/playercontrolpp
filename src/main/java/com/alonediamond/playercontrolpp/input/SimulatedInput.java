package com.alonediamond.playercontrolpp.input;

import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single writer of simulated key state.
 *
 * <p>Several features need to hold movement or click keys down: input playback, auto-forward,
 * route following, container opening, shulker-box mining. Before this class existed each of
 * them called {@code KeyMapping.setDown()} directly, so whichever ran last won — releasing a
 * key another feature still needed, or (in {@code ContainerOpener}) pressing a key that nothing
 * ever released, which left right-click stuck on.
 *
 * <p>Now features only declare intent. Every key is reference-counted by owner, and
 * {@link #apply()} — called once at the end of the client tick — is the only place that touches
 * {@code setDown}. A key stays down while at least one owner wants it and is released on the
 * tick the last owner lets go.
 *
 * <p>Keys nobody has ever declared are never written to, so the player's real input is
 * untouched. Client-thread only; no synchronization.
 */
public final class SimulatedInput {

    /** key -&gt; owners currently requesting it. Identity-keyed: KeyMappings are singletons. */
    private static final Map<KeyMapping, Set<Object>> HOLDERS = new IdentityHashMap<>();
    /** Keys this class has pressed and not yet released — all {@link #apply()} may clear. */
    private static final Set<KeyMapping> PRESSED = newIdentitySet();

    private SimulatedInput() {}

    private static <T> Set<T> newIdentitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Declare that {@code owner} needs {@code key} held down. Idempotent. */
    public static void hold(KeyMapping key, Object owner) {
        HOLDERS.computeIfAbsent(key, k -> newIdentitySet()).add(owner);
    }

    /** Withdraw {@code owner}'s request for {@code key}. Other owners keep it down. */
    public static void release(KeyMapping key, Object owner) {
        Set<Object> owners = HOLDERS.get(key);
        if (owners != null && owners.remove(owner) && owners.isEmpty()) {
            HOLDERS.remove(key);
        }
    }

    /**
     * Convenience for per-tick declarations: callers that recompute a boolean every tick
     * (playback, auto-forward) pass it straight through instead of branching.
     */
    public static void set(KeyMapping key, Object owner, boolean held) {
        if (held) {
            hold(key, owner);
        } else {
            release(key, owner);
        }
    }

    /**
     * Withdraw every request made by {@code owner}. Call this when a feature stops, aborts or
     * fails — it makes cleanup a single line and cannot leave a key behind.
     */
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

    /** Drop all requests from all owners. Used when the player leaves the world. */
    public static void clear() {
        HOLDERS.clear();
    }

    /** @return whether any owner currently wants {@code key} held. */
    public static boolean isHeld(KeyMapping key) {
        return HOLDERS.containsKey(key);
    }

    /**
     * Reconcile the declared state onto the actual {@link KeyMapping}s. Must run once per client
     * tick, after every feature has ticked.
     */
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
