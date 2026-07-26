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
            } catch (Exception ignored) {}
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
}
