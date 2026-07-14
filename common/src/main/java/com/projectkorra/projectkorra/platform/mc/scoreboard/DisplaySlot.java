package com.projectkorra.projectkorra.platform.mc.scoreboard;

public class DisplaySlot {
    public static final DisplaySlot SIDEBAR = new DisplaySlot("SIDEBAR");
    private final String name;

    public DisplaySlot() {
        this.name = getClass().getSimpleName();
    }

    private DisplaySlot(String name) {
        this.name = name;
    }

    public static DisplaySlot valueOf(String name) {
        return new DisplaySlot(name);
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
