package com.alonediamond.playercontrolpp.integration;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import java.lang.reflect.Method;

public class BaritoneIntegration implements ModIntegration {

    private static final BaritoneIntegration INSTANCE = new BaritoneIntegration();
    private boolean loaded;

    private BaritoneIntegration() {}

    public static BaritoneIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("baritone");
    }

    private Object getBaritone() throws Exception {
        Class<?> apiClass = Class.forName("baritone.api.BaritoneAPI");
        Object provider = apiClass.getMethod("getProvider").invoke(null);
        return provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
    }

    /**
     * Start Baritone pathing toward the given block position.
     */
    public void pathTo(BlockPos target) {
        try {
            cancelPathing();

            Object baritone = getBaritone();
            Object customGoalProcess = baritone.getClass()
                    .getMethod("getCustomGoalProcess").invoke(baritone);

            Class<?> goalClass = Class.forName("baritone.api.pathing.goals.GoalGetToBlock");
            Object goal = goalClass.getConstructor(BlockPos.class).newInstance(target);

            customGoalProcess.getClass()
                    .getMethod("setGoalAndPath",
                            Class.forName("baritone.api.pathing.goals.Goal"))
                    .invoke(customGoalProcess, goal);

        } catch (Exception e) {
            // Fallback: try command execution
            try {
                Object baritone = getBaritone();
                Object cmdManager = baritone.getClass()
                        .getMethod("getCommandManager").invoke(baritone);
                String cmd = String.format("goto %d %d %d",
                        target.getX(), target.getY(), target.getZ());
                cmdManager.getClass()
                        .getMethod("execute", String.class)
                        .invoke(cmdManager, cmd);
            } catch (Exception ignored) {
                // Both the API and the command fallback failed, which means this Baritone fork
                // does not expose either. Staying silent is deliberate: the caller
                // (BaritonePathingController) notices that pathing never started and reports
                // that to the player, which is a far more useful message than a reflection trace.
            }
        }
    }

    /**
     * Cancel any active Baritone pathing and custom goals.
     */
    public boolean cancelPathing() {
        try {
            Object baritone = getBaritone();
            Object pathingBehavior = baritone.getClass()
                    .getMethod("getPathingBehavior").invoke(baritone);
            pathingBehavior.getClass().getMethod("cancelEverything").invoke(pathingBehavior);
            Object customGoalProcess = baritone.getClass()
                    .getMethod("getCustomGoalProcess").invoke(baritone);
            customGoalProcess.getClass().getMethod("onLostControl").invoke(customGoalProcess);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Check if Baritone is currently pathing.
     */
    public boolean isPathing() {
        try {
            Object baritone = getBaritone();
            Object pathingBehavior = baritone.getClass()
                    .getMethod("getPathingBehavior").invoke(baritone);
            Boolean isPathing = (Boolean) pathingBehavior.getClass()
                    .getMethod("isPathing").invoke(pathingBehavior);
            return isPathing != null && isPathing;
        } catch (Exception e) {
            return false;
        }
    }

    // --- Builder process access (for auto-restock) ---

    /**
     * @return whether the Baritone BuilderProcess is currently active (i.e. a #litematica build
     *         is running). Returns false when Baritone is absent or the process is idle.
     */
    public boolean isBuilderActive() {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            Boolean active = (Boolean) builderProcess.getClass()
                    .getMethod("isActive").invoke(builderProcess);
            return active != null && active;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return whether the BuilderProcess is currently paused (material shortage / pathing failure /
     *         liquid targets / …). Returns false when the process is not active.
     */
    public boolean isBuilderPaused() {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            Boolean paused = (Boolean) builderProcess.getClass()
                    .getMethod("isPaused").invoke(builderProcess);
            return paused != null && paused;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Start a new Litematica schematic build through Baritone's BuilderProcess, equivalent to
     * typing {@code #litematica} in chat (or {@code #litematica <index>} when more than one
     * schematic is loaded).
     *
     * @param schematicIndex zero-based index into Litematica's loaded schematic list.
     */
    public boolean startLitematicaBuild(int schematicIndex) {
        try {
            Object baritone = getBaritone();
            Object builderProcess = baritone.getClass()
                    .getMethod("getBuilderProcess").invoke(baritone);
            builderProcess.getClass()
                    .getMethod("buildOpenLitematic", int.class)
                    .invoke(builderProcess, schematicIndex);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
