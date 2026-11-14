package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.feature.ClientFeature;
import net.minecraft.client.Minecraft;

/** 来源观察的统一 tick 入口；仅推进已请求的任务，不主动启动采集。 */
public final class MiningSurveyFeature implements ClientFeature {
    public static final MiningSurveyFeature INSTANCE = new MiningSurveyFeature();
    private MiningSurveyFeature() {}
    @Override
    public void onClientTick(Minecraft mc) { KnownMiningSurvey.tick(); }
    @Override
    public void onWorldChange() { KnownMiningSurvey.clear(); }
}
