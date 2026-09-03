package com.projectkorra.projectkorra.fabric;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirGliderRecipeDataTest {
    private static final List<String> COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    @Test
    void baseRecipeCreatesTheTaggedStick() {
        final JsonObject recipe = recipe("airglider");
        assertEquals("minecraft:crafting_shaped", recipe.get("type").getAsString());
        assertGliderResult(recipe, "classic");
    }

    @Test
    void everyVanillaWoolColorHasARecolorRecipe() {
        for (final String color : COLORS) {
            final JsonObject recipe = recipe("airglider_" + color);
            assertEquals("minecraft:crafting_shapeless", recipe.get("type").getAsString());
            final JsonArray ingredients = recipe.getAsJsonArray("ingredients");
            final JsonObject glider = ingredients.get(0).getAsJsonObject();
            assertEquals("fabric:custom_data", glider.get("fabric:type").getAsString());
            assertEquals("minecraft:stick", glider.get("base").getAsString());
            assertEquals("true", glider.getAsJsonObject("nbt")
                    .get("projectkorra:airglider").getAsString());
            assertEquals("minecraft:" + color + "_wool", ingredients.get(1).getAsString());
            assertGliderResult(recipe, color);
        }
    }

    private static void assertGliderResult(final JsonObject recipe, final String color) {
        final JsonObject result = recipe.getAsJsonObject("result");
        assertEquals("minecraft:stick", result.get("id").getAsString());
        final JsonObject components = result.getAsJsonObject("components");
        final JsonObject data = components.getAsJsonObject("minecraft:custom_data");
        assertEquals("true", data.get("projectkorra:airglider").getAsString());
        assertEquals(color, data.get("projectkorra:airglider_color").getAsString());
        final JsonArray lore = components.getAsJsonArray("minecraft:lore");
        assertEquals(4, lore.size());
        assertTrue(lore.get(0).getAsJsonObject().get("text").getAsString().contains("ability to be bound"));
        assertTrue(lore.get(1).getAsJsonObject().get("text").getAsString().contains("either hand"));
        final JsonArray name = components.getAsJsonObject("minecraft:custom_name").getAsJsonArray("extra");
        assertTrue(name.get(0).getAsJsonObject().get("color").getAsString().startsWith("#"));
        assertTrue(name.get(2).getAsJsonObject().get("color").getAsString().startsWith("#"));
    }

    private static JsonObject recipe(final String name) {
        final String path = "/data/projectkorra/recipe/" + name + ".json";
        final var stream = AirGliderRecipeDataTest.class.getResourceAsStream(path);
        assertNotNull(stream, path);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException exception) {
            throw new AssertionError("Unable to read " + path, exception);
        }
    }
}
