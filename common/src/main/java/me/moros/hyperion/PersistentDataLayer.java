package me.moros.hyperion;

import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.inventory.meta.ItemMeta;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class PersistentDataLayer {
    private final Set<ItemMeta> earthGuard = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<ItemMeta> locksmithing = Collections.newSetFromMap(new WeakHashMap<>());

    public boolean hasEarthGuardKey(final ItemStack item) {
        return item != null && item.hasItemMeta() && earthGuard.contains(item.getItemMeta());
    }

    public boolean hasLocksmithingKey(final ItemStack item) {
        return item != null && item.hasItemMeta() && locksmithing.contains(item.getItemMeta());
    }

    public boolean hasEarthGuardKey(final ItemMeta meta) {
        return meta != null && earthGuard.contains(meta);
    }

    public boolean hasLocksmithingKey(final ItemMeta meta) {
        return meta != null && locksmithing.contains(meta);
    }

    public void addEarthGuardKey(final ItemStack item) {
        if (item != null) addEarthGuardKey(item.getItemMeta());
    }

    public void addLockSmithingKey(final ItemStack item) {
        if (item != null) addLockSmithingKey(item.getItemMeta());
    }

    public void addEarthGuardKey(final ItemMeta meta) {
        if (meta != null) earthGuard.add(meta);
    }

    public void addLockSmithingKey(final ItemMeta meta) {
        if (meta != null) locksmithing.add(meta);
    }
}
