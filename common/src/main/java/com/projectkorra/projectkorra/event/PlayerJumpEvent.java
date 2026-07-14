package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * An event that is called when a player jumps. ProjectKorra tracks the jump statistic and fires this
 * event when it increments. This event is useful for addon abilities that need to know when a player
 * jumps.
 *
 * @author Simplicitee
 */
public class PlayerJumpEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final double height;

    public PlayerJumpEvent(final Player player, final double height) {
        this.player = player;
        this.height = height;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Player getPlayer() {
        return this.player;
    }

    public double getHeight() {
        return this.height;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
