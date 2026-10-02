package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterData;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerItems;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerVitals;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterViews;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackBootstrapData;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import java.lang.reflect.Proxy;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackRosterTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void paperImportedRosterBindsTheSameNativeMovementHealthControlsAndInventoryToAbilities() throws Exception {
        RollbackBootstrapData seed;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/duel-bootstrap.base64"))) {
            seed = RollbackBootstrapData.decode(Base64.getMimeDecoder().decode(input.readAllBytes()), "12".repeat(32), new FabricRollbackTerrainTransfer(),
                    new RollbackTerrainCodec.Limits(4_096, 32, 1_048_576, 1_048_576, 65_536));
        }
        var data = seed.roster(); var roster = FabricRollbackRoster.instantiate(data, Set.of(A, B), queries(), 0, 300_000);
        var logical = FabricRollbackWorldQueriesTest.world();
        var items = new RollbackNativeItems<>(new FabricRollbackItems(roster.world().world().getRegistryManager(),
                Map.of("minecraft:air", RollbackNativeItems.Kind.GENERIC, "minecraft:diamond_sword", RollbackNativeItems.Kind.GENERIC)));
        var bindings = new TreeMap<UUID, RollbackRosterViews.Services>();
        for (var entry : seed.access().players().entrySet()) bindings.put(entry.getKey(), viewServices(entry.getValue()));
        var last = bindings.get(B);
        var badScoreboard = new RollbackRosterViews.Services(last.profile(), last.hidden(), new Scoreboard() { }, last.body(), last.player());
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackRosterViews.bind(logical, roster, Set.of(A, B), items,
                Map.of(A, bindings.get(A), B, badScoreboard)));
        assertTrue(logical.getPlayers().isEmpty(), "A late view failure must not publish the first player");
        var views = FabricRollbackRosterViews.bind(logical, roster, Set.of(A, B), items, bindings);
        var player = views.players().get(A); var victim = views.players().get(B);
        assertTrue(player.hasPermission("BENDING.FEATURE.ALLOWED")); assertFalse(player.hasPermission("bending.feature.denied"));
        assertFalse(player.canSee(victim));
        assertThrows(IllegalStateException.class, () -> player.hasPermission("unknown.feature"));
        assertEquals(List.of(player, victim), logical.getPlayers());
        assertSame(roster.players().get(A), player.body().kinematicsSource());
        assertSame(roster.players().get(A), player.state().inventory().nativeOwner());
        assertSame(roster.players().get(A), player.state().controlSource());
        assertSame(roster.players().get(A), player.state().living().combatSource());
        var snapshot = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000).capture(List.of(views), List.of());
        player.state().hidden(Set.of()); assertTrue(player.canSee(victim));
        victim.setHealth(11); player.setVelocity(new com.projectkorra.projectkorra.platform.mc.util.Vector(.2, .3, .4));
        player.getInventory().getItem(0).setDurability((short) 12);
        assertEquals(11, roster.players().get(B).ownedPlayer().getHealth());
        assertEquals(.3, roster.players().get(A).ownedPlayer().getVelocity().y);
        assertEquals(12, roster.players().get(A).ownedPlayer().getInventory().getStack(0).getDamage());
        snapshot.restore();
        assertFalse(player.canSee(victim)); assertFalse(player.hasPermission("bending.feature.denied"));
        assertEquals(16, victim.getHealth()); assertEquals(7, player.getInventory().getItem(0).getDurability());
        abilityQueries(logical, player, victim);
        assertThrows(IllegalStateException.class, () -> FabricRollbackRosterViews.bind(logical, roster, Set.of(A, B), items, bindings));
        var rejected = FabricRollbackWorldQueriesTest.world();
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackRosterViews.bind(rejected, roster, Set.of(A), items, bindings));
        assertTrue(rejected.getPlayers().isEmpty());
    }
    private static void abilityQueries(RollbackWorld world, RollbackPlayer player, RollbackPlayer target) {
        aim(player, -2, .75, .5, -90); aim(target, 2, .75, .5, 90);
        var bottom = com.projectkorra.projectkorra.platform.mc.Material.OAK_SLAB.createBlockData();
        bottom.setExactState("minecraft:oak_slab[type=bottom,waterlogged=false]");
        world.getBlockAt(0, 0, 0).setBlockData(bottom, false);
        assertTrue(player.hasLineOfSight(target));
        assertNull(player.getTargetBlockExact(4), "The eye ray passes above the lower slab");
        assertSame(world.getBlockAt(0, 0, 0), player.getTargetBlock(null, 4), "Legacy material targeting still selects that cell");
        assertEquals(List.of(target), player.getNearbyEntities(4, 0, 0));
        var saved = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000).capture(List.of(world), List.of());
        var top = com.projectkorra.projectkorra.platform.mc.Material.OAK_SLAB.createBlockData(); top.setExactState("minecraft:oak_slab[type=top,waterlogged=false]");
        world.getBlockAt(0, 0, 0).setBlockData(top, false);
        assertFalse(player.hasLineOfSight(target));
        assertSame(world.getBlockAt(0, 0, 0), player.getTargetBlockExact(4));
        aim(target, 2, 2.75, .5, 90);
        assertTrue(player.hasLineOfSight(target), "The target's changed eye position clears the obstruction in 3D");
        saved.restore();
        assertTrue(player.hasLineOfSight(target)); assertNull(player.getTargetBlockExact(4));
        assertEquals(.75, target.getEyeLocation().getY(), 1e-6);
    }
    private static void aim(RollbackPlayer player, double x, double eyeY, double z, float yaw) {
        var old = player.body().kinematics();
        player.body().kinematics(new RollbackEntityBody.Kinematics(new RollbackEntityBody.Pose(x, eyeY - player.getEyeHeight(), z, yaw, 0),
                old.velocity(), old.bounds(), old.height(), old.onGround(), old.fallDistance(), old.velocityChanged()));
    }

    private static RollbackRosterViews.Services viewServices(RollbackPlayerAccess.Entry access) {
        var body = (RollbackEntityBody.Rules) Proxy.newProxyInstance(RollbackEntityBody.Rules.class.getClassLoader(), new Class<?>[]{RollbackEntityBody.Rules.class},
                (proxy, method, args) -> { throw new AssertionError(method); });
        var player = (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(), new Class<?>[]{RollbackPlayerState.Rules.class},
                (proxy, method, args) -> { throw new AssertionError(method); });
        return new RollbackRosterViews.Services(access, new Scoreboard(), body, player);
    }

    @Test void paperRosterConstructsRealNativePlayersThenDamageItemsMovementAndRngRewindTogether() throws Exception {
        var data = fixture(); var queries = queries();
        var roster = FabricRollbackRoster.instantiate(data, Set.of(A, B), queries, 0, 300_000);
        assertEquivalent(data, roster.capture(0), roster);
        var a = roster.players().get(A).ownedPlayer(); var b = roster.players().get(B).ownedPlayer();
        assertEquals(701, a.getId()); assertEquals(702, b.getId()); assertEquals(GameMode.ADVENTURE, a.getGameMode());
        assertEquals(GameMode.SURVIVAL, b.getGameMode()); assertEquals(16, b.getHealth()); assertSame(a, b.getPrimeAdversary());
        assertEquals(2, a.getGameProfile().properties().get("textures").size());
        assertEquals(data.players().get(A).identity(), roster.identity(A));
        assertSame(a.getInventory().getStack(0), a.getEnderChestInventory().getStack(2));
        assertEquals(7, a.getInventory().getStack(0).getDamage());
        assertTrue(queries.outputs.isEmpty());
        byte[] initial = roster.capture(0).encode();
        var saved = new RollbackStateGraph(value -> false, field -> true, 600_000).capture(List.copyOf(roster.players().values()), List.of());
        long random = a.getRandom().nextLong(); assertEquals(new Random(data.players().get(A).randomSeed()).nextLong(), random);
        assertTrue(roster.players().get(B).damage(roster.world().world().getDamageSources().playerAttack(a), 8));
        assertEquals(12, b.getHealth());
        a.getInventory().getStack(0).setDamage(60); b.setPosition(3, 1, 3); b.setVelocity(new Vec3d(1, 2, 3));
        assertFalse(Arrays.equals(initial, roster.capture(0).encode()));
        saved.restore();
        assertArrayEquals(initial, roster.capture(0).encode()); assertEquals(random, a.getRandom().nextLong());
        assertSame(a.getInventory().getStack(0), a.getEnderChestInventory().getStack(2));
        var other = FabricRollbackRoster.instantiate(data, Set.of(A, B), queries(), 0, 300_000);
        assertNotSame(a, other.players().get(A).ownedPlayer());
        assertNotSame(a.getInventory().getStack(0), other.players().get(A).ownedPlayer().getInventory().getStack(0));
        assertEquivalent(data, other.capture(0), other);
    }

    @Test void importedPlayerRandomMatchesThePaperReferenceIncludingGaussianAndForks() throws Exception {
        var data = fixture(); var roster = FabricRollbackRoster.instantiate(data, Set.of(A, B), queries(), 0, 300_000);
        var state = roster.players().get(A); var player = state.ownedPlayer();
        var saved = new RollbackStateGraph(value -> false, field -> true, 600_000).capture(List.of(state), List.of());
        String actual = FabricRollbackPaperRandomTest.line(7001, player.getRandom());
        try (var source = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/native-random.txt"))) {
            var expected = new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().filter(line -> line.startsWith("7001 ")).findFirst().orElseThrow();
            assertEquals(expected, actual.strip());
        }
        saved.restore(); assertEquals(actual, FabricRollbackPaperRandomTest.line(7001, player.getRandom()));
    }

    @Test void invalidLateComponentClockWorldAndThreadCannotPublishOrChangeAnExistingRoster() throws Exception {
        var data = fixture(); var existing = FabricRollbackRoster.instantiate(data, Set.of(A, B), queries(), 0, 300_000);
        byte[] before = existing.capture(0).encode();
        var second = data.players().get(B); var tracked = second.vitals().tracked(); tracked[2] = (byte) 127;
        var badVitals = new RollbackPlayerVitals(tracked, second.vitals().effects(), second.vitals().attributes());
        var bad = new RollbackRosterData.Player(second.identity(), second.randomSeed(), second.values(), badVitals, second.items(), second.context(), second.combat());
        var invalid = new RollbackRosterData(data.worldTime(), Map.of(A, data.players().get(A), B, bad));
        var rejectedQueries = queries();
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackRoster.instantiate(invalid, Set.of(A, B), rejectedQueries, 0, 300_000));
        assertTrue(rejectedQueries.outputs.isEmpty());
        var wrongTime = queries(); wrongTime.time++;
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackRoster.instantiate(data, Set.of(A, B), wrongTime, 0, 300_000));
        assertThrows(ArithmeticException.class, () -> FabricRollbackRoster.instantiate(data, Set.of(A, B), queries(), Long.MIN_VALUE, 300_000));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackRoster.instantiate(data, Set.of(A, B), queries(), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackRoster.instantiate(data, Set.of(A), queries(), 0, 300_000));
        assertThrows(UnsupportedOperationException.class, () -> existing.players().clear());
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(existing::players).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        assertArrayEquals(before, existing.capture(0).encode());
    }

    private static void assertEquivalent(RollbackRosterData expected, RollbackRosterData actual, FabricRollbackRoster roster) throws Exception {
        var normalized = new TreeMap<UUID, RollbackRosterData.Player>();
        for (var entry : actual.players().entrySet()) {
            var a = entry.getValue(); var e = expected.players().get(entry.getKey());
            assertArrayEquals(e.vitals().tracked(), a.vitals().tracked()); assertEquals(e.vitals().attributes(), a.vitals().attributes());
            try (var left = new DataInputStream(new ByteArrayInputStream(e.vitals().effects())); var right = new DataInputStream(new ByteArrayInputStream(a.vitals().effects()))) {
                assertEquals(NbtIo.read(left, new NbtSizeTracker(RollbackPlayerVitals.MAXIMUM_NBT_ALLOCATION, RollbackPlayerVitals.MAXIMUM_NBT_DEPTH)),
                        NbtIo.read(right, new NbtSizeTracker(RollbackPlayerVitals.MAXIMUM_NBT_ALLOCATION, RollbackPlayerVitals.MAXIMUM_NBT_DEPTH)));
            }
            var codec = new FabricRollbackItemCodec(roster.world().world().getRegistryManager());
            assertEquals(e.items().items().size(), a.items().items().size());
            for (int i = 0; i < e.items().items().size(); i++) {
                var first = e.items().items().get(i); var second = a.items().items().get(i);
                assertTrue(ItemStack.areEqual(codec.decode(first.data()), codec.decode(second.data()))); assertEquals(first.popTime(), second.popTime());
            }
            var items = a.items();
            var normalizedItems = new RollbackPlayerItems(e.items().items(), items.inventory(), items.enderChest(), items.selected(), items.maximumStack(),
                    items.useItem(), items.lastItem(), items.spinItem(), items.lastEquipment(), items.cooldownTick(), items.cooldowns());
            normalized.put(entry.getKey(), new RollbackRosterData.Player(a.identity(), a.randomSeed(), a.values(), e.vitals(), normalizedItems, a.context(), a.combat()));
        }
        // Compound NBT key order is not semantic; every other encoded field must match exactly.
        assertArrayEquals(expected.encode(), new RollbackRosterData(actual.worldTime(), normalized).encode());
    }
    private RollbackRosterData fixture() throws Exception {
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-roster.base64"))) {
            return RollbackRosterData.decode(Base64.getMimeDecoder().decode(input.readAllBytes()));
        }
    }
    private static FabricRollbackWorldAccessTest.Queries queries() {
        var queries = new FabricRollbackWorldAccessTest.Queries(); queries.time = 20; return queries;
    }
}
