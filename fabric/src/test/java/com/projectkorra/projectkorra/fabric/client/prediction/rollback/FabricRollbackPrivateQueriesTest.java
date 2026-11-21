package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.Difficulty;
import net.minecraft.world.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPrivateQueriesTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void importedPlayersSeeAndRewindLogicalTerrainMembershipNativeMovementAndWorldConditions() throws Exception {
        var scene = new Scene(); var nativeWorld = scene.roster.world().world();
        var a = scene.roster.players().get(A).ownedPlayer(); var b = scene.roster.players().get(B).ownedPlayer();
        var bounds = new Box(0, 0, 1.5, 1, 3, 2.5);
        assertEquals(List.of(b), nativeWorld.getOtherEntities(a, bounds));
        assertEquals(Difficulty.HARD, nativeWorld.getDifficulty()); assertEquals(20, nativeWorld.getTime());
        assertEquals("minecraft:desert", nativeWorld.getBiome(BlockPos.ORIGIN).getKey().orElseThrow().getValue().toString());
        var wall = new BlockPos(0, 1, 1); scene.block(wall, Material.STONE, "minecraft:stone");
        var blocked = new Box(.2, 1, 1.1, .8, 2.8, 1.7);
        assertFalse(nativeWorld.isSpaceEmpty(a, blocked));
        assertEquals(2, nativeWorld.getTopPosition(Heightmap.Type.MOTION_BLOCKING, wall).getY());
        var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        scene.block(wall, Material.AIR, "minecraft:air"); b.setPosition(3, 1, 3);
        assertTrue(nativeWorld.getOtherEntities(a, bounds).isEmpty()); assertTrue(nativeWorld.isSpaceEmpty(a, blocked));
        assertEquals(1, nativeWorld.getTopPosition(Heightmap.Type.MOTION_BLOCKING, wall).getY());
        scene.logical.conditions(new RollbackWorld.Conditions(900, 900, "EASY", true, Set.of())); scene.worldServices.advanceTick();
        assertTrue(nativeWorld.isRaining()); assertEquals(Difficulty.EASY, nativeWorld.getDifficulty()); assertEquals(21, nativeWorld.getTime());
        assertFalse(nativeWorld.isChunkLoaded(0, 0));
        saved.restore();
        assertEquals(List.of(b), nativeWorld.getOtherEntities(a, bounds)); assertFalse(nativeWorld.isSpaceEmpty(a, blocked));
        assertEquals(2, nativeWorld.getTopPosition(Heightmap.Type.MOTION_BLOCKING, wall).getY());
        assertFalse(nativeWorld.isRaining()); assertEquals(Difficulty.HARD, nativeWorld.getDifficulty()); assertEquals(20, nativeWorld.getTime());
        assertTrue(nativeWorld.isChunkLoaded(0, 0));
        var state = scene.roster.players().get(A); var beforeMove = state.readKinematics();
        state.movementInput(new RollbackMovementInput(0, 1, false, 0, 0)); state.tick();
        var afterMove = state.readKinematics(); assertNotEquals(beforeMove, afterMove);
        var output = List.copyOf(scene.services.fixture.outputs);
        assertTrue(output.stream().anyMatch(value -> value instanceof FabricRollbackPacketData.Tracked tracked && tracked.data() instanceof FabricRollbackPacketData.Equipment));
        saved.restore();
        state.movementInput(new RollbackMovementInput(0, 1, false, 0, 0)); state.tick();
        assertEquals(afterMove, state.readKinematics());
        assertEquals(output, scene.services.fixture.outputs);
    }

    @Test void trackedEquipmentStatusAndAnimationDetachTheirPayloadAndRewindWithoutSendingPackets() throws Exception {
        var scene = new Scene(); var a = scene.roster.players().get(A).ownedPlayer();
        var chunks = ((net.minecraft.server.world.ServerWorld) scene.roster.world().world()).getChunkManager();
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.roster.world()), List.of());
        var item = new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD); item.setDamage(7);
        var packet = new net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket(a.getId(), List.of(com.mojang.datafixers.util.Pair.of(net.minecraft.entity.EquipmentSlot.MAINHAND, item)));
        chunks.sendToOtherNearbyPlayers(a, packet);
        chunks.sendToNearbyPlayers(a, new net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket(a, 0));
        chunks.sendToOtherNearbyPlayers(a, new net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket(a, (byte) 55));
        var journal = scene.services.fixture.outputs;
        var equipment = assertInstanceOf(FabricRollbackPacketData.Tracked.class, journal.get(0));
        assertEquals(A, equipment.entity()); assertFalse(equipment.includeSelf());
        var data = assertInstanceOf(FabricRollbackPacketData.Equipment.class, equipment.data());
        item.setDamage(60);
        assertEquals(7, new FabricRollbackItemCodec(scene.queries.registries()).decode(data.slots().getFirst().item()).getDamage());
        assertTrue(assertInstanceOf(FabricRollbackPacketData.Tracked.class, journal.get(1)).includeSelf());
        assertEquals(new FabricRollbackPacketData.Animation(a.getId(), 0), ((FabricRollbackPacketData.Tracked) journal.get(1)).data());
        assertEquals(new FabricRollbackPacketData.Status(a.getId(), (byte) 55), ((FabricRollbackPacketData.Tracked) journal.get(2)).data());
        assertThrows(IllegalArgumentException.class, () -> chunks.sendToOtherNearbyPlayers(a,
                new net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket(999, packet.getEquipmentList())));
        assertThrows(IllegalArgumentException.class, () -> chunks.sendToOtherNearbyPlayers(a, new net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket(a)));
        var foreign = new Scene().roster.players().get(A).ownedPlayer();
        assertThrows(IllegalArgumentException.class, () -> chunks.sendToOtherNearbyPlayers(foreign, packet));
        assertEquals(3, journal.size());
        checkpoint.restore(); assertTrue(journal.isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> data.slots().clear());
    }

    @Test void privatePlacementQueriesUsePaperToleranceAndRestoredEntityFlags() throws Exception {
        var scene = new Scene(); var a = scene.roster.players().get(A).ownedPlayer(); var b = scene.roster.players().get(B).ownedPlayer();
        var world = scene.roster.world().world(); var body = b.getBoundingBox();
        b.intersectionChecked = true;
        var contact = net.minecraft.util.shape.VoxelShapes.cuboid(body.minX - .125, body.minY + .125, body.minZ + .125,
                body.minX + 5e-8, body.maxY - .125, body.maxZ - .125);
        var overlap = net.minecraft.util.shape.VoxelShapes.cuboid(body.minX - .125, body.minY + .125, body.minZ + .125,
                body.minX + 2e-7, body.maxY - .125, body.maxZ - .125);
        assertTrue(world.doesNotIntersectEntities(a, contact));
        assertFalse(world.doesNotIntersectEntities(a, overlap));
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        b.intersectionChecked = false;
        assertTrue(world.doesNotIntersectEntities(a, overlap));
        checkpoint.restore(); assertFalse(world.doesNotIntersectEntities(a, overlap));
        var hollow = net.minecraft.util.shape.VoxelShapes.union(
                net.minecraft.util.shape.VoxelShapes.cuboid(body.minX - .25, body.minY, body.minZ - .25, body.minX - .125, body.maxY, body.maxZ + .25),
                net.minecraft.util.shape.VoxelShapes.cuboid(body.maxX + .125, body.minY, body.minZ - .25, body.maxX + .25, body.maxY, body.maxZ + .25));
        assertTrue(world.doesNotIntersectEntities(a, hollow));
        b.setPosition(b.getX() + .4, b.getY(), b.getZ());
        assertFalse(world.doesNotIntersectEntities(a, hollow));
        assertTrue(scene.services.fixture.outputs.isEmpty());
    }

    @Test void partialUnboundForeignAndUncapturedWorldQueriesRejectWithoutLiveFallback() throws Exception {
        var logical = FabricRollbackWorldQueriesTest.world();
        var queries = new FabricRollbackWorldQueries(logical, FabricRollbackWorldServicesTest.services(FabricRollbackWorldServicesTest.settings(), new FabricRollbackWorldServicesTest.Callbacks(logical)), new Tiles());
        assertThrows(IllegalStateException.class, queries::captureRollbackState);
        assertThrows(IllegalStateException.class, () -> queries.otherEntities(null, new Box(0, 0, 0, 1, 1, 1)));
        var scene = new Scene(); var other = new Scene();
        assertThrows(IllegalArgumentException.class, () -> queries.bindRoster(scene.roster));
        assertThrows(IllegalStateException.class, () -> scene.queries.bindRoster(scene.roster));
        assertThrows(IllegalArgumentException.class, () -> scene.queries.otherEntities(other.roster.players().get(A).ownedPlayer(), new Box(0, 0, 0, 1, 1, 1)));
        assertThrows(IllegalStateException.class, () -> scene.queries.getBlockState(new BlockPos(6, 0, 0)));
        assertThrows(IllegalStateException.class, () -> scene.queries.isChunkLoaded(1, 0));
        assertThrows(IllegalStateException.class, () -> scene.queries.otherEntities(null, new Box(0, 0, 0, 6, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> scene.queries.otherEntities(null, new Box(Double.NaN, 0, 0, 1, 1, 1)));
        var b = scene.roster.players().get(B).ownedPlayer(); b.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        assertEquals(List.of(scene.roster.players().get(A).ownedPlayer()), scene.queries.getPlayers());
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(scene.queries::getPlayers).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertTrue(scene.services.fixture.outputs.isEmpty());
    }
    static final class Scene {
        final RollbackWorld logical = FabricRollbackWorldQueriesTest.world();
        final FabricRollbackWorldServicesTest.Callbacks services = new FabricRollbackWorldServicesTest.Callbacks(logical);
        final FabricRollbackWorldServices worldServices = FabricRollbackWorldServicesTest.services(FabricRollbackWorldServicesTest.settings(), logical, services);
        final FabricRollbackWorldQueries queries = new FabricRollbackWorldQueries(logical, worldServices, new Tiles());
        final FabricRollbackRoster roster;
        Scene() throws Exception {
            logical.conditions(new RollbackWorld.Conditions(0, 0, "HARD", false, Set.of(new RollbackWorld.Chunk(-1, -1),
                    new RollbackWorld.Chunk(-1, 0), new RollbackWorld.Chunk(0, -1), new RollbackWorld.Chunk(0, 0))));
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 4; z++) block(new BlockPos(x, 0, z), Material.STONE, "minecraft:stone");
            try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-roster.base64"))) {
                roster = FabricRollbackRoster.instantiate(RollbackRosterData.decode(Base64.getMimeDecoder().decode(input.readAllBytes())), Set.of(A, B), queries, 0, 300_000);
            }
            queries.bindRoster(roster);
        }
        void block(BlockPos pos, Material material, String exact) {
            var data = new BlockData(material); data.setExactState(exact); logical.getBlockAt(pos.getX(), pos.getY(), pos.getZ()).setBlockData(data, false);
        }
    }
    private static final class Tiles implements FabricRollbackWorldQueries.BlockEntities<Void> {
        @Override public BlockEntity get(RollbackBlockStore terrain, RollbackBlockStore.Position position, BlockState state) { throw new AssertionError("No tile fixture"); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
    }
}
