package com.projectkorra.projectkorra.prediction.rollback;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackTagsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void capturesTheBukkitFacadesMinecraftBlockTagsAndDetachesDatapackChanges() {
        var members = new HashSet<>(Set.of(Material.STONE, Material.AIR));
        var tag = tag("minecraft", members);
        var captured = PaperRollbackTags.capture(List.of(tag, tag("custom", Set.of(Material.DIRT))));
        members.clear();
        assertEquals(List.of(com.projectkorra.projectkorra.platform.mc.Material.STONE), captured.blocks().get("test"));
        assertEquals(1, captured.blocks().size());
        assertEquals(captured, RollbackTags.decode(captured.encode()));
        assertThrows(IllegalArgumentException.class, () -> PaperRollbackTags.capture(List.of(tag, tag)));
    }
    private static Tag<Material> tag(String namespace, Set<Material> values) {
        return new Tag<>() {
            @Override public NamespacedKey getKey() { return new NamespacedKey(namespace, "test"); }
            @Override public Set<Material> getValues() { return values; }
            @Override public boolean isTagged(Material value) { return values.contains(value); }
        };
    }
}
