package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackPrivateQueriesNativeTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    private static final PaperRollbackPlayerFields.Field<Entity.RemovalReason> REMOVED =
            new PaperRollbackPlayerFields.Field<>(Entity.class, "removalReason", Entity.RemovalReason.class);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void importedServerPlayersTickAndRewindThroughCapturedTerrainAndNativeQueries() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var nativeWorld = scene.world.world();
            var a = scene.players.get(A).ownedPlayer(); var b = scene.players.get(B).ownedPlayer();
            var bounds = new AABB(0, 0, 1.5, 1, 3, 2.5);
            assertEquals(List.of(b), nativeWorld.getEntities(a, bounds));
            assertEquals("minecraft:desert", nativeWorld.getBiome(BlockPos.ZERO).unwrapKey().orElseThrow().identifier().toString());
            var wall = new BlockPos(0, 1, 1); scene.block(wall, Material.STONE, "minecraft:stone");
            var blocked = new AABB(.2, 1, 1.1, .8, 2.8, 1.7);
            assertFalse(nativeWorld.noCollision(a, blocked));
            assertEquals(2, scene.queries.topPosition(Heightmap.Types.MOTION_BLOCKING, wall).getY());
            var chunk = nativeWorld.getChunkSource().getChunkAtIfLoadedImmediately(0, 0);
            assertNotNull(chunk);
            assertNotNull(nativeWorld.getChunkSource().getChunkAtIfLoadedImmediately(-1, -1));
            assertThrows(IllegalStateException.class, () -> nativeWorld.getChunkSource().getChunkAtIfLoadedImmediately(1, 0));
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            scene.block(wall, Material.AIR, "minecraft:air"); b.setPos(3, 1, 3);
            assertTrue(nativeWorld.getEntities(a, bounds).isEmpty()); assertTrue(nativeWorld.noCollision(a, blocked));
            assertEquals(1, scene.queries.topPosition(Heightmap.Types.MOTION_BLOCKING, wall).getY());
            scene.logical.conditions(new RollbackWorld.Conditions(900, 900, "EASY", true, Set.of())); scene.worldServices.advanceTick();
            assertTrue(nativeWorld.isRaining()); assertEquals(21, nativeWorld.getGameTime());
            assertEquals(net.minecraft.world.Difficulty.EASY, nativeWorld.getDifficulty());
            assertFalse(scene.queries.chunksLoaded(-1, -1, 1, 1));
            assertNull(nativeWorld.getChunkSource().getChunkAtIfLoadedImmediately(0, 0));
            assertNull(nativeWorld.getChunkIfLoadedImmediately(wall));
            assertNull(nativeWorld.getBlockStateIfLoaded(wall));
            assertNull(nativeWorld.getFluidIfLoaded(wall));
            saved.restore();
            assertSame(chunk, nativeWorld.getChunkSource().getChunkAtIfLoadedImmediately(0, 0));
            assertEquals(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), nativeWorld.getBlockStateIfLoaded(wall));
            assertEquals(List.of(b), nativeWorld.getEntities(a, bounds)); assertFalse(nativeWorld.noCollision(a, blocked));
            assertEquals(2, scene.queries.topPosition(Heightmap.Types.MOTION_BLOCKING, wall).getY());
            assertFalse(nativeWorld.isRaining()); assertEquals(20, nativeWorld.getGameTime()); assertTrue(scene.queries.chunksLoaded(-1, -1, 1, 1));
            assertEquals(net.minecraft.world.Difficulty.HARD, nativeWorld.getDifficulty());
            var state = scene.players.get(A); var before = state.readKinematics();
            tick(state); var after = state.readKinematics(); var output = List.copyOf(scene.combat.outputs);
            assertNotEquals(before, after);
            assertTrue(output.stream().anyMatch(value -> value instanceof PaperRollbackPacketData.Tracked tracked && tracked.data() instanceof PaperRollbackPacketData.Equipment));
            saved.restore(); tick(state);
            assertEquals(after, state.readKinematics()); assertEquals(output, scene.combat.outputs);
            return null;
        });
    }

    @Test void nativePlacementQueriesRespectPaperToleranceAndRestoredEntityFlags() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var a = scene.players.get(A).ownedPlayer(); var b = scene.players.get(B).ownedPlayer();
            var world = scene.world.world(); var body = b.getBoundingBox(); b.blocksBuilding = true;
            var contact = net.minecraft.world.phys.shapes.Shapes.box(body.minX - .125, body.minY + .125, body.minZ + .125,
                    body.minX + 5e-8, body.maxY - .125, body.maxZ - .125);
            var overlap = net.minecraft.world.phys.shapes.Shapes.box(body.minX - .125, body.minY + .125, body.minZ + .125,
                    body.minX + 2e-7, body.maxY - .125, body.maxZ - .125);
            assertTrue(world.isUnobstructed(a, contact)); assertFalse(world.isUnobstructed(a, overlap));
            var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
            b.blocksBuilding = false; assertTrue(world.isUnobstructed(a, overlap));
            saved.restore(); assertFalse(world.isUnobstructed(a, overlap));
            var hollow = net.minecraft.world.phys.shapes.Shapes.or(
                    net.minecraft.world.phys.shapes.Shapes.box(body.minX - .25, body.minY, body.minZ - .25, body.minX - .125, body.maxY, body.maxZ + .25),
                    net.minecraft.world.phys.shapes.Shapes.box(body.maxX + .125, body.minY, body.minZ - .25, body.maxX + .25, body.maxY, body.maxZ + .25));
            assertTrue(world.isUnobstructed(a, hollow));
            b.setPos(b.getX() + .4, b.getY(), b.getZ()); assertFalse(world.isUnobstructed(a, hollow));
            assertTrue(scene.combat.outputs.isEmpty()); return null;
        });
    }

    @Test void rosterOwnershipLoadedBoundsAndUnboundQueriesRejectBeforeAnyFallback() throws Exception {
        onTickThread(() -> {
            var scene = new Scene(); var other = new Scene();
            var unbound = new PaperRollbackWorldQueries(PaperRollbackWorldQueriesNativeTest.world(), scene.worldServices, new Tiles());
            assertThrows(IllegalStateException.class, unbound::captureRollbackState);
            assertThrows(IllegalStateException.class, () -> unbound.getEntities((Entity) null, new AABB(0, 0, 0, 1, 1, 1), null));
            assertThrows(IllegalArgumentException.class, () -> unbound.bindRoster(scene.world, scene.players));
            assertThrows(IllegalStateException.class, () -> scene.queries.bindRoster(scene.world, scene.players));
            assertThrows(IllegalStateException.class, () -> Scene.player(scene.world, new UUID(0, 99), 3));
            assertThrows(IllegalArgumentException.class, () -> scene.queries.getEntities(other.players.get(A).ownedPlayer(), new AABB(0, 0, 0, 1, 1, 1), null));
            assertThrows(IllegalStateException.class, () -> scene.queries.getBlockState(new BlockPos(6, 0, 0)));
            assertThrows(IllegalStateException.class, () -> scene.queries.getChunkForCollisions(1, 0));
            assertThrows(IllegalStateException.class, () -> scene.queries.chunksLoaded(0, 0, 6, 0));
            assertThrows(IllegalStateException.class, () -> scene.queries.getEntities((Entity) null, new AABB(0, 0, 0, 6, 1, 1), null));
            assertThrows(IllegalArgumentException.class, () -> scene.queries.getEntities((Entity) null, new AABB(Double.NaN, 0, 0, 1, 1, 1), null));
            // Stage an already-removed body for the query filter. Native removal callbacks
            // require the separate entity-lifecycle adapter and deliberately remain unbound.
            REMOVED.set(scene.players.get(B).ownedPlayer(), Entity.RemovalReason.UNLOADED_TO_CHUNK);
            assertEquals(List.of(scene.players.get(A).ownedPlayer()), scene.queries.players());
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(scene.queries::players).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            assertTrue(scene.combat.outputs.isEmpty());
            return null;
        });
    }
    private static void tick(PaperRollbackNativePlayerState state) {
        try (var clock = RollbackClock.at(0, 0, 1, 50_000_000)) {
            state.movementInput(new RollbackMovementInput(0, 1, false, 0, 0)); state.tick();
        }
    }
    static final class Scene {
        final RollbackWorld logical = PaperRollbackWorldQueriesNativeTest.world();
        final PaperRollbackWorldServicesNativeTest.Callbacks callbacks = new PaperRollbackWorldServicesNativeTest.Callbacks(logical);
        final PaperRollbackDamageNativeTest.Combat combat = callbacks.combat;
        final PaperRollbackWorldServices worldServices = PaperRollbackWorldServicesNativeTest.services(PaperRollbackWorldServicesNativeTest.settings(), logical, callbacks);
        final PaperRollbackWorldQueries queries = new PaperRollbackWorldQueries(logical, worldServices, new Tiles());
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(queries, worldServices, 4);
        final Map<UUID, PaperRollbackNativePlayerState> players;
        Scene() {
            logical.conditions(new RollbackWorld.Conditions(0, 0, "HARD", false, Set.of(new RollbackWorld.Chunk(-1, -1), new RollbackWorld.Chunk(-1, 0),
                    new RollbackWorld.Chunk(0, -1), new RollbackWorld.Chunk(0, 0))));
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 4; z++) block(new BlockPos(x, 0, z), Material.STONE, "minecraft:stone");
            var sourceCombat = new PaperRollbackDamageNativeTest.Combat();
            var sourceWorld = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), sourceCombat, 4);
            var a = player(sourceWorld, A, .5); var b = player(sourceWorld, B, 2);
            a.getInventory().setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD));
            var captured = PaperRollbackRosterSeed.capture(List.of(a, b), 5_000_000_000L);
            players = captured.instantiate(world, 0, Map.of(A, services(A), B, services(B)));
            assertThrows(IllegalArgumentException.class, () -> queries.bindRoster(world, Map.of(A, players.get(A))));
            queries.bindRoster(world, players);
        }
        private static ServerPlayer player(PaperRollbackWorldAccess world, UUID id, double z) {
            var state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(id, "player" + id.getLeastSignificantBits()),
                    ClientInformation.createDefault(), GameType.SURVIVAL, id.getLeastSignificantBits(), 300_000);
            var player = (ServerPlayer) state.ownedPlayer(); player.valid = true; player.setPos(.5, 1, z); player.setOnGround(true); player.tickCount = 100;
            player.setYRot(0); player.setXRot(0); player.yHeadRot = 0; player.yHeadRotO = 0; player.yBodyRot = 0; player.yBodyRotO = 0;
            return player;
        }
        private static PaperRollbackRosterSeed.Services services(UUID id) {
            return new PaperRollbackRosterSeed.Services(id.getLeastSignificantBits(), 300_000, PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty());
        }
        void block(BlockPos pos, Material material, String exact) {
            var data = new BlockData(material); data.setExactState(exact); logical.getBlockAt(pos.getX(), pos.getY(), pos.getZ()).setBlockData(data, false);
        }
    }
    private static final class Tiles implements PaperRollbackWorldQueries.BlockEntities<Void> {
        @Override public Object get(RollbackBlockStore terrain, RollbackBlockStore.Position pos, Object state) { throw new AssertionError("No tile fixture"); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
}
