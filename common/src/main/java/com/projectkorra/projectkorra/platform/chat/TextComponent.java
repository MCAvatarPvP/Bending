package com.projectkorra.projectkorra.platform.chat;

import java.util.List;

/**
 * Simple text component; adapters convert this to Bukkit/Fabric native chat.
 */
public class TextComponent extends BaseComponent {
    public TextComponent() {
        super("");
    }

    public TextComponent(final String text) {
        super(text);
    }

    public TextComponent(final BaseComponent component) {
        super(component);
    }

    public TextComponent(final BaseComponent... components) {
        for (BaseComponent component : components) addExtra(component);
    }

    public static BaseComponent[] fromLegacyText(final String text) {
        return new BaseComponent[]{new TextComponent(text)};
    }

    public void setExtra(List<BaseComponent> components) {
        this.extra.clear();
        if (components != null) this.extra.addAll(components);
    }
}
