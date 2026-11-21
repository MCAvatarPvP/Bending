package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;

/** Captures all tags visible to BukkitTagsFacade, including server datapack changes. */
public final class PaperRollbackTags {
    private PaperRollbackTags() { }
    public static RollbackTags capture() {
        if (!Bukkit.isPrimaryThread() || RollbackDomain.active() || RollbackClock.active()) throw new IllegalStateException("Capture tags at live bootstrap boundary");
        return capture(Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class));
    }
    static RollbackTags capture(Iterable<Tag<Material>> tags) {
        var result = new TreeMap<String, List<com.projectkorra.projectkorra.platform.mc.Material>>();
        for (var tag : tags) {
            if (!tag.getKey().getNamespace().equals("minecraft")) continue;
            var members = tag.getValues().stream().map(BukkitMC::material).filter(Objects::nonNull).toList();
            if (result.putIfAbsent(tag.getKey().getKey(), members) != null) throw new IllegalArgumentException("Duplicate native block tag");
        }
        return new RollbackTags(result);
    }
}
