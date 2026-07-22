package com.projectkorra.projectkorra.prediction.block;

import com.projectkorra.projectkorra.prediction.block.TempBlockSync;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TempBlockSyncRayTest {

    @Test
    void rayQueryFindsForeignEffectsButIgnoresThePlayersOwnLayer() {
        final UUID viewer = UUID.randomUUID();
        final UUID remote = UUID.randomUUID();
        final FakeWorld world = new FakeWorld();
        final FakeBlock projectile = world.block(0, 0, 4);
        final FakePlayer player = new FakePlayer(viewer, new Location(world, 0.5, 0.5, 0));
        final UUID[] owner = {remote};
        final TempBlockSync.Listener listener = new TempBlockSync.Listener() {
            @Override
            public void onChange(final TempBlockSync.Change change) {
            }

            @Override
            public String authoritativeEffectAbility(final Block block) {
                return block == projectile ? "WaterManipulation" : "";
            }

            @Override
            public UUID authoritativeOwnerId(final Block block) {
                return block == projectile ? owner[0] : null;
            }
        };

        TempBlockSync.install(listener);
        try {
            assertSame(projectile, TempBlockSync.getAuthoritativeEffectAlongRay(
                    player, "WaterManipulation", 8, 1, true));

            owner[0] = viewer;
            assertNull(TempBlockSync.getAuthoritativeEffectAlongRay(
                    player, "WaterManipulation", 8, 1, true));
            assertSame(projectile, TempBlockSync.getAuthoritativeEffectAlongRay(
                    player, "WaterManipulation", 8, 1, false));

            assertNull(TempBlockSync.getAuthoritativeEffectAlongRay(
                    player, "IceSpike", 8, 1, false));
        } finally {
            TempBlockSync.clear(listener);
        }
    }

    private static final class FakePlayer extends Player {
        private final UUID id;
        private final Location eye;

        private FakePlayer(final UUID id, final Location eye) {
            this.id = id;
            this.eye = eye;
        }

        @Override
        public UUID getUniqueId() {
            return id;
        }

        @Override
        public Location getEyeLocation() {
            return eye.clone();
        }
    }

    private static final class FakeWorld extends World {
        private final Map<String, FakeBlock> blocks = new HashMap<>();

        private FakeBlock block(final int x, final int y, final int z) {
            return (FakeBlock) getBlockAt(x, y, z);
        }

        @Override
        public Block getBlockAt(final int x, final int y, final int z) {
            return blocks.computeIfAbsent(x + ":" + y + ":" + z,
                    ignored -> new FakeBlock(this, x, y, z));
        }
    }

    private static final class FakeBlock extends Block {
        private final World world;
        private final int x;
        private final int y;
        private final int z;

        private FakeBlock(final World world, final int x, final int y, final int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public World getWorld() {
            return world;
        }

        @Override
        public Location getLocation() {
            return new Location(world, x, y, z);
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public int getZ() {
            return z;
        }
    }
}
