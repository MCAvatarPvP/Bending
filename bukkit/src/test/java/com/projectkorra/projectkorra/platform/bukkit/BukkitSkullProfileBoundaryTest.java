package com.projectkorra.projectkorra.platform.bukkit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the Paper profile API boundary used by FireBlast's textured display heads. */
class BukkitSkullProfileBoundaryTest {
    @Test
    void texturedHeadsUseBukkitsSupportedProfileApi() throws IOException {
        Path sourcePath = Path.of("src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        if (!Files.exists(sourcePath)) {
            sourcePath = Path.of("bukkit/src/main/java/com/projectkorra/projectkorra/platform/bukkit/BukkitMC.java");
        }
        assertTrue(Files.exists(sourcePath));

        String source = Files.readString(sourcePath);
        int methodStart = source.indexOf("public static org.bukkit.inventory.ItemStack itemHandle");
        int methodEnd = source.indexOf("private static org.bukkit.inventory.ItemStack[] itemHandles", methodStart);
        assertTrue(methodStart >= 0 && methodEnd > methodStart);

        String itemHandle = source.substring(methodStart, methodEnd);
        assertTrue(itemHandle.contains("org.bukkit.profile.PlayerProfile profile"));
        assertTrue(itemHandle.contains("profile.getTextures()"));
        assertTrue(itemHandle.contains("textures.setSkin("));
        assertTrue(itemHandle.contains("nativeSkull.setOwnerProfile(profile)"));
        assertFalse(itemHandle.contains("com.destroystokyo.paper.profile"));
        assertFalse(itemHandle.contains("setProperty("));
        assertFalse(itemHandle.contains("setPlayerProfile("));
    }
}
