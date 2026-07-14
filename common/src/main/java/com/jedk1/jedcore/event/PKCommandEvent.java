package com.jedk1.jedcore.event;

import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

public class PKCommandEvent extends Event {

    public static final HandlerList handlers = new HandlerList();
    private Player sender;
    private CommandType type;
    private String[] args;
    public PKCommandEvent(Player sender, String[] args, CommandType type) {
        this.sender = sender;
        this.type = type;
        this.args = args;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    public Player getSender() {
        return sender;
    }

    public CommandType getType() {
        return type;
    }

    public String[] getArgs() {
        return args;
    }

    public HandlerList getHandlers() {
        return handlers;
    }

    public enum CommandType {
        ADD,
        BIND,
        CHECK,
        CHOOSE,
        CLEAR,
        DISPLAY,
        GIVE,
        HELP,
        IMPORT,
        INVINCIBLE,
        PERMREMOVE,
        PRESET,
        RELOAD,
        REMOVE,
        TOGGLE,
        VERSION,
        WHO,
        JEDCORE,
        SCOREBOARD;

        public static CommandType getType(String string) {
            for (CommandType element : CommandType.values()) {
                if (element.toString().equalsIgnoreCase(string)) {
                    return element;
                }
            }
            return null;
        }
    }
}
