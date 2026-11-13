package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import net.minecraft.world.item.Items;

import java.util.List;

/** 固定分批顺序、父帧推进时机与数量取整，防止重构改变采集计划。 */
public final class RawMaterialPlanRegression {
    private static int checks;

    public static int run() {
        checks = 0;
        RawMaterialPlan plan = new RawMaterialPlan();
        RawNode root = new RawNode("minecraft:piston", 130, "crafting_shaped", List.of());
        check(plan.pushBatches(Items.PISTON, root, 130, 500, null) == 3, "130 个活塞分为三批");
        check(plan.current().need == 64 && plan.current().targetTotal == 500, "整组先执行且保留绝对目标");
        check(!plan.finishFrame() && plan.current().need == 64, "第二批仍是整组");
        check(!plan.finishFrame() && plan.current().need == 2, "余数批次最后执行");
        check(plan.finishFrame() && plan.isEmpty(), "最后一批完成后结束计划");

        plan.pushBatches(Items.PISTON, root, 1, 500, null);
        RawMaterialPlan.Frame parent = plan.current();
        RawNode child = new RawNode("minecraft:oak_planks", 100, "crafting_shapeless", List.of());
        plan.pushBatches(Items.OAK_PLANKS, child, 100, 120, parent);
        check(plan.current().need == 64 && !plan.current().advanceParent, "子材料第一批不提前推进父帧");
        plan.finishFrame();
        check(parent.nextChild == 0 && plan.current().need == 36, "子材料尚有余数时父帧继续等待");
        plan.finishFrame();
        check(plan.current() == parent && parent.nextChild == 1, "子材料最后一批结束只推进父帧一次");
        plan.clear();
        check(plan.isEmpty(), "取消后清空整份计划");

        check(RawMaterialPlan.scaleCount(3, 1, 2) == 2, "子材料数向上取整");
        check(RawMaterialPlan.scaleCount(8, 4, 4) == 8, "完整批次保持原数量");
        check(RawMaterialPlan.scaleCount(7, 3, 0) == 7, "父节点数量无效时保留来源数量");
        check(RawMaterialPlan.scaleCount(Integer.MAX_VALUE, Integer.MAX_VALUE, 1) == Integer.MAX_VALUE,
                "大数量使用 long 中间值并封顶");
        return checks;
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
        checks++;
    }
}
