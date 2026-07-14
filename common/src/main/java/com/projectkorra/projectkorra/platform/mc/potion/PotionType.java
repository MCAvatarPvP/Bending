package com.projectkorra.projectkorra.platform.mc.potion;

import java.util.Locale;
import java.util.Objects;

public class PotionType {
    public static final PotionType WATER = new PotionType("WATER");
    private final String name;

    public PotionType() {
        this.name = getClass().getSimpleName();
    }

    private PotionType(String name) {
        this.name = name;
    }

    public static PotionType valueOf(String name) {
        return new PotionType(name);
    }

    public String name() {
        return this.name;
    }

    @Override
    public String toString() {
        return this.name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PotionType type && name.equalsIgnoreCase(type.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name.toUpperCase(Locale.ROOT));
    }

    public Object handle() {
        return this;
    }
}
