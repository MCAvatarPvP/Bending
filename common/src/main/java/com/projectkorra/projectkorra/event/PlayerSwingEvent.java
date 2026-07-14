package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * An event that is called when a player swings their arm. This event is a combination of the PlayerInteractEvent
 * and the PlayerAnimationEvent, after all exceptions have been checked. This is useful for addon
 * abilities so they don't fire on things like "The player opening a crafting table".
 *
 * @author StrangeOne101
 */
public class PlayerSwingEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private boolean cancelled;
    private Player player;

    public PlayerSwingEvent(Player player) {
        this.player = player;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Player getPlayer() {
        return player;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean b) {
        this.cancelled = b;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }
}
