package com.projectkorra.projectkorra.platform.mc;

import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;

public class Particle {
    public static final Particle ANGRY_VILLAGER = new Particle("ANGRY_VILLAGER");
    public static final Particle ASH = new Particle("ASH");
    public static final Particle BLOCK = new Particle("BLOCK");
    public static final Particle BUBBLE = new Particle("BUBBLE");
    public static final Particle BUBBLE_COLUMN_UP = new Particle("BUBBLE_COLUMN_UP");
    public static final Particle BUBBLE_POP = new Particle("BUBBLE_POP");
    public static final Particle CAMPFIRE_COSY_SMOKE = new Particle("CAMPFIRE_COSY_SMOKE");
    public static final Particle CAMPFIRE_SIGNAL_SMOKE = new Particle("CAMPFIRE_SIGNAL_SMOKE");
    public static final Particle CLOUD = new Particle("CLOUD");
    public static final Particle COMPOSTER = new Particle("COMPOSTER");
    public static final Particle COPPER_FIRE_FLAME = new Particle("COPPER_FIRE_FLAME");
    public static final Particle CRIMSON_SPORE = new Particle("CRIMSON_SPORE");
    public static final Particle CRIT = new Particle("CRIT");
    public static final Particle CURRENT_DOWN = new Particle("CURRENT_DOWN");
    public static final Particle DAMAGE_INDICATOR = new Particle("DAMAGE_INDICATOR");
    public static final Particle DOLPHIN = new Particle("DOLPHIN");
    public static final Particle DRAGON_BREATH = new Particle("DRAGON_BREATH");
    public static final Particle DRIPPING_HONEY = new Particle("DRIPPING_HONEY");
    public static final Particle DRIPPING_LAVA = new Particle("DRIPPING_LAVA");
    public static final Particle DRIPPING_OBSIDIAN_TEAR = new Particle("DRIPPING_OBSIDIAN_TEAR");
    public static final Particle DRIPPING_WATER = new Particle("DRIPPING_WATER");
    public static final Particle DUST = new Particle("DUST");
    public static final Particle EFFECT = new Particle("EFFECT");
    public static final Particle ELECTRIC_SPARK = new Particle("ELECTRIC_SPARK");
    public static final Particle ELDER_GUARDIAN = new Particle("ELDER_GUARDIAN");
    public static final Particle ENCHANT = new Particle("ENCHANT");
    public static final Particle ENCHANTED_HIT = new Particle("ENCHANTED_HIT");
    public static final Particle END_ROD = new Particle("END_ROD");
    public static final Particle ENTITY_EFFECT = new Particle("ENTITY_EFFECT");
    public static final Particle EXPLOSION = new Particle("EXPLOSION");
    public static final Particle EXPLOSION_EMITTER = new Particle("EXPLOSION_EMITTER");
    public static final Particle FALLING_DUST = new Particle("FALLING_DUST");
    public static final Particle FALLING_HONEY = new Particle("FALLING_HONEY");
    public static final Particle FALLING_LAVA = new Particle("FALLING_LAVA");
    public static final Particle FALLING_NECTAR = new Particle("FALLING_NECTAR");
    public static final Particle FALLING_OBSIDIAN_TEAR = new Particle("FALLING_OBSIDIAN_TEAR");
    public static final Particle FALLING_WATER = new Particle("FALLING_WATER");
    public static final Particle FIREWORK = new Particle("FIREWORK");
    public static final Particle FISHING = new Particle("FISHING");
    public static final Particle FLAME = new Particle("FLAME");
    public static final Particle FLASH = new Particle("FLASH");
    public static final Particle HAPPY_VILLAGER = new Particle("HAPPY_VILLAGER");
    public static final Particle HEART = new Particle("HEART");
    public static final Particle INSTANT_EFFECT = new Particle("INSTANT_EFFECT");
    public static final Particle ITEM = new Particle("ITEM");
    public static final Particle ITEM_SLIME = new Particle("ITEM_SLIME");
    public static final Particle ITEM_SNOWBALL = new Particle("ITEM_SNOWBALL");
    public static final Particle LANDING_HONEY = new Particle("LANDING_HONEY");
    public static final Particle LANDING_LAVA = new Particle("LANDING_LAVA");
    public static final Particle LANDING_OBSIDIAN_TEAR = new Particle("LANDING_OBSIDIAN_TEAR");
    public static final Particle LARGE_SMOKE = new Particle("LARGE_SMOKE");
    public static final Particle LAVA = new Particle("LAVA");
    public static final Particle MYCELIUM = new Particle("MYCELIUM");
    public static final Particle NAUTILUS = new Particle("NAUTILUS");
    public static final Particle NOTE = new Particle("NOTE");
    public static final Particle POOF = new Particle("POOF");
    public static final Particle PORTAL = new Particle("PORTAL");
    public static final Particle RAIN = new Particle("RAIN");
    public static final Particle REVERSE_PORTAL = new Particle("REVERSE_PORTAL");
    public static final Particle SCRAPE = new Particle("SCRAPE");
    public static final Particle SMALL_GUST = new Particle("SMALL_GUST");
    public static final Particle GUST = new Particle("GUST");
    public static final Particle SMOKE = new Particle("SMOKE");
    public static final Particle SNEEZE = new Particle("SNEEZE");
    public static final Particle SOUL = new Particle("SOUL");
    public static final Particle SOUL_FIRE_FLAME = new Particle("SOUL_FIRE_FLAME");
    public static final Particle SPIT = new Particle("SPIT");
    public static final Particle SPLASH = new Particle("SPLASH");
    public static final Particle SQUID_INK = new Particle("SQUID_INK");
    public static final Particle SWEEP_ATTACK = new Particle("SWEEP_ATTACK");
    public static final Particle TOTEM_OF_UNDYING = new Particle("TOTEM_OF_UNDYING");
    public static final Particle WARPED_SPORE = new Particle("WARPED_SPORE");
    public static final Particle WAX_OFF = new Particle("WAX_OFF");
    public static final Particle WAX_ON = new Particle("WAX_ON");
    public static final Particle WHITE_ASH = new Particle("WHITE_ASH");
    public static final Particle WHITE_SMOKE = new Particle("WHITE_SMOKE");
    public static final Particle WITCH = new Particle("WITCH");
    private final String name;
    public Particle() {
        this.name = getClass().getSimpleName();
    }
    private Particle(String name) {
        this.name = name;
    }

    public static Particle valueOf(String name) {
        return new Particle(name);
    }

    public String name() {
        return this.name;
    }

    @Override
    public String toString() {
        return this.name;
    }

    public Object handle() {
        return this;
    }

    public Class<?> getDataType() {
        return switch (this.name) {
            case "BLOCK", "BLOCK_CRACK", "BLOCK_DUST", "FALLING_DUST" -> BlockData.class;
            case "ITEM", "ITEM_CRACK" -> ItemStack.class;
            case "DUST", "REDSTONE", "RED_DUST" -> DustOptions.class;
            case "ENTITY_EFFECT", "EFFECT" -> Color.class;
            default -> Void.class;
        };
    }

    public static class DustOptions {
        private final Color color;
        private final float size;

        public DustOptions(Object color, float size) {
            this.color = color instanceof Color c ? c : Color.WHITE;
            this.size = size;
        }

        public Color getColor() {
            return color;
        }

        public float getSize() {
            return size;
        }
    }

    public static class Spell {
        private final Color color;
        private final float power;

        public Spell(Color color, float power) {
            this.color = color == null ? Color.WHITE : color;
            this.power = power;
        }

        public Color getColor() {
            return color;
        }

        public float getPower() {
            return power;
        }
    }
}
