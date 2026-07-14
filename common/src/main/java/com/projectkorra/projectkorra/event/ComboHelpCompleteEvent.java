package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when a player completes a combo while using combo help.
 */
public class ComboHelpCompleteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ComboAbilityInfo combo;

    public ComboHelpCompleteEvent(final Player player, final ComboAbilityInfo combo) {
        this.player = player;
        this.combo = combo;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Player getPlayer() {
        return this.player;
    }

    public ComboAbilityInfo getCombo() {
        return this.combo;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
