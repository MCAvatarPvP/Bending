package com.projectkorra.projectkorra.platform.chat;

import com.projectkorra.projectkorra.platform.mc.ChatColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Platform-neutral legacy chat component used by common commands.
 */
public class BaseComponent {
    protected final List<BaseComponent> extra = new ArrayList<>();
    protected String text = "";
    protected ChatColor color;
    protected ClickEvent clickEvent;
    protected HoverEvent hoverEvent;

    public BaseComponent() {
    }

    public BaseComponent(final String text) {
        this.text = text == null ? "" : text;
    }

    public BaseComponent(final BaseComponent other) {
        this.text = other == null ? "" : other.toLegacyText();
    }

    public void addExtra(final BaseComponent component) {
        if (component != null) this.extra.add(component);
    }

    public ClickEvent getClickEvent() {
        return clickEvent;
    }

    public void setClickEvent(final ClickEvent event) {
        this.clickEvent = event;
    }

    public HoverEvent getHoverEvent() {
        return hoverEvent;
    }

    public void setHoverEvent(final HoverEvent event) {
        this.hoverEvent = event;
    }

    public ChatColor getColor() {
        return color;
    }

    public void setColor(final ChatColor color) {
        this.color = color;
    }

    public String toLegacyText() {
        StringBuilder out = new StringBuilder();
        if (color != null) out.append(color);
        out.append(text);
        for (BaseComponent component : extra) out.append(component.toLegacyText());
        return out.toString();
    }
}
