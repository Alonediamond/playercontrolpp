package com.alonediamond.playercontrolpp.feature.automaterial.crafting;

import java.util.concurrent.TimeUnit;

/** 从本批首次快照起计时。切换扫描阶段、工作线程排队和失效重算共用同一个期限。 */
record WholeOrderWait(long startedNanos, int seconds) {
    boolean expired() {
        return expiredAt(System.nanoTime());
    }

    boolean expiredAt(long now) {
        return seconds > 0 && now - startedNanos >= TimeUnit.SECONDS.toNanos(seconds);
    }
}
