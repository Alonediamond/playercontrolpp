package com.alonediamond.playercontrolpp.interaction;

import com.alonediamond.playercontrolpp.inventory.ContainerMenus;
import net.minecraft.client.Minecraft;

/**
 * 两种容器缓存共用的开箱等待过程。
 * 内容非空即继续，空菜单保留原有的有限宽限；它不是服务端同步确认器。
 */
public final class ContainerOpenWait {
    public enum Result { WAITING, READY, TIMED_OUT }

    private final int openTimeout;
    private final int contentGrace;
    private int openTicksRemaining;
    private int contentTicksRemaining;

    public ContainerOpenWait(int openTimeout, int contentGrace) {
        this.openTimeout = openTimeout;
        this.contentGrace = contentGrace;
    }

    public void begin() {
        openTicksRemaining = openTimeout;
        contentTicksRemaining = contentGrace;
    }

    public Result tick(Minecraft mc) {
        boolean menuOpen = ContainerMenus.isOpen(mc);
        return tick(menuOpen, menuOpen && ContainerMenus.hasContainerItems(mc));
    }

    /** 一个调用对应一个客户端 tick，菜单出现后不再消耗开箱超时。 */
    public Result tick(boolean menuOpen, boolean hasItems) {
        if (menuOpen) {
            if (!hasItems && --contentTicksRemaining > 0) return Result.WAITING;
            return Result.READY;
        }
        return --openTicksRemaining <= 0 ? Result.TIMED_OUT : Result.WAITING;
    }
}
