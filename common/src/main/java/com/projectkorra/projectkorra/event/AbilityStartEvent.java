package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when an ability starts
 *
 * @author Philip
 *
 */
public class AbilityStartEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();

    boolean cancelled = false;
    Ability ability;

    public AbilityStartEvent(final Ability ability) {
        this.ability = ability;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public Ability getAbility() {
        return this.ability;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }
}
