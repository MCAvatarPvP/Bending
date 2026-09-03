package com.projectkorra.projectkorra.fabric;

import net.minecraft.recipe.Recipe;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/** Makes ProjectKorra's data-pack recipes visible in each player's recipe book. */
final class FabricAirGliderRecipes {
    private static final List<String> COLORS = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final List<RegistryKey<Recipe<?>>> KEYS = recipeKeys();

    private FabricAirGliderRecipes() {
    }

    static void discover(final ServerPlayerEntity player) {
        player.unlockRecipes(KEYS);
    }

    private static List<RegistryKey<Recipe<?>>> recipeKeys() {
        final List<RegistryKey<Recipe<?>>> keys = new ArrayList<>();
        keys.add(key("airglider"));
        COLORS.forEach(color -> keys.add(key("airglider_" + color)));
        return List.copyOf(keys);
    }

    private static RegistryKey<Recipe<?>> key(final String path) {
        return RegistryKey.of(RegistryKeys.RECIPE, Identifier.of("projectkorra", path));
    }
}
