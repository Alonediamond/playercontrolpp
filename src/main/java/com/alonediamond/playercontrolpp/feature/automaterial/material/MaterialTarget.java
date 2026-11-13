package com.alonediamond.playercontrolpp.feature.automaterial.material;

import net.minecraft.world.item.Item;

/** 当前采集物品与目标总量成对切换，避免追溯子任务只更新其中一项。 */
public record MaterialTarget(Item item, int neededTotal) {}
