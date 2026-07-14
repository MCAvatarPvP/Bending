package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

public class PlayerStanceChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String oldStance;
    private final String newStance;

    private boolean cancelled;

    public PlayerStanceChangeEvent(final Player player, final String oldStance, final String newStance) {
        this.player = player;
        this.oldStance = oldStance;
        this.newStance = newStance;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Player getPlayer() {
        return this.player;
    }

    public String getOldStance() {
        return this.oldStance;
    }

    public String getNewStance() {
        return this.newStance;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }
}
