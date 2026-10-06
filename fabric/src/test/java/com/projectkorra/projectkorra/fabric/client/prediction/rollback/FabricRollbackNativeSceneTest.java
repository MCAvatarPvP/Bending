package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.scoreboard.Scoreboard;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackNativeSceneTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    @Test void capturedNativeWorldAndRosterAssembleAndRewindTogether() throws Exception {
        var seed = seed(); var players = players(); var scene = scene(seed, players, players.players().keySet());
        assertEquals(seed.identity().name(), scene.logical().getName());
        assertEquals(seed.settings().gameTime(), scene.roster().world().world().getTime());
        assertEquals(players.players().keySet(), scene.roster().players().keySet());
        for (var entry : scene.roster().players().entrySet()) {
            var body = entry.getValue().ownedPlayer();
            assertSame(body, scene.roster().world().world().getPlayerByUuid(entry.getKey()));
            assertSame(scene.roster().world().world(), body.getEntityWorld());
        }
        var checkpoint = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 500_000)
                .capture(List.of(scene), List.of());
        long day = scene.logical().getFullTime();
        scene.services().advanceTick(1);
        assertEquals(players.worldTime() + 1, scene.roster().world().world().getTime());
        var block = scene.logical().getBlockAt(0, 4, 0);
        var glow = new BlockData(Material.GLOWSTONE); glow.setExactState("minecraft:glowstone");
        block.setBlockData(glow, false);
        assertEquals(15, scene.lighting().block(new RollbackBlockStore.Position(0, 4, 0)));
        var state = scene.roster().players().values().iterator().next(); var before = state.ownedPlayer().getEntityPos();
        state.ownedPlayer().setPosition(2, 10, 2);
        checkpoint.restore();
        assertEquals(before, state.ownedPlayer().getEntityPos());
        assertEquals(day, scene.logical().getFullTime());
        assertEquals(players.worldTime(), scene.roster().world().world().getTime());
        assertEquals(Material.AIR, block.getType());
        assertEquals(0, scene.lighting().block(new RollbackBlockStore.Position(0, 4, 0)));
        scene.services().advanceTick(1);
        assertEquals(players.worldTime() + 1, scene.roster().world().world().getTime());
    }
    @Test void rejectsMissingLightAndMismatchedRosterBeforeReturningAScene() throws Exception {
        var seed = seed(); var players = players();
        assertThrows(IllegalArgumentException.class, () -> scene(seed, players, Set.of()));
        var absent = new RollbackWorldSeed(seed.world(), seed.name(), seed.minimumY(), seed.maximumY(), seed.dayTime(), seed.storm(),
                seed.settings(), seed.border(), seed.environment(), seed.terrain());
        assertThrows(IllegalStateException.class, () -> scene(absent, players, players.players().keySet()));
        assertThrows(IllegalArgumentException.class, () -> scene(seed, new RollbackRosterData(players.worldTime() + 1, players.players()), players.players().keySet()));
    }
    private static RollbackRosterData players() throws Exception {
        try (var in = Objects.requireNonNull(FabricRollbackNativeSceneTest.class.getResourceAsStream("/rollback/player-roster.base64"))) {
            return RollbackRosterData.decode(Base64.getMimeDecoder().decode(in.readAllBytes()));
        }
    }
    private static RollbackWorldSeed seed() throws Exception {
        var bounds = new RollbackBlockStore.Bounds(-16, -64, -16, 32, 320, 32);
        int count = 48 * 384 * 48;
        var air = new BlockData(Material.AIR); air.setExactState("minecraft:air");
        var terrain = new RollbackTerrainSeed.Builder(bounds, count * 4 + 1024);
        terrain.append(new RollbackBlockStore.Cell(air, null, Biome.DESERT, (byte) 15, .8, .4), count);
        return new RollbackWorldSeed(new UUID(0, 1), "assembled", -64, 320, 6000, false,
                FabricRollbackWorldServicesTest.settings(), new RollbackBorderData(0, 0, 29999984, .2, 5, 5, 15, 1000, null),
                FabricRollbackEnvironmentTest.seed(), terrain.finish(), RollbackLightSeed.capture(bounds, count, p -> 15, p -> 0));
    }
    private static FabricRollbackNativeScene scene(RollbackWorldSeed seed, RollbackRosterData players, Set<UUID> participants) {
        var tiles = new Tiles(); var geometry = new FabricRollbackGeometry(seed.minimumY(), seed.maximumY(), tiles);
        var blocks = (RollbackBlockStore.Rules) Proxy.newProxyInstance(FabricRollbackNativeSceneTest.class.getClassLoader(), new Class<?>[]{RollbackBlockStore.Rules.class}, (proxy, method, args) -> {
            if (method.getName().equals("geometry")) return geometry.geometry((RollbackBlockStore) args[0], (RollbackBlockStore.Position) args[1], (BlockData) args[2]);
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            throw new AssertionError("Unexpected block action " + method);
        });
        var bindings = new FabricRollbackNativeScene.Bindings(blocks, unused(RollbackItems.class), unused(RollbackWorld.Actions.class), tiles, new Events(), new Scoreboard());
        return new FabricRollbackNativeScene(seed, players, participants, FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, bindings, 0, 100, 128, 300_000);
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> { throw new AssertionError("Unexpected " + method); }));
    }
    private static final class Tiles implements FabricRollbackWorldQueries.BlockEntities<Void> {
        @Override public net.minecraft.block.entity.BlockEntity get(RollbackBlockStore terrain, RollbackBlockStore.Position pos, net.minecraft.block.BlockState state) {
            if (state.hasBlockEntity()) throw new AssertionError("Fixture has no block entities"); return null;
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
    private static final class Events implements FabricRollbackWorldServices.Events<Void> {
        @Override public void gameEvent(net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.event.GameEvent> event, net.minecraft.util.math.Vec3d pos, net.minecraft.world.event.GameEvent.Emitter emitter) { throw new AssertionError("No gameplay event expected"); }
        @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, net.minecraft.entity.Entity entity) { throw new AssertionError("No waypoint expected"); }
        @Override public void output(FabricRollbackWorldAccess.Output output) { throw new AssertionError("No output expected"); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
}
