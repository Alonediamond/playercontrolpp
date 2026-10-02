package com.alonediamond.playercontrolpp.event;

import com.alonediamond.playercontrolpp.feature.AutoForwardFeature;
import com.alonediamond.playercontrolpp.feature.FeatureRegistry;
import com.alonediamond.playercontrolpp.input.SimulatedInput;
import com.alonediamond.playercontrolpp.record.InputPlayer;
import com.alonediamond.playercontrolpp.record.RecordingManager;
import com.alonediamond.playercontrolpp.route.RouteFlowRuntime;
import fi.dy.masa.malilib.event.TickHandler;
import fi.dy.masa.malilib.event.WorldLoadHandler;
import fi.dy.masa.malilib.interfaces.IClientTickHandler;
import fi.dy.masa.malilib.interfaces.IWorldLoadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * 把 malilib 的 tick 与世界加载事件桥接到 {@link FeatureRegistry}，并把移动类功能想要的输入落成按键。
 *
 * <p>按键都经 {@link SimulatedInput} 声明，在所有功能跑完后的 tick 末尾统一提交一次——
 * 这样开容器、挖潜影盒就不会和回放抢同一个键。
 */
public class ClientEventHandler {

    /** 本类声明的按键归属令牌。 */
    private static final Object MOVEMENT_OWNER = new Object();

    public static void register() {
        WorldLoadHandler.getInstance().registerWorldLoadPreHandler(new WorldLoadListener());
        TickHandler.getInstance().registerClientTickHandler(new PlayerControlTickHandler());
    }

    private static class WorldLoadListener implements IWorldLoadListener {
        @Override
        public void onWorldLoadPre(ClientLevel world1, ClientLevel world2, Minecraft client) {
            FeatureRegistry.notifyWorldChange();
            // 跨世界不会有功能还在模拟输入，全部清掉，别把按下的键带进新世界。
            SimulatedInput.clear();
            SimulatedInput.apply();
        }
    }

    private static class PlayerControlTickHandler implements IClientTickHandler {
        @Override
        public void onClientTick(Minecraft mc) {
            if (mc.player == null) {
                SimulatedInput.clear();
                SimulatedInput.apply();
                return;
            }

            FeatureRegistry.tickAll(mc);

            InputPlayer playback = RecordingManager.getInstance().getPlayer();
            declareMovementKeys(mc, playback);

            if (playback.isPlaying()) {
                playback.applyYaw(mc);
            }

            // 声明状态到达 KeyMapping 的唯一出口。
            SimulatedInput.apply();
        }

        /**
         * 每 tick 从零重新声明移动类功能要的键。
         *
         * <p>必须从零开始：回放分支管九个键，自动前进分支只管两个。留着上一 tick 的声明，
         * 回放结束而自动前进还开着时，横移或潜行就会一直按住。
         */
        private void declareMovementKeys(Minecraft mc, InputPlayer playback) {
            SimulatedInput.releaseAll(MOVEMENT_OWNER);

            if (playback.isPlaying()) {
                float fwd = playback.getForward();
                float side = playback.getSideways();
                SimulatedInput.set(mc.options.keyUp, MOVEMENT_OWNER, fwd > 0);
                SimulatedInput.set(mc.options.keyDown, MOVEMENT_OWNER, fwd < 0);
                SimulatedInput.set(mc.options.keyLeft, MOVEMENT_OWNER, side > 0);
                SimulatedInput.set(mc.options.keyRight, MOVEMENT_OWNER, side < 0);
                SimulatedInput.set(mc.options.keyJump, MOVEMENT_OWNER, playback.getJump());
                SimulatedInput.set(mc.options.keyShift, MOVEMENT_OWNER, playback.getSneak());
                SimulatedInput.set(mc.options.keySprint, MOVEMENT_OWNER, playback.getSprint());
                SimulatedInput.set(mc.options.keyAttack, MOVEMENT_OWNER, playback.getLeftClick());
                SimulatedInput.set(mc.options.keyUse, MOVEMENT_OWNER, playback.getRightClick());
                return;
            }

            RouteFlowRuntime routes = RouteFlowRuntime.getInstance();
            if (AutoForwardFeature.isEnabled() || routes.isForwardActive()) {
                SimulatedInput.hold(mc.options.keyUp, MOVEMENT_OWNER);
                SimulatedInput.set(mc.options.keySprint, MOVEMENT_OWNER, routes.isSprintRequested());
            }
        }
    }
}
