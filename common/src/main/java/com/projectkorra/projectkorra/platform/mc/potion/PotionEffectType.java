package com.projectkorra.projectkorra.platform.mc.potion;

public class PotionEffectType {
    public static final PotionEffectType ABSORPTION = new PotionEffectType("ABSORPTION");
    public static final PotionEffectType BLINDNESS = new PotionEffectType("BLINDNESS");
    public static final PotionEffectType FIRE_RESISTANCE = new PotionEffectType("FIRE_RESISTANCE");
    public static final PotionEffectType GLOWING = new PotionEffectType("GLOWING");
    public static final PotionEffectType LEVITATION = new PotionEffectType("LEVITATION");
    public static final PotionEffectType SLOW_FALLING = new PotionEffectType("SLOW_FALLING");
    public static final PotionEffectType DOLPHINS_GRACE = new PotionEffectType("DOLPHINS_GRACE");
    public static final PotionEffectType HASTE = new PotionEffectType("HASTE");
    public static final PotionEffectType HEALTH_BOOST = new PotionEffectType("HEALTH_BOOST");
    public static final PotionEffectType HUNGER = new PotionEffectType("HUNGER");
    public static final PotionEffectType INSTANT_DAMAGE = new PotionEffectType("INSTANT_DAMAGE");
    public static final PotionEffectType INSTANT_HEALTH = new PotionEffectType("INSTANT_HEALTH");
    public static final PotionEffectType INVISIBILITY = new PotionEffectType("INVISIBILITY");
    public static final PotionEffectType JUMP_BOOST = new PotionEffectType("JUMP_BOOST");
    public static final PotionEffectType MINING_FATIGUE = new PotionEffectType("MINING_FATIGUE");
    public static final PotionEffectType NAUSEA = new PotionEffectType("NAUSEA");
    public static final PotionEffectType NIGHT_VISION = new PotionEffectType("NIGHT_VISION");
    public static final PotionEffectType POISON = new PotionEffectType("POISON");
    public static final PotionEffectType REGENERATION = new PotionEffectType("REGENERATION");
    public static final PotionEffectType RESISTANCE = new PotionEffectType("RESISTANCE");
    public static final PotionEffectType SATURATION = new PotionEffectType("SATURATION");
    public static final PotionEffectType SLOWNESS = new PotionEffectType("SLOWNESS");
    public static final PotionEffectType SPEED = new PotionEffectType("SPEED");
    public static final PotionEffectType STRENGTH = new PotionEffectType("STRENGTH");
    public static final PotionEffectType WATER_BREATHING = new PotionEffectType("WATER_BREATHING");
    public static final PotionEffectType WEAKNESS = new PotionEffectType("WEAKNESS");
    public static final PotionEffectType WITHER = new PotionEffectType("WITHER");
    private final String name;
    public PotionEffectType() {
        this.name = getClass().getSimpleName();
    }
    private PotionEffectType(String name) {
        this.name = name;
    }

    public static PotionEffectType valueOf(String name) {
        return new PotionEffectType(name);
    }

    public static PotionEffectType getByName(String name) {
        return valueOf(name);
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
}
