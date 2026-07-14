package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

public class AbilityEndEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();

    Ability ability;

    public AbilityEndEvent(final Ability ability) {
        this.ability = ability;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Ability getAbility() {
        return this.ability;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
