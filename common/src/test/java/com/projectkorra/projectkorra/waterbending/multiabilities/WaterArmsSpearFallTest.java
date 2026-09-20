package com.projectkorra.projectkorra.waterbending.multiabilities;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterArmsSpearFallTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 65, 0.5);

    private TempBlock ice() throws Exception {
        TempBlock layer = new TempBlock(world.getBlockAt(0, 64, 0), Material.ICE);
        var track = WaterArmsSpear.class.getDeclaredMethod("track", TempBlock.class, long.class);
        track.setAccessible(true);
        track.invoke(null, layer, 5000L);
        return layer;
    }

    @AfterEach void cleanup() throws Exception {
        TempBlock.removeAll();
        WaterArmsSpear.discardAllTracking();
        world.close();
    }

    @Test void walkingOffAnIceEdgeProtectsTheFallUntilLanding() throws Exception {
        ice();
        Location from = player.getLocation();
        player.location = new Location(world, 1.4, 64.8, 0.5);
        WaterArmsSpear.protectFallFromIce(player, from, player.getLocation());
        assertTrue(FallHandler.contains(player));
        FallHandler.move(player);
        assertEquals(FallHandler.Step.MOVED, FallHandler.getStep(player));
        player.onGround = true;
        FallHandler.move(player);
        assertTrue(FallHandler.contains(player), "the landing damage event must still be protected");
        FallHandler.move(player);
        assertFalse(FallHandler.contains(player), "protection must not persist for another fall");
    }

    @Test void meltingUnderAStationaryPlayerProtectsTheDrop() throws Exception {
        TempBlock ice = ice();
        assertFalse(FallHandler.contains(player));
        ice.revertBlock();
        assertTrue(FallHandler.contains(player));
        assertEquals(Material.AIR, world.getBlockAt(0, 64, 0).getType());
        assertTrue(WaterArmsSpear.getIceBlocks().isEmpty());
    }

    @Test void naturalIceAndBuriedSpearIceDoNotGrantProtection() throws Exception {
        world.getBlockAt(0, 64, 0).setType(Material.ICE);
        WaterArmsSpear.protectFallFromIce(player, player.getLocation(), player.getLocation());
        assertFalse(FallHandler.contains(player));
        TempBlock buriedIce = ice();
        new TempBlock(world.getBlockAt(0, 64, 0), Material.STONE);
        WaterArmsSpear.protectFallFromIce(player, player.getLocation(), player.getLocation());
        assertFalse(FallHandler.contains(player));
        buriedIce.revertBlock();
        assertFalse(FallHandler.contains(player), "the solid covering layer still supports the player");
    }

    @Test void walkingFromIceOntoSolidGroundDoesNotSaveProtectionForALaterFall() throws Exception {
        ice();
        Location from = player.getLocation();
        player.location = new Location(world, 1.4, 65, 0.5);
        player.onGround = true;
        WaterArmsSpear.protectFallFromIce(player, from, player.getLocation());
        FallHandler.move(player);
        FallHandler.move(player);
        assertFalse(FallHandler.contains(player));
    }
}
