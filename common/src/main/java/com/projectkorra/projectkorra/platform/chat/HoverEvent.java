package com.projectkorra.projectkorra.platform.chat;

public class HoverEvent {
    private final Action action;
    private final Object value;
    public HoverEvent(final Action action, final Object value) {
        this.action = action;
        this.value = value;
    }

    public Action getAction() {
        return action;
    }

    public Object getValue() {
        return value;
    }

    public enum Action {SHOW_TEXT}
}
