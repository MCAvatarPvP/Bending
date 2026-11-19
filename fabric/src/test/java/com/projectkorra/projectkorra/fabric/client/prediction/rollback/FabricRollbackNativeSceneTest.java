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
    @Test void privateDamageUsesTheCapturedRulesAndMutatesOnlyTheRewindableRoster() throws Exception {
        var seed = seed();
        var roster = players();
        var output = new RollbackStepOutput<Object>();
        var events = new FabricRollbackArenaEvents(output, seed.settings().policy());
        var scene = scene(seed, roster, roster.players().keySet(), events);
        var players = new ArrayList<>(scene.roster().players().values());
        var first = players.get(0);
        var second = players.get(1);
        var id = first.identity().uuid();
        var opponent = second.identity().uuid();
        var round = new RollbackRound(new UUID(52, 83), Map.of(id, id, opponent, opponent));
        scene.roster().world().bindRound(round, events);
        var snapshot = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 500_000)
                .capture(List.of(scene, round), List.of());
        float hunger = second.readControls().exhaustion();
        var damage = scene.roster().world().world().getDamageSources().playerAttack(first.ownedPlayer());
        events.exhaustion(second.ownedPlayer(), damage, 1);
        assertEquals(hunger + 1, second.readControls().exhaustion());
        assertFalse(events.slotAllowed(id, 0, 1, true));
        assertTrue(events.flightAllowed(id, true, false));
        assertEquals(seed.settings().policy().skipBlockedDamageTick(), events.skipDamageTickWhenShieldBlocked());
        snapshot.restore();
        assertEquals(hunger, second.readControls().exhaustion());
    }
    @Test void bootstrapSceneSharesItsPaperDefinedItemsWithTheImportedRoster() throws Exception {
        var data = bootstrapData();
        var imported = FabricRollbackNativeScene.importState(data, FabricRollbackTestRegistry.simulationRegistries(), FeatureFlags.DEFAULT_ENABLED_FEATURES,
                unused(RollbackBlockStore.Rules.class), unused(RollbackWorld.Actions.class), new Events(), new Scoreboard(), 100, 128, 300_000);
        var services = new TreeMap<UUID, RollbackRosterViews.Services>();
        data.access().players().forEach((id, access) -> services.put(id, FabricRollbackRosterTest.viewServices(access)));
        var views = imported.bindPlayerViews(services);
        var player = views.players().get(new UUID(0, 7001));
        var sword = player.getInventory().getItem(0);
        assertTrue(imported.items().nativeCopy(sword).isOf(net.minecraft.item.Items.DIAMOND_SWORD));
        assertInstanceOf(com.projectkorra.projectkorra.platform.mc.inventory.meta.PotionMeta.class,
                imported.items().create(Material.SPLASH_POTION, 1).getItemMeta());
        assertSame(imported.scene().logical(), views.world());
    }

    private RollbackBootstrapData bootstrapData() throws Exception {
        RollbackBootstrapData reference;
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/duel-bootstrap.base64"))) {
            reference = RollbackBootstrapData.decode(Base64.getMimeDecoder().decode(in.readAllBytes()), "12".repeat(32),
                    new FabricRollbackTerrainTransfer(), new RollbackTerrainCodec.Limits(4_096, 32, 1_048_576, 1_048_576, 65_536));
        }
        // The historical bootstrap fixture predates the captured lighting seed.
        return new RollbackBootstrapData(reference.session(), reference.challenge(), reference.match(), reference.round(),
                reference.epochMillis(), reference.epochNanos(), reference.definitions(), reference.sides(), seed(), reference.roster(),
                reference.configuration(), reference.access(), reference.materials(), reference.tags(), reference.server(), reference.plugins(), reference.bending());
    }

    @Test void productionDuelCreatesARealOwnedDisplayAndImportsAuthoritativeState() throws Exception {
        var data = bootstrapData();
        var duel = FabricRollbackNativeScene.importDuel(data, FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var world = duel.scene().logical();
        var pos = new com.projectkorra.projectkorra.platform.mc.Location(world, 1, 4, 1);
        var server = (net.minecraft.server.world.ServerWorld) duel.scene().roster().world().world();
        var nativeType = net.minecraft.entity.EntityType.BLOCK_DISPLAY;
        var before = nativeType.create(server, net.minecraft.entity.SpawnReason.TRIGGERED);
        var view = world.spawn(pos, com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay.class);
        var after = nativeType.create(server, net.minecraft.entity.SpawnReason.TRIGGERED);
        assertEquals(before.getId() + 1, after.getId());
        var glow = new BlockData(Material.GLOWSTONE); glow.setExactState("minecraft:glowstone"); view.setBlock(glow);
        var actor = (FabricRollbackActor) duel.actors().registry().get(view.getUniqueId());
        var nativeDisplay = (net.minecraft.entity.decoration.DisplayEntity.BlockDisplayEntity) actor.owned();
        assertSame(world, view.getWorld()); assertSame(view, view.handle()); assertSame(view, actor.view());
        assertTrue(nativeDisplay.getBlockState().isOf(net.minecraft.block.Blocks.GLOWSTONE));
        assertSame(nativeDisplay, duel.scene().roster().world().world().getEntityAnyDimension(view.getUniqueId()));
        var saved = duel.actors().registry().exportState();
        assertEquals(saved, RollbackActorData.decode(saved.encode()));
        var checkpoint = actor.captureRollbackState();
        view.setBlock(new FabricRollbackTerrainTransfer().decode(Material.STONE, "minecraft:stone"));
        checkpoint.restore();
        assertTrue(nativeDisplay.getBlockState().isOf(net.minecraft.block.Blocks.GLOWSTONE));
        duel.actors().importState(saved);
        var restored = (FabricRollbackActor) duel.actors().registry().get(view.getUniqueId());
        assertNotSame(actor, restored); assertFalse(view.isValid()); assertSame(restored.view(), world.entities().get(view.getEntityId()));
        var player = duel.scene().roster().players().values().iterator().next();
        var captured = duel.scene().roster().capture(data.epochNanos());
        var original = player.ownedPlayer().getEntityPos();
        player.ownedPlayer().setPosition(5, 4, 5);
        duel.scene().roster().importConfirmed(captured, data.epochNanos());
        assertEquals(original, player.ownedPlayer().getEntityPos());
    }

    @Test void privatePassengerTicksOnceAndEjectDetachesItsNativeVehicle() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var world = duel.scene().logical();
        var pos = new com.projectkorra.projectkorra.platform.mc.Location(world, 1, 4, 1);
        var vehicle = world.spawn(pos, com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay.class);
        var passenger = world.spawn(pos, com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay.class);
        var nativeVehicle = duel.actors().nativeEntity(vehicle);
        var nativePassenger = duel.actors().nativeEntity(passenger);
        assertTrue(vehicle.addPassenger(passenger));
        assertSame(nativeVehicle, nativePassenger.getVehicle());
        int age = nativePassenger.age;
        new FabricRollbackPhases(duel).tickWorld(1);
        assertEquals(age + 1, nativePassenger.age);
        assertTrue(vehicle.eject());
        assertNull(nativePassenger.getVehicle());
        assertFalse(passenger.isInsideVehicle());
    }

    @Test void replayResolvesTheRequestedTargetFromTheCapturedEyeAtTheInputEdge() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var participants = new ArrayList<>(duel.views().players().values());
        var player = participants.getFirst();
        var victim = participants.get(1);
        ((FabricRollbackNativePlayerState) player.body().kinematicsSource()).ownedPlayer().setPosition(1.5, 4, 1.5);
        ((FabricRollbackNativePlayerState) victim.body().kinematicsSource()).ownedPlayer().setPosition(1.5, 4, 4.5);
        var attack = new RollbackInputActions.Action(1, 1, RollbackInputActions.Kind.ATTACK, -1,
                RollbackInputActions.Hand.MAIN, new RollbackInteractionTarget.Actor(victim.getEntityId(), null));
        var forward = new RollbackPlayerInput.Edge(attack, 0, 0);
        assertTrue(RollbackInteractionTarget.allowed(player, forward, 4));
        assertFalse(RollbackInteractionTarget.allowed(player, forward, 1));
        assertFalse(RollbackInteractionTarget.allowed(player, new RollbackPlayerInput.Edge(attack, 180, 0), 4));
        var interaction = new RollbackInputActions.Action(2, 2, RollbackInputActions.Kind.RIGHT_CLICK_ENTITY, -1,
                RollbackInputActions.Hand.MAIN, new RollbackInteractionTarget.Actor(victim.getEntityId(),
                new RollbackInteractionTarget.Offset(0, .1F, -.3F)));
        assertFalse(RollbackInteractionTarget.allowed(player, new RollbackPlayerInput.Edge(interaction, 0, 0), 4));
        duel.scene().logical().getBlockAt(1, player.getEyeLocation().getBlockY(), 3).setType(Material.STONE, false);
        assertFalse(RollbackInteractionTarget.allowed(player, forward, 4));
    }

    @Test void rejectsMissingLightAndMismatchedRosterBeforeReturningAScene() throws Exception {
        var seed = seed(); var players = players();
        assertThrows(IllegalArgumentException.class, () -> scene(seed, players, Set.of()));
        var absent = new RollbackWorldSeed(seed.world(), seed.name(), seed.minimumY(), seed.maximumY(), seed.dayTime(), seed.storm(),
                seed.settings(), seed.border(), seed.environment(), seed.terrain());
        assertThrows(IllegalStateException.class, () -> scene(absent, players, players.players().keySet()));
        assertThrows(IllegalArgumentException.class, () -> scene(seed, new RollbackRosterData(players.worldTime() + 1, players.players()), players.players().keySet()));
    }
    @Test void privateNeighborShapesAndVanillaLootRewindTogether() throws Exception {
        var captured = players();
        var scene = scene(seed(), captured, captured.players().keySet());
        var block = scene.logical().getBlockAt(0, 4, 0);
        var fence = scene.logical().getBlockAt(1, 4, 0);
        fence.setType(Material.OAK_FENCE, false);
        block.setType(Material.OAK_PLANKS);
        assertTrue(fence.getBlockData().getExactState().contains("west=true"));
        assertEquals(List.of(Material.OAK_PLANKS), block.getDrops().stream().map(item -> item.getType()).toList());
        block.setType(Material.GRAVEL, false);
        var checkpoint = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000)
                .capture(List.of(scene), List.of());
        var results = new ArrayList<Material>();
        for (int i = 0; i < 64; i++) results.add(block.getDrops().iterator().next().getType());
        assertTrue(results.contains(Material.FLINT));
        assertTrue(results.contains(Material.GRAVEL));
        checkpoint.restore();
        for (var expected : results) assertEquals(expected, block.getDrops().iterator().next().getType());
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
        return scene(seed, players, participants, new Events());
    }
    private static FabricRollbackNativeScene scene(RollbackWorldSeed seed, RollbackRosterData players, Set<UUID> participants, FabricRollbackWorldServices.Events<?> events) {
        var tiles = new FabricRollbackBlockEntities(FabricRollbackTestRegistry.simulationRegistries());
        var items = new RollbackNativeItems<>(new FabricRollbackItems(FabricRollbackTestRegistry.simulationRegistries()));
        var blocks = new FabricRollbackBlockRules(items);
        var bindings = new FabricRollbackNativeScene.Bindings(blocks, items, unused(RollbackWorld.Actions.class), tiles, events, new Scoreboard());
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
