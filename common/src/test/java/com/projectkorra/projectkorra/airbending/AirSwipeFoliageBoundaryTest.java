package com.projectkorra.projectkorra.airbending;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AirSwipeFoliageBoundaryTest {

    @Test
    void foliageIsCutBeforeSolidBlocksCanStopTheSwipe() throws IOException {
        final String swipe = source("airbending/AirSwipe.java");
        final int foliage = swipe.indexOf("final boolean clearedCenter = AirFoliage.clear(this, block, foliageVelocity)");
        final int obstruction = swipe.indexOf("!ElementalAbility.isTransparent(this.player, block)");

        assertTrue(foliage >= 0 && obstruction > foliage,
                "leaf blocks must be removed before the normal solid-block obstruction check");
        assertTrue(swipe.contains("if (clearedCenter) return true"),
                "a cut plant must let the AirSwipe stream continue through it");
        assertTrue(swipe.contains("GeneralMethods.getBlocksAroundPoint(block.getLocation(), this.radius)"),
                "foliage clearing must cover the full sweep radius instead of only its center ray");
    }

    @Test
    void leavesAndConfiguredPlantsEmitColoredTintedLeavesWithoutDrops() throws IOException {
        final String source = source("airbending/AirFoliage.java");

        assertTrue(source.contains("Tag.LEAVES.isTagged(material)")
                        && source.contains("endsWith(\"_LEAVES\")")
                        && source.contains("ElementalAbility.isPlant(block.getType())")
                        && source.contains("GeneralMethods.isPassable(block)"),
                "all leaf blocks and grass-like configured plants must be recognized");
        assertTrue(source.contains("Particle.TINTED_LEAVES")
                        && source.contains("final Color color = foliageColor(block.getType())"),
                "removed foliage must use explicitly colored Minecraft tinted-leaf particles");
        assertTrue(source.contains("final int particleCount = leaves ? 5 : 3")
                        && source.contains("particleOrigin, 0")
                        && source.contains("particleVelocity(direction, index, particleCount)"),
                "the restrained particle set must inherit an explicit per-particle push velocity");
        assertTrue(!source.contains("Particle.BLOCK") && !source.contains("Particle.FALLING_DUST"),
                "foliage effects must not fall back to block or dust particles");
        assertTrue(source.contains("block.setType(Material.AIR, false)"),
                "the sweep must remove foliage without harvesting item drops");
        assertTrue(source.contains("originalData instanceof Bisected")
                        && source.contains("pairedHalf(block)"),
                "both halves of tall grass and other double-height plants must be removed");
        assertTrue(source.contains("GeneralMethods.isRegionProtectedFromBuild(ability, block.getLocation())"),
                "each foliage mutation must honor region protection");
    }

    @Test
    void airSweepComboClearsItsWholeFanBeforeWallTests() throws IOException {
        final String sweep = source("airbending/combo/AirSweep.java");
        final int foliage = sweep.indexOf("AirFoliage.clear(this, check.getBlock(), foliageVelocity)");
        final int obstruction = sweep.indexOf("GeneralMethods.checkDiagonalWall(check, direction)");

        assertTrue(foliage >= 0 && obstruction > foliage,
                "AirSweep must clear foliage before testing whether a stream hit a wall");
        assertTrue(sweep.contains("GeneralMethods.getBlocksAroundPoint(check, this.radius)"),
                "AirSweep must clear leaves and grass across the combo's full configured radius");
    }

    private static String source(final String relative) throws IOException {
        Path path = Path.of("src/main/java/com/projectkorra/projectkorra").resolve(relative);
        if (!Files.exists(path)) path = Path.of("common/src/main/java/com/projectkorra/projectkorra").resolve(relative);
        assertTrue(Files.exists(path), "missing source: " + path);
        return Files.readString(path);
    }
}
