package com.projectkorra.projectkorra.platform.mc;

public class Tag<T> {
    public static final Tag<Material> LEAVES = new Tag<>();
    public static final Tag<Material> SLABS = new Tag<>();

    public boolean isTagged(T value) {
        return false;
    }

    public Object handle() {
        return this;
    }
}
