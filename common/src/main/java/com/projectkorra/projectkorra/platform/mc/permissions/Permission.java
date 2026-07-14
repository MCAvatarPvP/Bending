package com.projectkorra.projectkorra.platform.mc.permissions;

import java.util.LinkedHashMap;
import java.util.Map;

public class Permission {
    private final String name;
    private final Map<Permission, Boolean> parents = new LinkedHashMap<>();

    public Permission(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void addParent(Permission parent, boolean value) {
        if (parent != null) this.parents.put(parent, value);
    }

    public Map<Permission, Boolean> getParents() {
        return Map.copyOf(this.parents);
    }

    @Override
    public String toString() {
        return name;
    }
}
