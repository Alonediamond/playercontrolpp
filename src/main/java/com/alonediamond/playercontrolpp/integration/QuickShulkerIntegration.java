package com.alonediamond.playercontrolpp.integration;

import com.alonediamond.playercontrolpp.util.ItemUtil;
import com.alonediamond.playercontrolpp.util.PlayerUtil;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * QuickShulker 联动，全部走反射，模组保持可选。
 * 调 QuickShulker 自己的 {@code OpenShulkerPacket.sendOpenPacket(int)} 直接在物品栏里开盒。
 */
public class QuickShulkerIntegration implements ModIntegration {

    /**
     * {@code InventoryMenu} 界面空间里快捷栏的第一个槽位。原版有
     * {@code InventoryMenu.USE_ROW_SLOT_START}，但本项目一份源码要编多个版本，
     * 所以常量放这里，理由同 {@link PlayerUtil#HOTBAR_SIZE}。
     */
    private static final int MENU_HOTBAR_START = 36;

    /** {@code InventoryMenu} 界面空间里的副手槽（{@code InventoryMenu.SHIELD_SLOT}）。 */
    public static final int MENU_OFFHAND_SLOT = 45;

    private static final QuickShulkerIntegration INSTANCE = new QuickShulkerIntegration();
    private boolean loaded;

    private QuickShulkerIntegration() {}

    public static QuickShulkerIntegration getInstance() { return INSTANCE; }

    @Override
    public boolean isLoaded() { return loaded; }

    @Override
    public void initialize() {
        loaded = FabricLoader.getInstance().isModLoaded("quickshulker");
    }

    /**
     * 把 {@link Inventory} 索引换算成 QuickShulker 要的 {@code InventoryMenu} 界面槽位。
     *
     * <p>两套编号在主背包上一致、在快捷栏上不一致：
     * <pre>
     * 物品栏 0-8   （快捷栏）  → 菜单 36-44
     * 物品栏 9-35  （主背包）  → 菜单  9-35
     * </pre>
     * 所以直接传物品栏索引对主背包是「碰巧对」，对快捷栏则会落到合成结果格、合成格和盔甲格上——
     * 这正是放在快捷栏的潜影盒静默打不开的原因。QuickShulker 自己对手持物品也做同样的换算
     * （{@code 36 + getSelectedSlot()}）。
     *
     * @param inventorySlot {@link Inventory} 空间的槽位索引，0-35
     * @return 对应的 {@code InventoryMenu} 槽位索引
     */
    public static int menuSlotForInventorySlot(int inventorySlot) {
        return inventorySlot < PlayerUtil.HOTBAR_SIZE
                ? MENU_HOTBAR_START + inventorySlot
                : inventorySlot;
    }

    /**
     * @return QuickShulker 能不能打开这一堆物品。
     *
     * <p>潜影盒注册时没有设 {@code ignoreSingleStackCheck}，所以 QuickShulker 只开<em>单个</em>盒子：
     * 它自己的客户端路径要求 {@code getCount() <= 1}，而服务端监听器一旦发现该槽数量不是 1
     * 就强制关掉界面。对着两个以上的盒子开，表现是「刚开就死」，调用方不如直接跳过。
     */
    public static boolean isOpenableShulkerBox(ItemStack stack) {
        return ItemUtil.isShulkerBox(stack) && stack.getCount() == 1;
    }

    /**
     * 通过 QuickShulker 自己的发包函数打开潜影盒。
     *
     * <p>服务端是对着 {@code player.containerMenu} 解析槽位的，所以只有当前打开的是玩家自己的
     * 物品栏菜单时这个调用才有意义——开着箱子时同一个索引指向完全不同的槽位。
     * 前置条件由 {@link #canOpenFromInventory(Minecraft)} 检查。
     *
     * @param menuSlot {@code InventoryMenu} 界面空间的槽位索引，从物品栏索引换算请用
     *                 {@link #menuSlotForInventorySlot(int)}
     * @return 仅在 QuickShulker 未加载或反射失败时返回 false
     */
    public boolean openShulkerBox(int menuSlot) {
        if (!loaded) return false;

        try {
            // OpenShulkerPacket.sendOpenPacket(menuSlot)
            Class<?> packetClass = Class.forName(
                    "net.kyrptonaught.quickshulker.network.OpenShulkerPacket");
            packetClass.getMethod("sendOpenPacket", int.class).invoke(null, menuSlot);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @return 现在传物品栏空间的槽位索引会不会被正确解析，也就是当前打开的容器是否为玩家自己的物品栏菜单。
     */
    public boolean canOpenFromInventory(Minecraft mc) {
        return loaded && mc.player != null && mc.player.containerMenu == mc.player.inventoryMenu;
    }
}
