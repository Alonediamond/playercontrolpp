package com.alonediamond.playercontrolpp.feature.automaterial.restock;

import com.alonediamond.playercontrolpp.integration.LitematicaIntegration.SelectionBounds;
import net.minecraft.core.BlockPos;

import java.util.List;

/** 验证分层规则，不把 Baritone 进程结束或未知区块当成建造完成。 */
public final class LayerBuildControllerRegression {
    private static int checks;

    public static int run() {
        checks = 0;
        nextLayerSkipsGaps();
        completionRequiresStableScans();
        unknownAndMissingInvalidateConfirmation();
        return checks;
    }

    private static void nextLayerSkipsGaps() {
        List<SelectionBounds> regions = List.of(box(10, 12), box(20, 22));
        check(LayerBuildRules.nextLayer(regions, 10, 1).orElseThrow() == 11, "向上逐层推进");
        check(LayerBuildRules.nextLayer(regions, 12, 1).orElseThrow() == 20, "向上跨过子区之间的空洞层");
        check(LayerBuildRules.nextLayer(regions, 20, -1).orElseThrow() == 12, "向下跨过空洞层");
        check(LayerBuildRules.nextLayer(regions, 22, -1).orElseThrow() == 21, "向下逐层推进");
        check(LayerBuildRules.nextLayer(regions, 22, 1).isEmpty(), "最高层之后结束");
        check(LayerBuildRules.nextLayer(regions, 10, -1).isEmpty(), "最低层之后结束");
        check(LayerBuildRules.nextLayer(regions, 15, 1).orElseThrow() == 20, "初始层在空洞内仍找到上方子区");
        check(LayerBuildRules.nextLayer(regions, 15, -1).orElseThrow() == 12, "初始层在空洞内仍找到下方子区");
        check(LayerBuildRules.nextLayer(List.of(box(10, 15), box(12, 20)), 13, 1).orElseThrow() == 14,
                "重叠子区不重复推进");
        check(LayerBuildRules.nextLayer(List.of(), 10, 1).isEmpty(), "没有启用子区时没有下一层");
        check(LayerBuildRules.nextLayer(regions, 10, 0).isEmpty(), "零方向不推进");
        check(LayerBuildRules.nextLayer(List.of(box(Integer.MAX_VALUE, Integer.MAX_VALUE)),
                Integer.MAX_VALUE, 1).isEmpty(), "向上终点计算不溢出");
        check(LayerBuildRules.nextLayer(List.of(box(Integer.MIN_VALUE, Integer.MIN_VALUE)),
                Integer.MIN_VALUE, -1).isEmpty(), "向下终点计算不溢出");
    }

    private static void completionRequiresStableScans() {
        var completion = new LayerBuildRules.StableCompletion();
        check(!completion.confirm(10, false, false, 5), "首次完整无缺扫描不能确认完成");
        check(!completion.confirm(14, false, false, 5), "重复扫描仍须等稳定窗口");
        check(completion.confirm(15, false, false, 5), "窗口结束且重复扫描后才确认完成");
        completion.reset();
        check(!completion.confirm(20, false, false, 0), "零稳定窗口也要求两次完整扫描");
        check(!completion.confirm(20, false, false, 0), "同tick重复提交不能伪造第二次观察");
        check(completion.confirm(21, false, false, 0), "不同tick的第二次无缺扫描可确认");
        completion.reset();
        check(!completion.confirm(30, false, false, 1), "换层或停止后丢弃上层确认");
        check(!completion.confirm(29, false, false, 1), "时间回退重新开始确认");
        check(completion.confirm(30, false, false, 1), "回退后的新稳定窗口独立计时");
    }

    private static void unknownAndMissingInvalidateConfirmation() {
        var completion = new LayerBuildRules.StableCompletion();
        check(!completion.confirm(0, false, false, 5), "首次确认允许从tick零开始");
        check(!completion.confirm(10, true, false, 5), "未加载区块阻止翻层");
        check(!completion.confirm(20, false, false, 5), "区块加载后必须重新建立首个完成观察");
        check(!completion.confirm(24, false, false, 5), "未知状态之前的时间不计入新窗口");
        check(completion.confirm(25, false, false, 5), "新窗口稳定后才可翻层");
        check(!completion.confirm(26, false, true, 5), "再次出现缺失方块立即取消已完成确认");
        check(!completion.confirm(40, false, false, 5), "缺失修复后重新开始窗口");
        check(completion.confirm(45, false, false, 5), "修复后经过独立窗口可完成");
    }

    private static SelectionBounds box(int minY, int maxY) {
        return new SelectionBounds(new BlockPos(0, minY, 0), new BlockPos(1, maxY, 1));
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
        checks++;
    }
}
