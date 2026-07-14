package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.OfflinePlayer;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class PlayerChangeSubElementEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    @Nullable
    private final CommandSender sender;
    @NotNull
    private final OfflinePlayer target;
    private final SubElement sub;
    private final Result result;
    private boolean cancelled;

    /**
     *
     * @param sender the {@link CommandSender} who changed the player's bending
     * @param target the {@link Player} who's bending was changed
     * @param sub    the {@link SubElement} that was changed to
     * @param result whether the element was chosen, added, removed, or
     *               permaremoved
     */
    public PlayerChangeSubElementEvent(final CommandSender sender, final OfflinePlayer target, final SubElement sub, final Result result) {
        super(!Platform.scheduler().isPrimaryThread());
        this.sender = sender;
        this.target = target;
        this.sub = sub;
        this.result = result;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    /**
     * Get the sender responsible for changing the player's bending
     *
     * @return the {@link CommandSender} who changed the player's bending
     */
    public CommandSender getSender() {
        return this.sender;
    }

    /**
     * Get the player who's bending was changed
     *
     * @return the {@link Player player} who's bending was changed
     */
    public OfflinePlayer getTarget() {
        return this.target;
    }

    /**
     * Is the target player online?
     *
     * @return whether the player is online
     */
    public boolean isTargetOnline() {
        return this.target.isOnline();
    }

    /**
     * Gets whether the result is a result where the element is removed. Including removing,
     * permanently removing, temporary element removing, and temporary elements expiring.
     *
     * @return whether the element was removed
     */
    public boolean isRemoved() {
        return this.result == Result.REMOVE || this.result == Result.PERMAREMOVE
                || this.result == Result.TEMP_REMOVE || this.result == Result.TEMP_EXPIRE
                || this.result == Result.TEMP_PARENT_REMOVE || this.result == Result.TEMP_PARENT_EXPIRE;
    }

    /**
     * Gets whether the result is a result where the element changed is a temporary element
     *
     * @return whether the element being modified is temporary
     */
    public boolean isTemporaryElement() {
        return this.result == Result.TEMP_ADD || this.result == Result.TEMP_REMOVE || this.result == Result.TEMP_EXPIRE
                || this.result == Result.TEMP_PARENT_ADD || this.result == Result.TEMP_PARENT_REMOVE
                || this.result == Result.TEMP_PARENT_EXPIRE;
    }

    /**
     * Get the {@link Element element} that was affected
     *
     * @return the {@link Element element} that was affected
     */
    public SubElement getSubElement() {
        return this.sub;
    }

    /**
     * Get the {@link BendingPlayer} that was affected
     *
     * @return the {@link BendingPlayer} that was affected
     */
    public OfflineBendingPlayer getBendingPlayer() {
        return BendingPlayer.getBendingPlayer(this.target);
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    /**
     * Get the result of the event.
     *
     * @return whether the element was chosen, added, removed, or permaremoved
     */
    public Result getResult() {
        return this.result;
    }

    /**
     * The possible results of the event. TEMP_PARENT is when the sub was modified
     * because the player has a temp parent element of that sub
     */
    public enum Result {
        CHOOSE, REMOVE, ADD, PERMAREMOVE,
        TEMP_ADD, TEMP_REMOVE, TEMP_EXPIRE,
        TEMP_PARENT_ADD, TEMP_PARENT_REMOVE, TEMP_PARENT_EXPIRE;
    }
}
