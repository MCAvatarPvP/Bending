package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPushOutOfBlocks;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPushOutOfBlocksTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void sharedSelectionMatchesActualClientVelocityWritesAndColumnQueryOrderAcross2048Cases() {
        var oracle = new Oracle(); int cases = 0;
        for (double[] position : new double[][]{{.5, .5}, {-.01, .99}, {0, 0}, {1.8, -1.2}}) for (float width : new float[]{.6F, 1.2F}) for (int mask = 0; mask < 256; mask++) {
            var shared = new Body(position[0], position[1], width, mask); var nativeBody = new Body(position[0], position[1], width, mask);
            oracle.run(nativeBody); RollbackPushOutOfBlocks.apply(shared);
            assertEquals(nativeBody.velocity, shared.velocity); assertEquals(nativeBody.writes, shared.writes); assertEquals(nativeBody.columns, shared.columns); cases++;
        }
        assertEquals(2048, cases);
    }

    @ParameterizedTest @ValueSource(strings = {"stone", "head_only", "glass", "slab", "no_clip"})
    void nativeSuffocationShapesAndNoClipControlTheEscapePhase(String block) throws Exception {
        var scene = new Scene(); var initial = new Vec3d(.7, -.2, .31); scene.player.setVelocity(initial);
        if (block.equals("head_only")) {
            scene.terrain.block(new BlockPos(0, 1, 0), Material.AIR, "minecraft:air");
            scene.terrain.block(new BlockPos(0, 2, 0), Material.STONE, "minecraft:stone");
        }
        if (block.equals("glass")) scene.terrain.block(new BlockPos(0, 1, 0), Material.GLASS, "minecraft:glass");
        if (block.equals("slab")) scene.terrain.block(new BlockPos(0, 1, 0), Material.STONE_SLAB, "minecraft:stone_slab[type=bottom,waterlogged=false]");
        if (block.equals("no_clip")) scene.player.noClip = true;
        scene.state.use(player -> { FabricRollbackPushOutOfBlocks.apply(player); return null; });
        assertEquals(block.equals("stone") || block.equals("head_only") ? new Vec3d(.1, -.2, .31) : initial, scene.player.getVelocity());
    }

    @Test void fullNativeTickRewindsTerrainHealthAndEscapeMovementTogether() throws Exception {
        var scene = new Scene(); var before = scene.player.getEntityPos(); var saved = scene.snapshot();
        scene.step(); var position = scene.player.getEntityPos(); var velocity = scene.player.getVelocity(); float health = scene.player.getHealth();
        assertTrue(position.x > before.x, "The escape impulse must reach native travel during this tick");
        saved.restore(); scene.terrain.block(new BlockPos(0, 1, 0), Material.AIR, "minecraft:air"); scene.step();
        assertEquals(before.x, scene.player.getX());
        saved.restore(); scene.step();
        assertEquals(position, scene.player.getEntityPos()); assertEquals(velocity, scene.player.getVelocity()); assertEquals(health, scene.player.getHealth());
    }

    private static final class Scene {
        final FabricRollbackPrivateQueriesTest.Scene terrain = new FabricRollbackPrivateQueriesTest.Scene();
        final FabricRollbackNativePlayerState state = terrain.roster.players().get(A);
        final PlayerEntity player = state.ownedPlayer();
        Scene() throws Exception {
            player.setPosition(.5, 1, .5); player.setVelocity(Vec3d.ZERO); player.setOnGround(true); player.setPose(EntityPose.STANDING);
            player.setSneaking(false); player.setSprinting(false); player.getAbilities().allowFlying = false; player.getAbilities().flying = false;
            terrain.roster.players().get(B).ownedPlayer().setPosition(3, 1, 3);
            terrain.block(new BlockPos(0, 1, 0), Material.STONE, "minecraft:stone");
        }
        void step() { state.movementInput(new RollbackMovementInput(0, 0, false, 0, 0)); state.tick(); }
        RollbackStateGraph.Snapshot snapshot() { return new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(terrain.queries), List.of()); }
    }
    private static final class Body implements RollbackPushOutOfBlocks.Body {
        final double x, z; final float width; final int mask;
        Vec3d velocity = new Vec3d(.7, -.2, .31);
        final List<Vec3d> writes = new ArrayList<>(); final List<String> columns = new ArrayList<>();
        Body(double x, double z, float width, int mask) { this.x = x; this.z = z; this.width = width; this.mask = mask; }
        @Override public double x() { return x; }
        @Override public double z() { return z; }
        @Override public float width() { return width; }
        @Override public boolean noClip() { return false; }
        @Override public boolean collides(int x, int z) { columns.add(x + ":" + z); return (mask & 1 << Math.floorMod(31 * x + 17 * z, 8)) != 0; }
        @Override public void push(RollbackPushOutOfBlocks.Direction direction) {
            write(direction.x() != 0 ? new Vec3d(.1 * direction.x(), velocity.y, velocity.z) : new Vec3d(velocity.x, velocity.y, .1 * direction.z()));
        }
        void write(Vec3d next) { velocity = next; writes.add(next); }
    }
    private static final class Oracle {
        Body body;
        final World world = RollbackNativeQueryShell.create(World.class)
                .query(value -> value.canCollide(null, new Box(0, 0, 0, 1, 1, 1)), false, args -> {
                    var box = (Box) args[1]; assertEquals(1 + 1.0E-7, box.minY); assertEquals(2.8 - 1.0E-7, box.maxY);
                    return body.collides((int) Math.floor(box.minX), (int) Math.floor(box.minZ));
                }).instance();
        final ClientPlayerEntity player = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                .constant(PlayerEntity::getEntityWorld, world).query(PlayerEntity::getVelocity, Vec3d.ZERO, args -> body.velocity)
                .outputQuery(value -> value.setVelocity(0, 0, 0), args -> body.write(new Vec3d((double) args[0], (double) args[1], (double) args[2]))).instance();
        void run(Body value) {
            body = value; player.pos = new Vec3d(body.x, 1, body.z); player.setBoundingBox(new Box(0, 1, 0, 1, 2.8, 1));
            player.pushOutOfBlocks(body.x - body.width * .35, body.z + body.width * .35);
            player.pushOutOfBlocks(body.x - body.width * .35, body.z - body.width * .35);
            player.pushOutOfBlocks(body.x + body.width * .35, body.z - body.width * .35);
            player.pushOutOfBlocks(body.x + body.width * .35, body.z + body.width * .35);
        }
    }
}
