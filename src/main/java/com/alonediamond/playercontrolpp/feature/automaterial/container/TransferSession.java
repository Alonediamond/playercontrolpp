package com.alonediamond.playercontrolpp.feature.automaterial.container;

import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** 一次打开容器期间的转移进展。扫描不计次数，只有实际尝试才记录来源槽快照。 */
public final class TransferSession {
    public enum Result { MOVED, UNCHANGED, EXHAUSTED }
    private Object menu;
    private final Map<Integer, ItemStack> attempts = new HashMap<>();

    /** 换到另一个菜单时重新判断容量与槽位，不能继承上个盒子的失败记录。 */
    public void bind(Object menu) {
        if (this.menu != menu) {
            reset();
            this.menu = menu;
        }
    }

    public void reset() { menu = null; attempts.clear(); }

    public boolean canAttempt(Slot slot) {
        ItemStack before = attempts.get(slot.index);
        ItemStack current = slot.getItem();
        // 数量没有减少（含服务端回滚）时不重复发同一操作；下一次开箱会重新允许。
        return before == null || !ItemStack.isSameItemSameComponents(before, current)
                || current.getCount() < before.getCount();
    }

    public Result attempt(Slot slot, ToIntFunction<Slot> transfer) {
        if (!canAttempt(slot)) return Result.UNCHANGED;
        attempts.put(slot.index, slot.getItem().copy());
        return transfer.applyAsInt(slot) > 0 ? Result.MOVED : Result.UNCHANGED;
    }

    /** 每次只尝试一个符合条件的槽位；无关物品和已拒绝槽位只扫描，不消耗操作预算。 */
    public Result transferNext(List<Slot> slots, Predicate<Slot> eligible, ToIntFunction<Slot> transfer) {
        for (Slot slot : slots) {
            if (slot.hasItem() && eligible.test(slot) && canAttempt(slot)) return attempt(slot, transfer);
        }
        return Result.EXHAUSTED;
    }
}
