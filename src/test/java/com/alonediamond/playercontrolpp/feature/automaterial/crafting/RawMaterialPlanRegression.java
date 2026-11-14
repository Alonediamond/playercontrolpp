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

        check(plan.pushBatches(Items.PISTON, root, Integer.MAX_VALUE, Integer.MAX_VALUE, null) == 33_554_432,
                "巨大需求只登记批次数，不一次分配数千万帧");
        check(plan.current().need == 64 && plan.current().remainingAfter == Integer.MAX_VALUE - 64,
                "惰性批次只保存当前数量和余量");
        check(plan.finishFrame(true) && plan.isEmpty(), "外部补齐后直接结束剩余批次");

        plan.pushBatches(Items.STICKY_PISTON, root, 2, 2, null);
        RawMaterialPlan.Frame outer = plan.current();
        outer.allocation = new RecipeAllocation(null, java.util.Map.of(Items.PISTON, 2, Items.SLIME_BALL, 2), 2, 0);
        plan.pushBatches(Items.PISTON, root, 2, 66, outer);
        RawMaterialPlan.Frame inner = plan.current();
        inner.allocation = new RecipeAllocation(null, java.util.Map.of(Items.OAK_PLANKS, 6, Items.COBBLESTONE, 8), 2, 0);
        check(RawMaterialPlan.reservedFor(inner, Items.SLIME_BALL, 5) == 7,
                "合成活塞时同时保留外层粘性活塞已备的粘液球");
        check(RawMaterialPlan.reservedFor(inner, Items.PISTON, 64) == 64,
                "正在生产的活塞不把父帧消费量重复计为预留");
        plan.pushBatches(Items.OAK_PLANKS, child, 6, 6, inner);
        check(RawMaterialPlan.reservedFor(plan.current(), Items.COBBLESTONE, 3) == 11,
                "继续追溯木板时保护活塞帧的圆石及清单预留");
        plan.clear();

        check(RawMaterialPlan.scaleCount(3, 1, 2) == 2, "子材料数向上取整");
        check(RawMaterialPlan.scaleCount(8, 4, 4) == 8, "完整批次保持原数量");
        check(RawMaterialPlan.scaleCount(7, 3, 0) == 7, "父节点数量无效时保留来源数量");
        check(RawMaterialPlan.scaleCount(Integer.MAX_VALUE, Integer.MAX_VALUE, 1) == Integer.MAX_VALUE,
                "大数量使用 long 中间值并封顶");
        int nestedTarget = RecipeAllocation.target(20, 10);
        check(nestedTarget == 30 && RawMaterialPlan.missingToProduce(nestedTarget, 25) == 5,
                "嵌套子材料只追溯总目标减现有库存的五件缺口");
        check(RawMaterialPlan.missingToProduce(nestedTarget, 35) == 0,
                "已有材料超过子目标时不重复追溯");
        return checks;
    }

    private static void check(boolean passed, String scenario) {
        if (!passed) throw new AssertionError(scenario);
        checks++;
    }
}
