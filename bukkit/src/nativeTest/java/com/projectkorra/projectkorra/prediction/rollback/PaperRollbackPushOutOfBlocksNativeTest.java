package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.UUID;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackPushOutOfBlocksNativeTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @ParameterizedTest @ValueSource(strings = {"stone", "head_only", "glass", "slab", "no_clip"})
    void nativePaperSuffocationShapesMatchClientEscapePolicy(String block) throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var initial = new Vec3(.7, -.2, .31); scene.player.setDeltaMovement(initial);
            if (block.equals("head_only")) {
                scene.terrain.block(new BlockPos(0, 1, 0), Material.AIR, "minecraft:air");
                scene.terrain.block(new BlockPos(0, 2, 0), Material.STONE, "minecraft:stone");
            }
            if (block.equals("glass")) scene.terrain.block(new BlockPos(0, 1, 0), Material.GLASS, "minecraft:glass");
            if (block.equals("slab")) scene.terrain.block(new BlockPos(0, 1, 0), Material.STONE_SLAB, "minecraft:stone_slab[type=bottom,waterlogged=false]");
            if (block.equals("no_clip")) scene.player.noPhysics = true;
            scene.state.use(player -> { PaperRollbackPushOutOfBlocks.apply((net.minecraft.world.entity.player.Player) player); return null; });
            assertEquals(block.equals("stone") || block.equals("head_only") ? new Vec3(.1, -.2, .31) : initial, scene.player.getDeltaMovement());
            return null;
        });
    }

    @Test void copiedServerPlayerTickAppliesTheEscapePhaseAndRewindsItWithTerrainAndHealth() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var before = scene.player.position(); var saved = scene.snapshot();
            scene.step(); var position = scene.player.position(); var velocity = scene.player.getDeltaMovement(); float health = scene.player.getHealth();
            assertTrue(position.x > before.x, "The phase must execute through the copied native ServerPlayer tick");
            saved.restore(); scene.terrain.block(new BlockPos(0, 1, 0), Material.AIR, "minecraft:air"); scene.step();
            assertEquals(before.x, scene.player.getX());
            saved.restore(); scene.step();
            assertEquals(position, scene.player.position()); assertEquals(velocity, scene.player.getDeltaMovement()); assertEquals(health, scene.player.getHealth());
            assertNull(org.bukkit.Bukkit.getServer()); return null;
        });
    }
    private static final class Scene {
        final PaperRollbackPrivateQueriesNativeTest.Scene terrain = new PaperRollbackPrivateQueriesNativeTest.Scene();
        final PaperRollbackNativePlayerState state = terrain.players.get(A);
        final ServerPlayer player = (ServerPlayer) state.ownedPlayer();
        Scene() {
            player.setPos(.5, 1, .5); player.setDeltaMovement(Vec3.ZERO); player.setOnGround(true); player.setShiftKeyDown(false); player.setSprinting(false);
            player.getAbilities().mayfly = false; player.getAbilities().flying = false;
            terrain.players.get(B).ownedPlayer().setPos(3, 1, 3);
            terrain.block(new BlockPos(0, 1, 0), Material.STONE, "minecraft:stone");
        }
        void step() {
            try (var clock = RollbackClock.at(1000, 0, 1, 50_000_000)) {
                state.movementInput(new RollbackMovementInput(0, 0, false, 0, 0)); state.tick();
            }
        }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(terrain.queries), List.of()); }
    }
}
