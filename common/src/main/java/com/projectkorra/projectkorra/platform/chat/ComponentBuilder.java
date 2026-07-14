package com.projectkorra.projectkorra.platform.chat;

import com.projectkorra.projectkorra.platform.mc.ChatColor;

import java.util.ArrayList;
import java.util.List;

public class ComponentBuilder {
    private final List<BaseComponent> parts = new ArrayList<>();

    public ComponentBuilder() {
    }

    public ComponentBuilder(final String text) {
        append(text);
    }

    public ComponentBuilder append(final String text) {
        this.parts.add(new TextComponent(text));
        return this;
    }

    public ComponentBuilder append(final String text, final FormatRetention ignored) {
        return append(text);
    }

    public ComponentBuilder appendLegacy(final String text) {
        return append(text);
    }

    public ComponentBuilder color(final ChatColor color) {
        if (!parts.isEmpty()) parts.get(parts.size() - 1).setColor(color);
        return this;
    }

    public ComponentBuilder event(final ClickEvent event) {
        if (!parts.isEmpty()) parts.get(parts.size() - 1).setClickEvent(event);
        return this;
    }

    public ComponentBuilder event(final HoverEvent event) {
        if (!parts.isEmpty()) parts.get(parts.size() - 1).setHoverEvent(event);
        return this;
    }

    public BaseComponent[] create() {
        return this.parts.toArray(BaseComponent[]::new);
    }

    public List<BaseComponent> getParts() {
        return this.parts;
    }

    public enum FormatRetention {NONE, FORMATTING, EVENTS, ALL}
}
