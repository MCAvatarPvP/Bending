package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TempEntityLifecycleTest {
    private final FakeWorld world = new FakeWorld();

    @AfterEach void cleanup() {
        TempArmorStand.removeAll();
        TempFallingBlock.removeAllFallingBlocks();
    }

    @Test void repeatedTrapsReleaseTheirStandsImmediately() {
        for (int index = 0; index < 1000; index++) {
            final TempArmorStand temporary = new TempArmorStand(new Location(world, 0, 64, 0));
            final FakeStand stand = (FakeStand) temporary.getArmorStand();
            TempArmorStand.remove(stand);
            assertTrue(stand.removed);
            assertTrue(stand.metadata.isEmpty());
        }
        assertTrue(TempArmorStand.getTempStands().isEmpty());
    }

    @Test void externallyRemovedStandIsReleasedOnTheNextMaintenanceTick() {
        final TempArmorStand temporary = new TempArmorStand(new Location(world, 0, 64, 0));
        temporary.getArmorStand().remove();
        TempArmorStand.manage();
        assertTrue(TempArmorStand.getTempStands().isEmpty());
    }

    @Test void everyFallingBlockRemovalEntryPointClearsItsMetadataAndCallback() {
        for (int path = 0; path < 3; path++) {
            final TempFallingBlock temporary = fallingBlock();
            final FakeFalling entity = (FakeFalling) temporary.getFallingBlock();
            temporary.setOnPlace(ignored -> fail("removal must not invoke placement"));
            assertSame(temporary, entity.metadata.get("retentiontest").value());
            switch (path) {
                case 0 -> temporary.remove();
                case 1 -> TempFallingBlock.removeFallingBlock(entity);
                case 2 -> TempFallingBlock.removeAllFallingBlocks();
            }
            assertTrue(entity.removed);
            assertTrue(entity.metadata.isEmpty());
            assertNull(temporary.getOnPlace());
            assertTrue(TempFallingBlock.instances.isEmpty());
        }
    }

    @Test void placementFailureStillReleasesTheFallingBlock() {
        final TempFallingBlock temporary = fallingBlock();
        final FakeFalling entity = (FakeFalling) temporary.getFallingBlock();
        entity.onGround = true;
        temporary.setOnPlace(ignored -> { throw new IllegalStateException("placement failed"); });
        assertThrows(IllegalStateException.class, TempFallingBlock::manage);
        assertTrue(entity.metadata.isEmpty());
        assertTrue(TempFallingBlock.instances.isEmpty());
    }

    @Test void unloadedFallingBlocksDoNotWaitForTheHardTimeoutOrPlaceBlocks() {
        final TempFallingBlock temporary = fallingBlock();
        final FakeFalling entity = (FakeFalling) temporary.getFallingBlock();
        entity.valid = false;
        temporary.setOnPlace(ignored -> fail("an unload must not place a block"));
        TempFallingBlock.manage();
        assertTrue(entity.metadata.isEmpty());
        assertTrue(TempFallingBlock.instances.isEmpty());
    }

    private TempFallingBlock fallingBlock() {
        return new TempFallingBlock(new Location(world, 0, 64, 0),
                Material.STONE.createBlockData(), new Vector(), new TestAbility());
    }

    private static final class FakeWorld extends World {
        @Override public <T> T spawn(Location location, Class<T> type) {
            return type.cast(new FakeStand());
        }
        @Override public FallingBlock spawnFallingBlock(Location location, BlockData data) {
            return new FakeFalling();
        }
    }

    private static final class FakeStand extends ArmorStand {
        private final Map<String, MetadataValue> metadata = new HashMap<>();
        private boolean removed;
        @Override public void setMetadata(String key, MetadataValue value) { metadata.put(key, value); }
        @Override public void removeMetadata(String key, Object owner) { metadata.remove(key); }
        @Override public void remove() { removed = true; }
        @Override public boolean isValid() { return !removed; }
        @Override public boolean isDead() { return removed; }
    }

    private static final class FakeFalling extends FallingBlock {
        private final Map<String, MetadataValue> metadata = new HashMap<>();
        private boolean removed;
        private boolean onGround;
        private boolean valid = true;
        @Override public void setMetadata(String key, MetadataValue value) { metadata.put(key, value); }
        @Override public void removeMetadata(String key, Object owner) { metadata.remove(key); }
        @Override public void remove() { removed = true; }
        @Override public boolean isValid() { return valid && !removed; }
        @Override public boolean isDead() { return removed; }
        @Override public boolean isOnGround() { return onGround; }
    }

    private static final class TestAbility extends CoreAbility {
        private final BendingPlayer bending = new BendingPlayer(new Player());
        private TestAbility() { super(null); }
        @Override public BendingPlayer getBendingPlayer() { return bending; }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "RetentionTest"; }
        @Override public Element getElement() { return Element.EARTH; }
        @Override public Location getLocation() { return null; }
    }
}
