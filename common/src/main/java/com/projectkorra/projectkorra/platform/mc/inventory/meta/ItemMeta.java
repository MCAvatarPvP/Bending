package com.projectkorra.projectkorra.platform.mc.inventory.meta;

import java.util.List;

public class ItemMeta {
    private String name = "";
    private List<String> lore = List.of();

    public String getDisplayName() {
        return name;
    }

    public void setDisplayName(String value) {
        name = value;
    }

    public List<String> getLore() {
        return lore;
    }

    public void setLore(List<String> value) {
        lore = value == null ? List.of() : List.copyOf(value);
    }
}
