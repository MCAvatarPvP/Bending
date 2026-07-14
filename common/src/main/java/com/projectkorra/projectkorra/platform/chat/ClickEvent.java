package com.projectkorra.projectkorra.platform.chat;

public record ClickEvent(Action action, String value) {
    public enum Action {OPEN_URL, RUN_COMMAND, SUGGEST_COMMAND, COPY_TO_CLIPBOARD}
}
