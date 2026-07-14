package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when the /bending reload command is executed.
 */

public class BendingReloadEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final CommandSender sender;
    private boolean cancelled = false;

    public BendingReloadEvent(final CommandSender sender) {
        this.sender = sender;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    /**
     * @return Who called the reload
     */
    public CommandSender getSender() {
        return this.sender;
    }

    /**
     * @return Whether the event is cancelled
     */
    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    /**
     * Sets if the event is cancelled
     *
     * @param cancel boolean value indicating whether the event is cancelled or
     *               not
     */
    @Override
    public void setCancelled(final boolean cancel) {
        this.cancelled = cancel;
    }
}
