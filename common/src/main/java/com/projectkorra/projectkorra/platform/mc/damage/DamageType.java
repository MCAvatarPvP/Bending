package com.projectkorra.projectkorra.platform.mc.damage;

public class DamageType {
    public static final DamageType GENERIC = new DamageType("GENERIC");
    private final String name;

    public DamageType() {
        this.name = getClass().getSimpleName();
    }

    private DamageType(String name) {
        this.name = name;
    }

    public static DamageType valueOf(String name) {
        return new DamageType(name);
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
