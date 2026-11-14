package com.alonediamond.playercontrolpp.feature.automaterial.manual;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 一个串行批次。数量是本批要新增的件数，不是扣除库存后的缺口。 */
public final class ManualMaterialBatch {
    private String name;
    private final Map<String, Integer> amounts = new LinkedHashMap<>();
    private final Map<String, Integer> view = Collections.unmodifiableMap(amounts);

    public ManualMaterialBatch(String name) {
        setName(name);
    }

    public String name() { return name; }

    public void setName(String name) {
        this.name = name == null ? "" : name.strip();
    }

    public Map<String, Integer> amounts() { return view; }

    public int amount(String itemId) { return amounts.getOrDefault(itemId, 0); }

    public void setAmount(String itemId, int amount) {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        amounts.put(itemId, amount);
    }

    public boolean removeItem(String itemId) { return amounts.remove(itemId) != null; }

    public boolean isEmpty() { return amounts.isEmpty(); }
}
