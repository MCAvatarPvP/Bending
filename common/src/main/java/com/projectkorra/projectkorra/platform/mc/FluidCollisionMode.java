package com.projectkorra.projectkorra.platform.mc;

public class FluidCollisionMode {
    public static final FluidCollisionMode NEVER = new FluidCollisionMode("NEVER");
    public static final FluidCollisionMode ALWAYS = new FluidCollisionMode("ALWAYS");
    private final String name;

    public FluidCollisionMode() {
        this.name = getClass().getSimpleName();
    }
    private FluidCollisionMode(String name) {
        this.name = name;
    }

    public static FluidCollisionMode valueOf(String name) {
        return new FluidCollisionMode(name);
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
