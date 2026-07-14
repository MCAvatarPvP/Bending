package com.projectkorra.projectkorra.platform.mc;

public class Effect {
    public static final Effect EXTINGUISH = new Effect("EXTINGUISH");
    public static final Effect GHAST_SHOOT = new Effect("GHAST_SHOOT");
    public static final Effect MOBSPAWNER_FLAMES = new Effect("MOBSPAWNER_FLAMES");
    public static final Effect SMOKE = new Effect("SMOKE");
    public static final Effect STEP_SOUND = new Effect("STEP_SOUND");
    private final String name;
    public Effect() {
        this.name = getClass().getSimpleName();
    }
    private Effect(String name) {
        this.name = name;
    }

    public static Effect valueOf(String name) {
        return new Effect(name);
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
