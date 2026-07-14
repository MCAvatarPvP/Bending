package com.projectkorra.projectkorra.platform.mc.inventory;

public class MainHand {
    public static final MainHand LEFT = new MainHand("LEFT");
    public static final MainHand RIGHT = new MainHand("RIGHT");
    private final String name;

    public MainHand() {
        this.name = getClass().getSimpleName();
    }
    private MainHand(String name) {
        this.name = name;
    }

    public static MainHand valueOf(String name) {
        return new MainHand(name);
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
