package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import com.projectkorra.projectkorra.platform.mc.event.world.WorldEvent;
import com.projectkorra.projectkorra.util.Experimental;
import org.jetbrains.annotations.NotNull;

/**
 * An event that is called when the time of a world changes. This is used for Day/Night factors for water and
 * fire elements.
 */
@Experimental
public class WorldTimeEvent extends WorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();
    private Time from;
    private Time to;
    public WorldTimeEvent(@NotNull World world, Time from, Time to) {
        super(world);

        this.from = from;
        this.to = to;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Time getFrom() {
        return from;
    }

    public Time getTo() {
        return to;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public enum Time {
        DAY, DUSK, NIGHT, DAWN,
    }
}
