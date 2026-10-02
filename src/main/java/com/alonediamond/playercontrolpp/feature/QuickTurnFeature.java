package com.alonediamond.playercontrolpp.feature;

import com.alonediamond.playercontrolpp.action.RotateAction;
import com.alonediamond.playercontrolpp.config.Configs;
import net.minecraft.client.Minecraft;

/** 快速转向：按配置的角度瞬间转身。 */
public class QuickTurnFeature {

    public static void execute(Minecraft client) {
        int angle = Configs.Settings.TURN_ANGLE.getIntegerValue();
        RotateAction.apply(client, angle);
    }
}
