package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.airbending.AirGliderItem;
import com.projectkorra.projectkorra.object.GliderColor;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Registers the tagged AirGlider and wool recolor recipes with Bukkit's recipe book. */
public final class BukkitAirGliderRecipes {
    private static final Set<NamespacedKey> KEYS = new LinkedHashSet<>();

    private BukkitAirGliderRecipes() {
    }

    public static void register(final JavaPlugin plugin) {
        unregister();
        final GliderColor classic = GliderColor.getDefault();
        if (classic == null) return;

        final NamespacedKey baseKey = new NamespacedKey(plugin, "airglider");
        final ShapedRecipe base = new ShapedRecipe(baseKey,
                BukkitMC.itemHandle(AirGliderItem.create(classic)));
        base.shape("S S", "OYO", " S ");
        base.setIngredient('S', Material.STICK);
        base.setIngredient('O', Material.ORANGE_WOOL);
        base.setIngredient('Y', Material.YELLOW_WOOL);
        base.setGroup("projectkorra_airglider");
        base.setCategory(CraftingBookCategory.EQUIPMENT);
        add(baseKey, base);

        final List<org.bukkit.inventory.ItemStack> gliderChoices = new ArrayList<>();
        for (final GliderColor color : GliderColor.getColors()) {
            gliderChoices.add(BukkitMC.itemHandle(AirGliderItem.create(color)));
        }
        final RecipeChoice.ExactChoice anyGlider = new RecipeChoice.ExactChoice(gliderChoices);
        for (final GliderColor color : GliderColor.getColors()) {
            if (color.getWoolMaterial() == null) continue;
            final Material wool = Material.matchMaterial(color.getWoolMaterial().name());
            if (wool == null) continue;
            final NamespacedKey key = new NamespacedKey(plugin, "airglider_" + color.getName());
            final ShapelessRecipe recolor = new ShapelessRecipe(key,
                    BukkitMC.itemHandle(AirGliderItem.create(color)));
            recolor.addIngredient(anyGlider);
            recolor.addIngredient(wool);
            recolor.setGroup("projectkorra_airglider_colors");
            recolor.setCategory(CraftingBookCategory.EQUIPMENT);
            add(key, recolor);
        }
        Bukkit.getOnlinePlayers().forEach(BukkitAirGliderRecipes::discover);
    }

    public static void discover(final Player player) {
        if (player != null && !KEYS.isEmpty()) player.discoverRecipes(KEYS);
    }

    public static void unregister() {
        KEYS.forEach(Bukkit::removeRecipe);
        KEYS.clear();
    }

    private static void add(final NamespacedKey key, final org.bukkit.inventory.Recipe recipe) {
        if (Bukkit.addRecipe(recipe)) KEYS.add(key);
    }
}
