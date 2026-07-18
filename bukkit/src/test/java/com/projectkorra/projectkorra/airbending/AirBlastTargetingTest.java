package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AirBlastTargetingTest {
    @Test
    void airBlastUsesTheExactPreModuleSplitTargetMarcher() throws IOException {
        Path source = Path.of("../common/src/main/java/com/projectkorra/projectkorra/airbending/AirBlast.java");
        if (!Files.exists(source)) {
            source = Path.of("common/src/main/java/com/projectkorra/projectkorra/airbending/AirBlast.java");
        }
        assertTrue(Files.exists(source), source.toString());
        final String airBlast = Files.readString(source);
        final int start = airBlast.indexOf("public static Location getTargetedLocation");
        final int end = airBlast.indexOf("public static int getSelectParticles", start);
        assertTrue(start >= 0 && end > start);
        final String targeting = airBlast.substring(start, end);

        assertTrue(targeting.contains("GeneralMethods.getTargetedLocation(player, maximumRange, nonOpaque2)"));
        assertTrue(targeting.contains("AirBlastTargeting.clampToRange(eye, target, maximumRange)"));
        assertFalse(targeting.contains("getTargetBlock("),
                "block-corner distance is not the pre-module-split AirBlast launch contract");
        assertFalse(targeting.contains("block.getLocation().distance("),
                "fractional position inside a block must not rotate the sourced launch vector");
    }

    @Test
    void straightDownTargetDoesNotDependOnTheBlocksIntegerCorner() {
        final FlatWorld world = new FlatWorld();
        final Location nearCorner = target(world, 0.05, 0.05);
        final Location farCorner = target(world, 0.95, 0.95);

        assertEquals(nearCorner.getX() - 0.05, farCorner.getX() - 0.95, 1.0E-12);
        assertEquals(nearCorner.getY(), farCorner.getY(), 1.0E-12);
        assertEquals(nearCorner.getZ() - 0.05, farCorner.getZ() - 0.95, 1.0E-12);
        assertEquals(1.02, nearCorner.getY(), 1.0E-12,
                "the pre-split marcher backs up one 0.2-block step before the flat ground");
    }

    @Test
    void unobstructedFloatingPointStepStaysInsideSelectionRange() {
        final World world = new FlatWorld();
        final Location origin = new Location(world, 4.25, 70.62, -2.75);
        final Location steppedPastRange = origin.clone().add(0.0, 0.0, 10.2);

        final Location clamped = AirBlastTargeting.clampToRange(origin, steppedPastRange, 10.0);

        assertEquals(9.9, clamped.distance(origin), 1.0E-12);
        assertEquals(origin.getX(), clamped.getX(), 0.0);
        assertEquals(origin.getY(), clamped.getY(), 0.0);
        assertEquals(origin.getZ() + 9.9, clamped.getZ(), 1.0E-12);
    }

    private static Location target(final World world, final double x, final double z) {
        final Location eye = new Location(world, x, 1.62, z);
        eye.setYaw(0.0F);
        eye.setPitch(90.0F);
        return GeneralMethods.getTargetedLocation(new TestPlayer(eye), 10.0,
                false, false);
    }

    private static final class TestPlayer extends Player {
        private final Location eye;

        private TestPlayer(final Location eye) {
            this.eye = eye;
        }

        @Override
        public Location getEyeLocation() {
            return this.eye.clone();
        }

        @Override
        public Block getTargetBlock(final Set<Material> transparent, final int range) {
            throw new AssertionError("AirBlast must use the shared pre-split marcher");
        }
    }

    private static final class FlatWorld extends World {
        @Override
        public Block getBlockAt(final int x, final int y, final int z) {
            return new FlatBlock(this, x, y, z);
        }
    }

    private static final class FlatBlock extends Block {
        private final FlatWorld world;
        private final int x;
        private final int y;
        private final int z;

        private FlatBlock(final FlatWorld world, final int x, final int y, final int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public Material getType() {
            return this.y <= 0 ? Material.STONE : Material.AIR;
        }

        @Override
        public Location getLocation() {
            return new Location(this.world, this.x, this.y, this.z);
        }

        @Override
        public World getWorld() {
            return this.world;
        }

        @Override
        public Block getRelative(final BlockFace face) {
            return switch (face) {
                case DOWN -> this.world.getBlockAt(this.x, this.y - 1, this.z);
                case EAST -> this.world.getBlockAt(this.x + 1, this.y, this.z);
                case NORTH -> this.world.getBlockAt(this.x, this.y, this.z - 1);
                case SOUTH -> this.world.getBlockAt(this.x, this.y, this.z + 1);
                case UP -> this.world.getBlockAt(this.x, this.y + 1, this.z);
                case WEST -> this.world.getBlockAt(this.x - 1, this.y, this.z);
                default -> this;
            };
        }
    }
}
