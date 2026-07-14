package com.projectkorra.projectkorra.platform.chat.hover.content;

import com.projectkorra.projectkorra.platform.chat.BaseComponent;

public class Text {
    private final BaseComponent[] components;

    public Text(final BaseComponent[] components) {
        this.components = components == null ? new BaseComponent[0] : components;
    }

    public BaseComponent[] components() {
        return components;
    }
}
