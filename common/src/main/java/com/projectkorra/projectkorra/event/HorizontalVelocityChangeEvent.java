package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/**
 * Created by Carbogen on 2/2/2015.
 */
public class HorizontalVelocityChangeEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final Entity entity;
    private final Player instigator;
    private final Vector from;
    private final Vector to;
    private final Vector difference;
    private boolean isCancelled;
    private Location start;
    private Location end;
    private Ability abil;

    @Deprecated
    public HorizontalVelocityChangeEvent(final Entity entity, final Player instigator, final Vector from, final Vector to, final Vector difference) {
        this.entity = entity;
        this.instigator = instigator;
        this.from = from;
        this.to = to;
        this.difference = difference;
    }

    public HorizontalVelocityChangeEvent(final Entity entity, final Player instigator, final Vector from, final Vector to, final Vector difference, final Location start, final Location end, final Ability ability) {
        this.entity = entity;
        this.instigator = instigator;
        this.from = from;
        this.to = to;
        this.difference = difference;
        this.start = start;
        this.end = end;
        this.abil = ability;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public Entity getEntity() {
        return this.entity;
    }

    public Player getInstigator() {
        return this.instigator;
    }

    public Vector getFrom() {
        return this.from;
    }

    public Vector getTo() {
        return this.to;
    }

    public Location getStartPoint() {
        return this.start;
    }

    public Location getEndPoint() {
        return this.end;
    }

    public double getDistanceTraveled() {
        if (!this.start.getWorld().equals(this.end.getWorld())) {
            return 0;
        }
        return this.start.distance(this.end);
    }

    public Vector getDifference() {
        return this.difference;
    }

    public Ability getAbility() {
        return this.abil;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return this.isCancelled;
    }

    @Override
    public void setCancelled(final boolean value) {
        this.isCancelled = value;
    }
}
