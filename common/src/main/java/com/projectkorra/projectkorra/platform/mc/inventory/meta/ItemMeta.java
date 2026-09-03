package com.projectkorra.projectkorra.platform.mc.inventory.meta;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public class ItemMeta {
    private String name = "";
    private List<String> lore = List.of();
    private Integer customModelData;
    private final Map<String, String> customData = new LinkedHashMap<>();

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

    public Integer getCustomModelData() {
        return customModelData;
    }

    public void setCustomModelData(Integer value) {
        customModelData = value;
    }

    public String getCustomData(final String key) {
        return key == null ? null : customData.get(key);
    }

    public void setCustomData(final String key, final String value) {
        if (key == null || key.isBlank()) return;
        if (value == null) customData.remove(key);
        else customData.put(key, value);
    }

    public Map<String, String> getCustomData() {
        return Map.copyOf(customData);
    }
}
