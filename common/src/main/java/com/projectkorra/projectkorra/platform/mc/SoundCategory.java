package com.projectkorra.projectkorra.platform.mc;

public class SoundCategory {
    public static final SoundCategory MASTER = new SoundCategory("MASTER");
    private final String name;

    public SoundCategory() {
        this.name = getClass().getSimpleName();
    }

    private SoundCategory(String name) {
        this.name = name;
    }

    public static SoundCategory valueOf(String name) {
        return new SoundCategory(name);
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
