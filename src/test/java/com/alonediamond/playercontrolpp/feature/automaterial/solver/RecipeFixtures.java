package com.alonediamond.playercontrolpp.feature.automaterial.solver;

import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Hint;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Recipe;
import com.alonediamond.playercontrolpp.feature.automaterial.crafting.solver.SupplyModel.Slot;
import com.alonediamond.playercontrolpp.integration.LitematListIntegration.RawNode;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RecipeFixtures {
    static Hint hint(RawNode node) {
        return new Hint(node.itemId(), node.count(), node.recipeType(), node.children().stream().map(RecipeFixtures::hint).toList());
    }

    /** 从该 MC 版本的测试类路径读取指定配方。标签可展开成多个选择，但单一物品绝不按外观补充“类似材料”。 */
    static Recipe read(String id) {
        JsonObject json = resource("recipe", id);
        String type = json.get("type").getAsString();
        JsonObject result = json.getAsJsonObject("result");
        if (!Set.of("id", "count").containsAll(result.keySet())) throw new IllegalArgumentException("非固定普通产物: " + id);
        List<Slot> grid = new ArrayList<>(Collections.nCopies(9, new Slot(List.of())));
        switch (type) {
            case "minecraft:crafting_shaped" -> {
                JsonObject keys = json.getAsJsonObject("key");
                int row = 0;
                for (JsonElement line : json.getAsJsonArray("pattern")) {
                    String pattern = line.getAsString();
                    if (row >= 3 || pattern.length() > 3) throw new IllegalArgumentException("超出工作台: " + id);
                    for (int column = 0; column < pattern.length(); column++) {
                        String symbol = pattern.substring(column, column + 1);
                        if (!symbol.equals(" ")) grid.set(row * 3 + column, slot(keys.get(symbol)));
                    }
                    row++;
                }
            }
            case "minecraft:crafting_shapeless" -> {
                int index = 0;
                for (JsonElement ingredient : json.getAsJsonArray("ingredients")) grid.set(index++, slot(ingredient));
            }
            default -> throw new IllegalArgumentException("非普通工作台配方: " + type);
        }
        return new Recipe(id, type.substring("minecraft:".length()), result.get("id").getAsString(),
                result.has("count") ? result.get("count").getAsInt() : 1, grid);
    }

    private static Slot slot(JsonElement ingredient) {
        List<String> choices = new ArrayList<>();
        expand(ingredient, new HashSet<>(), choices);
        if (choices.isEmpty()) throw new IllegalArgumentException("无可验证的输入");
        return new Slot(choices);
    }

    private static void expand(JsonElement element, Set<String> tags, List<String> choices) {
        if (element.isJsonArray()) {
            for (JsonElement alternative : element.getAsJsonArray()) expand(alternative, tags, choices);
            return;
        }
        String value;
        if (element.isJsonPrimitive()) value = element.getAsString();
        else {
            JsonObject old = element.getAsJsonObject();
            if (old.keySet().equals(Set.of("item"))) value = old.get("item").getAsString();
            else if (old.keySet().equals(Set.of("tag"))) value = "#" + old.get("tag").getAsString();
            else throw new IllegalArgumentException("无法验证的输入: " + element);
        }
        if (!value.startsWith("#")) {
            choices.add(value);
            return;
        }
        if (!tags.add(value)) throw new IllegalArgumentException("循环标签: " + value);
        for (JsonElement child : resource("tags/item", value.substring(1)).getAsJsonArray("values"))
            expand(child, tags, choices);
        tags.remove(value);
    }

    private static JsonObject resource(String folder, String id) {
        String[] parts = id.split(":", 2);
        String path = "/data/" + parts[0] + "/" + folder + "/" + parts[1] + ".json";
        try (InputStream input = RecipeFixtures.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("缺少测试配方资源: " + path);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
