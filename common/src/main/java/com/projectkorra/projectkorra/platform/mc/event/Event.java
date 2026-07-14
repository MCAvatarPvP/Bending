package com.projectkorra.projectkorra.platform.mc.event;

public class Event {
    private final boolean asynchronous;

    public Event() {
        this(false);
    }

    public Event(boolean asynchronous) {
        this.asynchronous = asynchronous;
    }

    public boolean isAsynchronous() {
        return asynchronous;
    }

    public HandlerList getHandlers() {
        return new HandlerList();
    }
}
