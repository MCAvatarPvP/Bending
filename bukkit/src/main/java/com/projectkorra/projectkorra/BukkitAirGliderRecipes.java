package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.airbending.AirGliderItem;
import com.projectkorra.projectkorra.object.GliderColor;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.Set;

/** Registers one tagged AirGlider recipe for each vanilla wool color. */
public final class BukkitAirGliderRecipes {
    private static final Set<NamespacedKey> KEYS = new LinkedHashSet<>();

    private BukkitAirGliderRecipes() {
    }

    public static void register(final JavaPlugin plugin) {
        unregister();
        for (final GliderColor color : GliderColor.getColors()) {
            if (color.getWoolMaterial() == null) continue;
            final Material wool = Material.matchMaterial(color.getWoolMaterial().name());
            if (wool == null) continue;
            final NamespacedKey key = new NamespacedKey(plugin, "airglider_" + color.getName());
            final ShapedRecipe recipe = new ShapedRecipe(key,
                    BukkitMC.itemHandle(AirGliderItem.create(color)));
            recipe.shape("S S", "WWW", " S ");
            recipe.setIngredient('S', Material.STICK);
            recipe.setIngredient('W', wool);
            recipe.setGroup("projectkorra_airglider");
            recipe.setCategory(CraftingBookCategory.EQUIPMENT);
            add(key, recipe);
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
