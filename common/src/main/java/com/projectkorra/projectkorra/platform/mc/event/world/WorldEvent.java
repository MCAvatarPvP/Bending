package com.projectkorra.projectkorra.platform.mc.event.world;

import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.event.Event;

public class WorldEvent extends Event {
    private final World world;

    public WorldEvent() {
        this(new World());
    }

    public WorldEvent(World world) {
        this.world = world;
    }

    public World getWorld() {
        return world;
    }
}
