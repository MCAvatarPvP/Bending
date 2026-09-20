package me.hiro;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.support.AbilityWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class TossGroundTest {
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 65, 0.5);

    @AfterEach void cleanup() throws Exception { world.close(); }

    private Block support() {
        return Toss.findSupportingBlock(player, block -> block.getType() == Material.STONE);
    }

    private static BoundingBox box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new BoundingBox(new Vector(minX, minY, minZ), new Vector(maxX, maxY, maxZ));
    }

    @ParameterizedTest
    @CsvSource({"0.5, 0.5", "1.29, 0.5", "-0.29, 0.5", "0.5, 1.29", "0.5, -0.29",
            "1.29, 1.29", "-0.29, -0.29", "1.29, -0.29", "-0.29, 1.29"})
    void findsEarthBeneathAnyPartOfTheFeet(double x, double z) {
        Block stone = world.getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        player.location = new Location(world, x, 65, z);
        assertSame(stone, support(), "the player's center can be over air while their feet still overlap earth");
    }

    @Test void findsEdgeSupportAtNegativeWorldCoordinates() {
        Block stone = world.getBlockAt(-8, 64, -12);
        stone.setType(Material.STONE);
        player.location = new Location(world, -8.29, 65, -12.29);
        assertSame(stone, support());
    }

    @Test void rejectsAirbornePlayersEvenWithAStaleGroundFlag() {
        world.getBlockAt(0, 64, 0).setType(Material.STONE);
        player.onGround = true;
        player.location.setY(65.1);
        assertNull(support());
    }

    @Test void touchingOnlyTheSideOfEarthDoesNotCountAsStandingOnIt() {
        world.getBlockAt(0, 64, 0).setType(Material.STONE);
        player.location = new Location(world, 1.3, 65, 0.5);
        assertNull(support(), "zero horizontal overlap cannot support a player");
        player.location = new Location(world, 1.2, 64.5, 0.5);
        assertNull(support(), "being beside a block below its top is not ground contact");
    }

    @Test void standingOnNonEarthDoesNotSelectEarthUnderneath() {
        world.getBlockAt(0, 63, 0).setType(Material.STONE);
        world.getBlockAt(0, 64, 0).setType(Material.ICE);
        assertNull(support());
    }

    @Test void respectsTheActualHeightOfSlabsAndWalls() {
        var stone = world.getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        stone.setCollisionBoxes(box(0, 64, 0, 1, 64.5, 1));
        player.location.setY(64.5);
        assertSame(stone, support());
        player.location.setY(65);
        assertNull(support(), "the empty half above a slab must not count as support");
        stone.setCollisionBoxes(box(0.25, 64, 0.25, 0.75, 65.5, 0.75));
        player.location.setY(65.5);
        assertSame(stone, support());
    }

    @Test void checksIndividualStairSurfacesInsteadOfTheirEnclosingBox() {
        var stone = world.getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        stone.setCollisionBoxes(box(0, 64, 0, 1, 64.5, 1), box(0.5, 64.5, 0, 1, 65, 1));
        player.location = new Location(world, 0.1, 64.5, 0.5);
        assertSame(stone, support(), "the lower step supports players too");
        player.location.setY(65);
        assertNull(support(), "air above the lower step is inside the enclosing box but has no support");
        player.location.setX(0.9);
        assertSame(stone, support());
    }

    @Test void recheckingBeforeLaunchRejectsAPlayerWhoLeftTheGround() {
        var stone = world.getBlockAt(0, 64, 0);
        stone.setType(Material.STONE);
        assertSame(stone, support());
        player.location.setX(1.4);
        assertNull(support());
        player.location.setX(0.5);
        stone.setType(Material.AIR);
        assertNull(support());
    }
}
