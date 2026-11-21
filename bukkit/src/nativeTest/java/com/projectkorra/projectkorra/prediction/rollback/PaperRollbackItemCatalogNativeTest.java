package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeItems.Kind;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackItemCatalogNativeTest {
    @BeforeAll static void bootstrapRegistry() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void metadataCatalogCoversNativeRegistryAndPreservesCommonApiSubtypes() {
        var catalog = PaperRollbackItemCatalog.capture();
        for (var id : BuiltInRegistries.ITEM.keySet()) assertNotNull(catalog.get(id.toString()), () -> "No metadata category for " + id);
        assertEquals(Kind.LEATHER, catalog.get("minecraft:leather_helmet"));
        assertEquals(Kind.POTION, catalog.get("minecraft:potion"));
        assertEquals(Kind.SKULL, catalog.get("minecraft:player_head"));
        assertEquals(Kind.GENERIC, catalog.get("minecraft:stone"));
    }
}
