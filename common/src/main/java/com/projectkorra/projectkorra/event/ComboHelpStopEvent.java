package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when combo help stops for a player.
 */
public class ComboHelpStopEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ComboAbilityInfo combo;
    private final Reason reason;
    public ComboHelpStopEvent(final Player player, final ComboAbilityInfo combo, final Reason reason) {
        this.player = player;
        this.combo = combo;
        this.reason = reason;
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

    public Reason getReason() {
        return this.reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public enum Reason {
        MANUAL,
        REPLACED,
        COMPLETED,
        PLAYER_OFFLINE
    }
}
