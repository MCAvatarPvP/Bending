package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when an ability starts
 *
 * @author Philip
 *
 */
public class AbilityProgressEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    Ability ability;

    public AbilityProgressEvent(final Ability ability) {
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
}
