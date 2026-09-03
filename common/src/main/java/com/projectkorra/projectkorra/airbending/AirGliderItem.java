package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.object.GliderColor;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;

import java.util.List;

/** Definition and validation for the reusable, craftable AirGlider item. */
public final class AirGliderItem {
    public static final Material MATERIAL = Material.STICK;
    public static final int CUSTOM_MODEL_DATA = 17001;
    public static final String ITEM_TAG = "projectkorra:airglider";
    public static final String COLOR_TAG = "projectkorra:airglider_color";
    private static final ChatColor TITLE_COLOR = ChatColor.of("#F2C14E");
    private static final ChatColor SEPARATOR_COLOR = ChatColor.of("#CBD5E1");
    private static final ChatColor REQUIREMENT_COLOR = ChatColor.of("#9FCDE3");
    private static final ChatColor INSTRUCTION_COLOR = ChatColor.of("#B6C2CC");
    private static final ChatColor LABEL_COLOR = ChatColor.of("#7F8C98");

    private AirGliderItem() {
    }

    public static ItemStack create(final GliderColor color) {
        final GliderColor selected = color == null ? GliderColor.getDefault() : color;
        final String colorName = selected == null ? "classic" : selected.getName();
        final ChatColor itemColor = ChatColor.of(textColor(colorName));
        final ItemStack item = new ItemStack(MATERIAL);
        final ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(TITLE_COLOR + "" + ChatColor.BOLD + "AirGlider"
                + SEPARATOR_COLOR + " • " + itemColor + displayName(colorName));
        meta.setLore(List.of(
                REQUIREMENT_COLOR + "Requires the AirGlider ability to be bound.",
                INSTRUCTION_COLOR + "Hold this item in either hand.",
                INSTRUCTION_COLOR + "Tap sneak while airborne to deploy.",
                LABEL_COLOR + "Color: " + itemColor + displayName(colorName)));
        meta.setCustomModelData(CUSTOM_MODEL_DATA);
        meta.setCustomData(ITEM_TAG, "true");
        meta.setCustomData(COLOR_TAG, colorName);
        item.setItemMeta(meta);
        return item;
    }

    public static GliderColor getColor(final ItemStack item) {
        if (item == null || item.getType() != MATERIAL || !item.hasItemMeta()) return null;
        final ItemMeta meta = item.getItemMeta();
        if (!"true".equals(meta.getCustomData(ITEM_TAG))) return null;
        return GliderColor.getColor(meta.getCustomData(COLOR_TAG));
    }

    public static GliderColor getHeldColor(final Player player) {
        if (player == null || player.getInventory() == null) return null;
        GliderColor color = getColor(player.getInventory().getItemInMainHand());
        if (color == null) color = getColor(player.getInventory().getItemInOffHand());
        return color;
    }

    private static String displayName(final String value) {
        if (value == null || value.isBlank()) return "Classic";
        final StringBuilder result = new StringBuilder(value.length());
        boolean capitalize = true;
        for (final char character : value.toCharArray()) {
            if (character == '_') {
                result.append(' ');
                capitalize = true;
            } else {
                result.append(capitalize ? Character.toUpperCase(character) : character);
                capitalize = false;
            }
        }
        return result.toString();
    }

    private static String textColor(final String colorName) {
        return switch (colorName) {
            case "white" -> "#FFFFFF";
            case "orange" -> "#FF9F43";
            case "magenta" -> "#E66DFF";
            case "light_blue" -> "#72D5FF";
            case "yellow" -> "#FFE66D";
            case "lime" -> "#9BE564";
            case "pink" -> "#FF9EC4";
            case "gray" -> "#8E9699";
            case "light_gray" -> "#C9D1D3";
            case "cyan" -> "#35D0BA";
            case "purple" -> "#B87BFF";
            case "blue" -> "#5F87FF";
            case "brown" -> "#B8835A";
            case "green" -> "#6FCF6A";
            case "red" -> "#FF6B6B";
            case "black" -> "#686D76";
            default -> "#F2C14E";
        };
    }
}
