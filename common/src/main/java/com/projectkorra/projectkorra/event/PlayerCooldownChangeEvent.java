package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.platform.mc.OfflinePlayer;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

public final class PlayerCooldownChangeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final OfflinePlayer player;
    private final String ability;
    private final Result eventresult;
    private boolean cancelled;
    private long cooldown;
    public PlayerCooldownChangeEvent(final OfflinePlayer player, final String abilityname, final long cooldown, final Result result) {
        this.player = player;
        this.ability = abilityname;
        this.eventresult = result;
        this.cancelled = false;
        this.cooldown = cooldown;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public OfflinePlayer getPlayer() {
        return this.player;
    }

    public String getAbility() {
        return this.ability;
    }

    public Result getResult() {
        return this.eventresult;
    }

    public long getCooldown() {
        return this.cooldown;
    }

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }

    /**
     * Get the {@link BendingPlayer} that was affected
     *
     * @return the {@link BendingPlayer} that was affected
     */
    public OfflineBendingPlayer getBendingPlayer() {
        return BendingPlayer.getBendingPlayer(this.player);
    }

    /**
     * Get whether the player is online
     *
     * @return true if the player is online
     */
    public boolean isOnline() {
        return this.player.isOnline();
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static enum Result {
        REMOVED, ADDED, SET;
    }
}
