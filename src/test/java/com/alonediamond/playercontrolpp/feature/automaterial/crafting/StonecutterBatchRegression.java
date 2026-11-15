package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

/** 验证实际产量、已投入数量及本批预算共同限制切石输入。 */
public final class StonecutterBatchRegression {
    public static int run() {
        check(inputs(64, 1, 1, 63) == 63, "一比一配方一次补足整批");
        check(inputs(64, 2, 1, 31) == 31, "半砖按实际双倍产量只投入32个原料");
        check(inputs(5, 2, 1, 2) == 2, "非整除产量向上取整");
        check(inputs(64, 1, 1, 7) == 7, "富余库存不能突破本批投入预算");
        check(inputs(0, 2, 1, 63) == 0, "已满足目标时不补料");
        check(inputs(32, 1, 32, 63) == 0, "输入槽已有足量材料时不重复补料");
        check(inputs(32, 1, 0, 0) == 0, "预算耗尽后不动用预留");
        check(inputs(Integer.MAX_VALUE, 2, 1, 63) == 63, "大目标不会整数溢出");
        return 8;
    }

    private static int inputs(int output, int yield, int current, int budget) {
        return CraftingController.stonecutterInputsToAdd(output, yield, current, budget);
    }

    private static void check(boolean condition, String scenario) {
        if (!condition) throw new AssertionError(scenario);
    }
}
