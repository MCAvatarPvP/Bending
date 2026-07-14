package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboManager.ComboAbilityInfo;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when combo help processes an input.
 */
public class ComboHelpInputEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player player;
    private final ComboAbilityInfo combo;
    private final AbilityInformation input;
    private final Result result;
    private final int progress;
    public ComboHelpInputEvent(final Player player, final ComboAbilityInfo combo, final AbilityInformation input, final Result result, final int progress) {
        this.player = player;
        this.combo = combo;
        this.input = input;
        this.result = result;
        this.progress = progress;
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

    public AbilityInformation getInput() {
        return this.input;
    }

    public Result getResult() {
        return this.result;
    }

    public int getProgress() {
        return this.progress;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public enum Result {
        CORRECT,
        WRONG,
        COMPLETED
    }
}
