package com.alonediamond.playercontrolpp.feature.automaterial;

import com.alonediamond.playercontrolpp.feature.automaterial.material.MaterialItemEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class GatherContext {
    private static final GatherContext INSTANCE = new GatherContext();

    public GatherContext getInstance() {
        return INSTANCE;
    }

    //材料上下文
    public List<MaterialItemEntry> missingItems = new ArrayList<>();//缺失材料

    public Set<Item> needItemTypes =  new HashSet<>();//当前所有所需材料

    public final List<BlockPos> foundBoxPositions = new ArrayList<>();//容器位置(潜影盒)

    public final List<BlockPos> foundItemPositions = new ArrayList<>();//容器位置(散装)

    public BlockPos currentContainerPos;//当前正在寻路的容器的位置
}
