package com.projectkorra.projectkorra.airbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TornadoDebrisTest {
    @Test
    void reusesOneBoundedPoolWhileFragmentsOrbitRiseAndTumbleForAThousandTicks() {
        final TestWorld world = new TestWorld();
        final Location base = new Location(world, 3, 64, 5);
        final TornadoDebris debris = new TornadoDebris(20, 0.25, 0.65, 0.16);
        assertTrue(world.displays.isEmpty(), "construction must not spawn entities");
        debris.update(base, 14, 7, 0);
        final List<TestDisplay> originals = List.copyOf(world.displays);
        final Location firstLocation = originals.getFirst().location.clone();
        final Quaternionf firstRotation = originals.getFirst().transformation.leftRotation();

        for (long tick = 1; tick <= 1000; tick++) {
            debris.update(base, 14, 7, tick);
            assertEquals(20, world.displays.size());
            for (final TestDisplay display : originals) {
                assertTrue(display.location.getY() >= base.getY());
                assertTrue(display.location.getY() <= base.getY() + 14);
                final double dx = display.location.getX() - base.getX();
                final double dz = display.location.getZ() - base.getZ();
                assertTrue(dx * dx + dz * dz <= 49);
            }
        }

        assertEquals(originals, world.displays, "updates must reuse the original entity identities");
        assertNotEquals(firstLocation.getX(), originals.getFirst().location.getX());
        assertNotEquals(firstLocation.getY(), originals.getFirst().location.getY());
        assertNotEquals(firstRotation, originals.getFirst().transformation.leftRotation());
        assertEquals(64, base.getY(), "rendering must not mutate the tornado base");
        for (final TestDisplay display : originals) {
            assertEquals(Material.LIGHT_BLUE_STAINED_GLASS, display.data.getMaterial());
            assertFalse(display.persistent);
            assertFalse(display.gravity);
            assertTrue(display.silent);
            assertTrue(display.invulnerable);
            assertEquals(2, display.interpolationDuration);
            assertEquals(2, display.teleportDuration);
            assertEquals(1001, display.teleports);
        }
        debris.remove();
        assertTrue(originals.stream().noneMatch(TestDisplay::isValid));
        debris.remove();
        debris.update(base, 14, 7, 1001);
        assertEquals(20, world.displays.size(), "removed renderers must never respawn");
        assertTrue(originals.stream().allMatch(display -> display.removes == 1));
    }

    @Test
    void scalesShardsWithTornadoSizeAndReplayingATickIsDeterministic() {
        final TestWorld world = new TestWorld();
        final Location base = new Location(world, 0, 64, 0);
        final TornadoDebris debris = new TornadoDebris(1, 1, 1, 0.16);
        debris.update(base, 14, 7, 80);
        final TestDisplay display = world.displays.getFirst();
        final Vector3f fullSize = display.transformation.scale();
        final Location fullLocation = display.location.clone();
        final Quaternionf fullRotation = display.transformation.leftRotation();
        debris.update(base, 14, 7, 80);
        assertEquals(fullRotation, display.transformation.leftRotation());
        assertEquals(fullLocation.getX(), display.location.getX());
        assertEquals(fullLocation.getY(), display.location.getY());
        assertEquals(fullLocation.getZ(), display.location.getZ());
        debris.update(base, 3.5, 1.75, 80);
        final Vector3f smallSize = display.transformation.scale();
        assertEquals(fullSize.x * 0.25F, smallSize.x, 0.00001F);
        assertEquals(fullSize.y * 0.25F, smallSize.y, 0.00001F);
        assertEquals(fullSize.z * 0.25F, smallSize.z, 0.00001F);
        debris.remove();
    }

    @Test
    void clampsCountAndMalformedSettingsWithoutGrowingThePool() {
        final TestWorld world = new TestWorld();
        final Location base = new Location(world, 0, 64, 0);
        new TornadoDebris(-10, 0.2, 0.5, 0.16).update(base, 14, 7, 0);
        assertTrue(world.displays.isEmpty());
        final TornadoDebris debris = new TornadoDebris(Integer.MAX_VALUE,
                Double.NaN, Double.POSITIVE_INFINITY, Double.NaN);
        debris.update(base, Double.NaN, Double.POSITIVE_INFINITY, Long.MAX_VALUE);
        assertEquals(32, world.displays.size());
        for (final TestDisplay display : world.displays) {
            assertTrue(Double.isFinite(display.location.getX()));
            assertTrue(Double.isFinite(display.location.getY()));
            assertTrue(Double.isFinite(display.location.getZ()));
            final Vector3f scale = display.transformation.scale();
            assertTrue(scale.x >= 0.08F && scale.x <= 2.0F);
            assertTrue(scale.y >= 0.08F && scale.y <= 2.0F);
            assertTrue(scale.z >= 0.08F && scale.z <= 2.0F);
        }
        world.displays.getFirst().remove();
        debris.update(base, 14, 7, 0);
        assertEquals(32, world.displays.size(), "externally removed shards must not cause spawn churn");
        debris.remove();
    }

    @Test
    void removesAllAlreadySpawnedDisplaysWhenALaterSpawnFails() {
        final TestWorld world = new TestWorld();
        world.failSpawnAt = 3;
        final Location base = new Location(world, 0, 64, 0);
        final TornadoDebris debris = new TornadoDebris(12, 0.2, 0.6, 0.16);
        assertThrows(IllegalStateException.class, () -> debris.update(base, 14, 7, 0));
        assertEquals(3, world.displays.size());
        assertTrue(world.displays.stream().noneMatch(TestDisplay::isValid));
        debris.update(base, 14, 7, 1);
        assertEquals(3, world.displays.size(), "a failed creation must not retry every tick");
    }

    @Test
    void tracksTheNewestDisplayBeforeAnyConfigurationCanFail() {
        final TestWorld world = new TestWorld();
        world.failSetupAt = 2;
        final TornadoDebris debris = new TornadoDebris(12, 0.2, 0.6, 0.16);
        assertThrows(IllegalStateException.class,
                () -> debris.update(new Location(world, 0, 64, 0), 14, 7, 0));
        assertEquals(3, world.displays.size());
        assertTrue(world.displays.stream().allMatch(display -> display.removes == 1));
        assertTrue(world.displays.stream().noneMatch(TestDisplay::isValid));
    }

    @Test
    void laterRenderingFailureAlsoReleasesTheWholePool() {
        final TestWorld world = new TestWorld();
        final Location base = new Location(world, 0, 64, 0);
        final TornadoDebris debris = new TornadoDebris(12, 0.2, 0.6, 0.16);
        debris.update(base, 14, 7, 0);
        world.displays.get(4).failUpdate = true;
        assertThrows(IllegalStateException.class, () -> debris.update(base, 14, 7, 1));
        assertTrue(world.displays.stream().noneMatch(TestDisplay::isValid));
        debris.update(base, 14, 7, 2);
        assertEquals(12, world.displays.size());
    }

    @Test
    void attemptsEveryRemovalEvenIfOnePlatformRemovalThrows() {
        final TestWorld world = new TestWorld();
        final Location base = new Location(world, 0, 64, 0);
        final TornadoDebris debris = new TornadoDebris(12, 0.2, 0.6, 0.16);
        debris.update(base, 14, 7, 0);
        world.displays.getFirst().failRemove = true;
        assertThrows(IllegalStateException.class, debris::remove);
        assertTrue(world.displays.stream().allMatch(display -> display.removes == 1));
        debris.remove();
        assertTrue(world.displays.stream().allMatch(display -> display.removes == 1));
    }

    private static final class TestWorld extends World {
        private final List<TestDisplay> displays = new ArrayList<>();
        private int failSpawnAt = -1;
        private int failSetupAt = -1;

        @Override public <T> T spawn(final Location location, final Class<T> type) {
            assertEquals(BlockDisplay.class, type);
            if (this.displays.size() == this.failSpawnAt) throw new IllegalStateException("spawn failed");
            final TestDisplay display = new TestDisplay(location);
            display.failSetup = this.displays.size() == this.failSetupAt;
            this.displays.add(display);
            return type.cast(display);
        }
    }

    private static final class TestDisplay extends BlockDisplay {
        private Location location;
        private BlockData data;
        private Transformation transformation;
        private boolean valid = true;
        private boolean persistent = true;
        private boolean gravity = true;
        private boolean silent;
        private boolean invulnerable;
        private boolean failSetup;
        private boolean failUpdate;
        private boolean failRemove;
        private int interpolationDuration;
        private int teleportDuration;
        private int teleports;
        private int removes;

        private TestDisplay(final Location location) { this.location = location.clone(); }
        @Override public boolean isValid() { return this.valid; }
        @Override public void remove() {
            this.removes++;
            this.valid = false;
            if (this.failRemove) throw new IllegalStateException("removal failed");
        }
        @Override public boolean teleport(final Location location) {
            this.teleports++;
            if (this.failUpdate) throw new IllegalStateException("update failed");
            this.location = location.clone();
            return true;
        }
        @Override public void setBlock(final BlockData data) {
            if (this.failSetup) throw new IllegalStateException("configuration failed");
            this.data = data.clone();
        }
        @Override public void setTransformation(final Transformation value) { this.transformation = value; }
        @Override public void setPersistent(final boolean value) { this.persistent = value; }
        @Override public void setGravity(final boolean value) { this.gravity = value; }
        @Override public void setSilent(final boolean value) { this.silent = value; }
        @Override public void setInvulnerable(final boolean value) { this.invulnerable = value; }
        @Override public void setInterpolationDuration(final int value) { this.interpolationDuration = value; }
        @Override public void setTeleportDuration(final int value) { this.teleportDuration = value; }
    }
}
