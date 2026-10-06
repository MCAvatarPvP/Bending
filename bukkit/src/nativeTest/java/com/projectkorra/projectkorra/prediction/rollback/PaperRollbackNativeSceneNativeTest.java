package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Biome;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.flag.FeatureFlags;
import org.bukkit.Bukkit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackNativeSceneNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }
    @Test void nativeSceneImportsOwnedServerBodiesAndRewindsTerrainLightClockAndPlayers() throws Exception {
        onTickThread(() -> {
            var source = source(); var sourcePositions = source.stream().map(ServerPlayer::position).toList(); var captured = PaperRollbackRosterSeed.capture(source, 0); var seed = seed();
            var scene = scene(seed, captured, captured.participants());
            assertEquals(captured.participants(), scene.roster().keySet());
            for (var player : source) {
                var body = scene.roster().get(player.getUUID()).ownedPlayer();
                assertNotSame(player, body); assertSame(scene.world().world(), body.level());
                assertSame(body, scene.world().world().getPlayerByUUID(player.getUUID()));
                assertEquals(player.getId(), body.getId());
            }
            var checkpoint = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000).capture(List.of(scene), List.of());
            var player = scene.roster().values().iterator().next().ownedPlayer(); var before = player.position();
            scene.services().advanceTick(1); player.setPos(2, 10, 2);
            var glow = new BlockData(Material.GLOWSTONE); glow.setExactState("minecraft:glowstone");
            var block = scene.logical().getBlockAt(0, 4, 0); block.setBlockData(glow, false);
            assertEquals(15, scene.lighting().block(new RollbackBlockStore.Position(0, 4, 0)));
            assertEquals(21, scene.world().world().getGameTime());
            checkpoint.restore();
            assertEquals(before, player.position()); assertEquals(20, scene.world().world().getGameTime());
            assertEquals(6000, scene.logical().getFullTime()); assertEquals(Material.AIR, block.getType());
            assertEquals(0, scene.lighting().block(new RollbackBlockStore.Position(0, 4, 0)));
            scene.services().advanceTick(1); assertEquals(21, scene.world().world().getGameTime());
            assertEquals(sourcePositions, source.stream().map(ServerPlayer::position).toList());
            assertEquals(20, source.getFirst().level().getGameTime());
            assertNull(Bukkit.getServer()); return null;
        });
    }
    @Test void missingLightAndForeignRosterFailBeforeReturningAScene() throws Exception {
        onTickThread(() -> {
            var players = PaperRollbackRosterSeed.capture(source(), 0); var seed = seed();
            assertThrows(IllegalArgumentException.class, () -> scene(seed, players, Set.of()));
            var absent = new RollbackWorldSeed(seed.world(), seed.name(), seed.minimumY(), seed.maximumY(), seed.dayTime(), seed.storm(),
                    seed.settings(), seed.border(), seed.environment(), seed.terrain());
            assertThrows(IllegalStateException.class, () -> scene(absent, players, players.participants()));
            assertNull(Bukkit.getServer()); return null;
        });
    }
    private static List<ServerPlayer> source() {
        var world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), new PaperRollbackDamageNativeTest.Combat(), 4);
        var result = new ArrayList<ServerPlayer>();
        for (int i = 1; i <= 2; i++) {
            var state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(new UUID(0, i), "player" + i),
                    ClientInformation.createDefault(), GameType.SURVIVAL, i, 300_000);
            var player = (ServerPlayer) state.ownedPlayer(); player.valid = true; player.setId(900 + i); player.setPos(.5, 1, i);
            player.setOnGround(true); result.add(player);
        }
        return result;
    }
    private static RollbackWorldSeed seed() {
        var bounds = new RollbackBlockStore.Bounds(-16, -64, -16, 32, 320, 32); int count = 48 * 384 * 48;
        var air = new BlockData(Material.AIR); air.setExactState("minecraft:air");
        var terrain = new RollbackTerrainSeed.Builder(bounds, count * 4 + 1024);
        terrain.append(new RollbackBlockStore.Cell(air, null, Biome.DESERT, (byte) 15, .8, .4), count);
        return new RollbackWorldSeed(new UUID(0, 1), "assembled", -64, 320, 6000, false,
                PaperRollbackWorldServicesNativeTest.settings(), new RollbackBorderData(0, 0, 29999984, .2, 5, 5, 15, 1000, null),
                PaperRollbackEnvironmentNativeTest.seed(), terrain.finish(), RollbackLightSeed.capture(bounds, count, p -> 15, p -> 0));
    }
    private static PaperRollbackNativeScene scene(RollbackWorldSeed seed, PaperRollbackRosterSeed players, Set<UUID> participants) {
        var tiles = new Tiles(); var geometry = new PaperRollbackGeometry(seed.minimumY(), seed.maximumY(), tiles);
        var blocks = (RollbackBlockStore.Rules) Proxy.newProxyInstance(PaperRollbackNativeSceneNativeTest.class.getClassLoader(), new Class<?>[]{RollbackBlockStore.Rules.class}, (proxy, method, args) -> {
            if (method.getName().equals("geometry")) return geometry.geometry((RollbackBlockStore) args[0], (RollbackBlockStore.Position) args[1], (BlockData) args[2]);
            if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
            throw new AssertionError("Unexpected block action " + method);
        });
        var bindings = new PaperRollbackNativeScene.Bindings(blocks, unused(RollbackItems.class), unused(RollbackWorld.Actions.class), tiles, new Events());
        var services = new TreeMap<UUID, PaperRollbackRosterSeed.Services>();
        players.participants().forEach(id -> services.put(id, new PaperRollbackRosterSeed.Services(id.getLeastSignificantBits(), 300_000,
                PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty())));
        return new PaperRollbackNativeScene(seed, players, participants, PaperRollbackEnvironmentNativeTest.registries(),
                FeatureFlags.DEFAULT_FLAGS, bindings, services, 0, 100, 128, 9);
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> { throw new AssertionError("Unexpected " + method); }));
    }
    private static final class Tiles implements PaperRollbackWorldQueries.BlockEntities<Void> {
        @Override public Object get(RollbackBlockStore terrain, RollbackBlockStore.Position pos, Object state) {
            if (((net.minecraft.world.level.block.state.BlockState) state).hasBlockEntity()) throw new AssertionError("Fixture has no block entities"); return null;
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
    private static final class Events implements PaperRollbackWorldServices.Events<Void> {
        @Override public void event(org.bukkit.event.Event event) { throw new AssertionError("No gameplay event expected"); }
        @Override public void gameEvent(Object event, Object pos, Object context) { throw new AssertionError("No gameplay event expected"); }
        @Override public void output(PaperRollbackCombatAccess.Output output) { throw new AssertionError("No output expected"); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
}
