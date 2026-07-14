package com.projectkorra.projectkorra.platform.mc;

public class Difficulty {
    public static final Difficulty PEACEFUL = new Difficulty("PEACEFUL");
    private final String name;

    public Difficulty() {
        this.name = getClass().getSimpleName();
    }

    private Difficulty(String name) {
        this.name = name;
    }

    public static Difficulty valueOf(String name) {
        return new Difficulty(name);
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
