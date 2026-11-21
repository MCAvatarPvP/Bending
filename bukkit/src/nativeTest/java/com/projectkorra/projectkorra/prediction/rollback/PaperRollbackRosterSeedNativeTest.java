package com.projectkorra.projectkorra.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard;
import java.lang.reflect.Proxy;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatEntry;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.FallLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static com.projectkorra.projectkorra.prediction.rollback.PaperRollbackWorldAccessNativeTest.onTickThread;
import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackRosterSeedNativeTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    private static final long EPOCH = 5_000_000_000L;
    private static final PaperRollbackPlayerFields.Field<Object2LongMap> KINETIC =
            new PaperRollbackPlayerFields.Field<>(LivingEntity.class, "recentKineticEnemies", Object2LongMap.class);
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void importedRosterBindsOneNativeBodyToAbilityMovementHealthAndInventory() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var a = source.player(A); var b = source.player(B);
            var sword = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD); sword.setDamageValue(7);
            ((ServerPlayer) a.ownedPlayer()).getInventory().setItem(0, sword);
            var seed = PaperRollbackRosterSeed.capture(List.of((ServerPlayer) a.ownedPlayer(), (ServerPlayer) b.ownedPlayer()), EPOCH);
            var target = new Scene(); var nativePlayers = target.imported(seed);
            var logical = PaperRollbackWorldQueriesNativeTest.world();
            var items = new RollbackNativeItems<>(new PaperRollbackItems(target.world.world().registryAccess()));
            var bindings = Map.of(A, viewServices(A), B, viewServices(B));
            var last = bindings.get(B);
            var badScoreboard = new RollbackRosterViews.Services(last.profile(), last.hidden(), new Scoreboard() { }, last.body(), last.player());
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackRosterViews.bind(logical, target.world, Set.of(A, B), nativePlayers, items,
                    Map.of(A, bindings.get(A), B, badScoreboard)));
            assertTrue(logical.getPlayers().isEmpty(), "A late view failure must not publish the first player");
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackRosterViews.bind(logical, target.world, Set.of(A, B), nativePlayers, items,
                    Map.of(A, bindings.get(A), B, bindings.get(A))), "Permission policy cannot be substituted between participants");
            assertTrue(logical.getPlayers().isEmpty());
            var views = PaperRollbackRosterViews.bind(logical, target.world, Set.of(A, B), nativePlayers, items, bindings);
            var player = views.players().get(A); var victim = views.players().get(B);
            assertTrue(player.hasPermission("BENDING.FEATURE.ALLOWED")); assertFalse(player.hasPermission("bending.feature.denied"));
            assertFalse(player.canSee(victim));
            assertThrows(IllegalStateException.class, () -> player.hasPermission("unknown.feature"));
            assertEquals(List.of(player, victim), logical.getPlayers());
            assertSame(nativePlayers.get(A), player.body().kinematicsSource());
            assertSame(nativePlayers.get(A), player.state().inventory().nativeOwner());
            assertSame(nativePlayers.get(A), player.state().controlSource());
            assertSame(nativePlayers.get(A), player.state().living().combatSource());
            assertEquals(List.of("player/" + A, "player/" + B), views.playerBindings().stream().map(RollbackGraphCodec.Binding::id).toList());
            var snapshot = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000).capture(List.of(views), List.of());
            player.state().hidden(Set.of()); assertTrue(player.canSee(victim));
            victim.damage(4, player); player.setVelocity(new com.projectkorra.projectkorra.platform.mc.util.Vector(.2, .3, .4));
            player.getInventory().getItem(0).setDurability((short) 12);
            assertEquals(16, nativePlayers.get(B).ownedPlayer().getHealth());
            assertEquals(.3, nativePlayers.get(A).ownedPlayer().getDeltaMovement().y);
            assertEquals(12, ((ServerPlayer) nativePlayers.get(A).ownedPlayer()).getInventory().getItem(0).getDamageValue());
            assertEquals(20, b.ownedPlayer().getHealth()); assertEquals(7, sword.getDamageValue());
            snapshot.restore();
            assertFalse(player.canSee(victim)); assertFalse(player.hasPermission("bending.feature.denied"));
            assertEquals(20, victim.getHealth()); assertEquals(7, player.getInventory().getItem(0).getDurability());
            assertEquals(0, player.getVelocity().lengthSquared());
            abilityQueries(logical, player, victim);
            assertThrows(IllegalStateException.class, () -> PaperRollbackRosterViews.bind(logical, target.world, Set.of(A, B), nativePlayers, items, bindings));
            var rejected = PaperRollbackWorldQueriesNativeTest.world();
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackRosterViews.bind(rejected, target.world, Set.of(A, B), Map.of(A, a, B, nativePlayers.get(B)), items, bindings));
            assertTrue(rejected.getPlayers().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> views.players().clear());
            return null;
        });
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

    private static RollbackRosterViews.Services viewServices(UUID id) {
        var body = (RollbackEntityBody.Rules) Proxy.newProxyInstance(RollbackEntityBody.Rules.class.getClassLoader(), new Class<?>[]{RollbackEntityBody.Rules.class},
                (proxy, method, args) -> { throw new AssertionError(method); });
        var player = (RollbackPlayerState.Rules) Proxy.newProxyInstance(RollbackPlayerState.Rules.class.getClassLoader(), new Class<?>[]{RollbackPlayerState.Rules.class},
                (proxy, method, args) -> { throw new AssertionError(method); });
        var profile = new RollbackPlayerState.Profile("player" + id.getLeastSignificantBits(), "SURVIVAL", "RIGHT", true, false, true, 50);
        var access = new RollbackPlayerAccess.Entry(id, profile, id.equals(A) ? Set.of(B) : Set.of(), Map.of("bending.feature.allowed", true, "bending.feature.denied", false));
        return new RollbackRosterViews.Services(access, new Scoreboard(), body, player);
    }

    @Test void completePortableRosterMatchesNativeImportWithProfilesModesItemsAndCombat() throws Exception {
        onTickThread(() -> {
            var source = new Scene();
            var properties = com.google.common.collect.ImmutableListMultimap.<String, com.mojang.authlib.properties.Property>builder()
                    .put("textures", new com.mojang.authlib.properties.Property("textures", "skin", "signature"))
                    .put("textures", new com.mojang.authlib.properties.Property("textures", "extra")).build();
            var info = new ClientInformation("en_us", 8, net.minecraft.world.entity.player.ChatVisiblity.SYSTEM, true, 127,
                    net.minecraft.world.entity.HumanoidArm.LEFT, false, true, net.minecraft.server.level.ParticleStatus.DECREASED);
            var first = PaperRollbackNativePlayerState.serverPlayer(source.world, new GameProfile(A, "player7001", new com.mojang.authlib.properties.PropertyMap(properties)),
                    info, GameType.ADVENTURE, A.getLeastSignificantBits(), 300_000);
            var second = source.player(B); var a = (ServerPlayer) first.ownedPlayer(); var b = (ServerPlayer) second.ownedPlayer();
            a.valid = true; a.setPos(.5, 1, .5); a.setOnGround(true); a.tickCount = 100; a.setId(701); b.setId(702);
            for (var player : List.of(a, b)) { player.setYRot(0); player.setXRot(0); player.yHeadRot = 0; player.yHeadRotO = 0; player.yBodyRot = 0; player.yBodyRotO = 0; }
            var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD); item.setDamageValue(7);
            a.getInventory().setItem(0, item); a.getEnderChestInventory().setItem(2, item);
            assertTrue(second.damage(source.world.world().damageSources().playerAttack(a), 4));
            var seed = PaperRollbackRosterSeed.capture(List.of(b, a), EPOCH);
            var services = Map.of(A, services(A), B, services(B));
            var portable = RollbackRosterData.decode(seed.portable(services).encode());
            assertEquals(GameType.ADVENTURE.name(), portable.players().get(A).identity().mode().name());
            assertEquals(2, portable.players().get(A).identity().properties().size());
            var target = new Scene(); var imported = seed.instantiate(target.world, 0, services);
            var copiedA = (ServerPlayer) imported.get(A).ownedPlayer(); var copiedB = (ServerPlayer) imported.get(B).ownedPlayer();
            assertEquals(a.getGameProfile(), copiedA.getGameProfile()); assertEquals(info, copiedA.clientInformation());
            assertSame(copiedA.getInventory().getItem(0), copiedA.getEnderChestInventory().getItem(2));
            assertSame(copiedA, copiedB.getKillCredit());
            assertArrayEquals(portable.encode(), PaperRollbackRosterSeed.capture(List.of(copiedA, copiedB), 0).portable(services).encode());
            assertEquals(new java.util.Random(A.getLeastSignificantBits()).nextLong(), copiedA.getRandom().nextLong());
            assertThrows(IllegalArgumentException.class, () -> seed.portable(Map.of(A, services(A))));
            assertTrue(target.combat.events.isEmpty()); assertTrue(target.combat.outputs.isEmpty());
            try (var fixture = getClass().getResourceAsStream("/rollback/player-roster.base64")) {
                byte[] bytes = portable.encode(); String message = "Current Paper player-roster fixture: " + Base64.getEncoder().encodeToString(bytes);
                assertNotNull(fixture, message); assertArrayEquals(bytes, Base64.getMimeDecoder().decode(fixture.readAllBytes()), message);
            }
            return null;
        });
    }

    @Test void portableCombatImportsAnEntireCohortWithSourceAliasesAndRejectsALateInvalidPlayerAtomically() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var first = source.player(A); var second = source.player(B);
            var a = (ServerPlayer) first.ownedPlayer(); var b = (ServerPlayer) second.ownedPlayer();
            assertTrue(second.damage(source.world.world().damageSources().playerAttack(a).knownCause(DamageCause.MAGIC).critical(), 4));
            assertTrue(first.damage(source.world.world().damageSources().playerAttack(b), 2));
            a.setLastHurtMob(b); b.setLastHurtMob(a); b.currentExplosionCause = a;
            var hits = new Object2LongOpenHashMap<Entity>(); hits.put(b, source.combat.time - 2); KINETIC.set(a, hits);
            var point = new Vec3(1.2, 3.4, 5.6);
            var historical = new DamageSource(source.world.world().damageSources().generic().typeHolder(), point).eventEntityDamager(b).knownCause(DamageCause.CUSTOM).critical();
            var sameValues = new DamageSource(source.world.world().damageSources().generic().typeHolder(), point).eventEntityDamager(b).knownCause(DamageCause.CUSTOM).critical();
            b.combatTracker.entries.add(new CombatEntry(historical, 3, null, 0));
            b.combatTracker.entries.add(new CombatEntry(historical, 5, FallLocation.GENERIC, 7));
            b.combatTracker.entries.add(new CombatEntry(sameValues, 5, FallLocation.GENERIC, 7));
            var aData = RollbackPlayerCombatData.decode(PaperRollbackCombatSeed.capture(first).encode());
            var bData = RollbackPlayerCombatData.decode(PaperRollbackPlayerSeed.capture(b, EPOCH).combat().encode());
            var target = new Scene(); var targetA = target.player(A); var targetB = target.player(B);
            var states = Map.of(A, targetA, B, targetB); var data = Map.of(A, aData, B, bData);
            PaperRollbackCombatSeed.apply(states, data);
            var copiedA = (ServerPlayer) targetA.ownedPlayer(); var copiedB = (ServerPlayer) targetB.ownedPlayer();
            assertArrayEquals(aData.encode(), PaperRollbackCombatSeed.capture(targetA).encode());
            assertArrayEquals(bData.encode(), PaperRollbackCombatSeed.capture(targetB).encode());
            assertSame(copiedA, copiedB.getKillCredit()); assertSame(copiedB, copiedA.getKillCredit());
            assertSame(copiedA, copiedB.currentExplosionCause); assertTrue(copiedA.wasRecentlyStabbed(copiedB, 5));
            var entries = copiedB.combatTracker.entries;
            assertSame(entries.get(0).source(), copiedB.getLastDamageSource());
            assertSame(entries.get(1).source(), entries.get(2).source()); assertNotSame(entries.get(1).source(), entries.get(3).source());
            assertSame(copiedB, entries.get(1).source().eventEntityDamager()); assertEquals(point, entries.get(1).source().getSourcePosition());
            copiedA.setLastHurtMob(null); byte[] beforeA = PaperRollbackCombatSeed.capture(targetA).encode(), beforeB = PaperRollbackCombatSeed.capture(targetB).encode();
            var invalidSources = new ArrayList<>(bData.sources()); var original = invalidSources.getFirst();
            invalidSources.set(0, new RollbackPlayerCombatData.Source("test:missing", original.direct(), original.causing(), original.eventDamager(), original.position(), original.knownCause(), original.critical()));
            var invalid = new RollbackPlayerCombatData(B, bData.lastHurtByPlayer(), bData.lastHurtByMob(), bData.lastHurtMob(), bData.explosionCause(), bData.hasKinetic(), bData.kinetic(), bData.tracker(), invalidSources, bData.lastDamage(), bData.entries());
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackCombatSeed.apply(states, Map.of(A, aData, B, invalid)));
            assertArrayEquals(beforeA, PaperRollbackCombatSeed.capture(targetA).encode()); assertArrayEquals(beforeB, PaperRollbackCombatSeed.capture(targetB).encode());
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackCombatSeed.apply(Map.of(B, targetB), Map.of(B, bData)));
            assertTrue(target.combat.events.isEmpty()); assertTrue(target.combat.outputs.isEmpty());
            assertAll(() -> fixture("a", aData), () -> fixture("b", bData));
            return null;
        });
    }

    private void fixture(String name, RollbackPlayerCombatData data) throws Exception {
        try (var input = getClass().getResourceAsStream("/rollback/player-combat-" + name + ".base64")) {
            byte[] bytes = data.encode(); String message = "Current Paper player-combat-" + name + " fixture: " + Base64.getEncoder().encodeToString(bytes);
            assertNotNull(input, message); assertArrayEquals(bytes, Base64.getMimeDecoder().decode(input.readAllBytes()), message);
        }
    }

    @Test void nativeDamageHistoryRebindsCyclicAttackersAndRewindsWithoutTouchingTheSource() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var first = source.player(A); var second = source.player(B);
            var a = (ServerPlayer) first.ownedPlayer(); var b = (ServerPlayer) second.ownedPlayer();
            var fromA = source.world.world().damageSources().playerAttack(a).knownCause(DamageCause.MAGIC).critical();
            assertTrue(second.damage(fromA, 4));
            assertTrue(first.damage(source.world.world().damageSources().playerAttack(b), 2));
            a.setLastHurtMob(b); b.setLastHurtMob(a); b.currentExplosionCause = a;
            assertEquals(16, b.getHealth()); assertSame(a, b.getKillCredit());
            assertEquals(1, b.combatTracker.entries.size());
            var kinetic = new Object2LongOpenHashMap<Entity>(); kinetic.put(b, source.combat.time - 2); KINETIC.set(a, kinetic);
            var originalConnection = b.connection;
            var roster = PaperRollbackRosterSeed.capture(List.of(b, a), EPOCH);
            assertEquals(List.of(A, B), new ArrayList<>(roster.participants()));
            assertSame(originalConnection, b.connection); assertSame(a, b.getLastDamageSource().getEntity());
            // Every relationship/value below must already be detached.
            a.setPos(8, 2, 8); a.setLastHurtMob(null); b.lastHurtByPlayer = null; b.lastHurtByMob = null;
            b.currentExplosionCause = null; b.combatTracker.entries.clear(); b.setHealth(7); kinetic.clear();
            var target = new Scene(); var imported = target.imported(roster);
            var copiedA = (ServerPlayer) imported.get(A).ownedPlayer(); var copiedB = (ServerPlayer) imported.get(B).ownedPlayer();
            assertEquals(16, copiedB.getHealth()); assertEquals(18, copiedA.getHealth());
            assertSame(copiedA, copiedB.getKillCredit()); assertSame(copiedB, copiedA.getKillCredit());
            assertSame(copiedA, copiedB.getLastHurtMob()); assertSame(copiedB, copiedA.getLastHurtMob());
            assertSame(copiedA, copiedB.currentExplosionCause);
            assertTrue(copiedA.wasRecentlyStabbed(copiedB, 5));
            // Native Entity.equals uses its numeric id; inspect the stored key's
            // identity to prove the live entity was replaced by the private one.
            assertSame(copiedB, KINETIC.get(copiedA).keySet().iterator().next());
            assertNotSame(kinetic, KINETIC.get(copiedA));
            var history = copiedB.combatTracker.entries.getFirst();
            assertEquals(4, history.damage()); assertSame(copiedB, copiedB.combatTracker.mob);
            assertSame(copiedA, history.source().getEntity()); assertSame(copiedA, history.source().getDirectEntity());
            assertSame(history.source(), copiedB.getLastDamageSource());
            assertNotSame(fromA, history.source()); assertTrue(history.source().isCritical());
            assertEquals(DamageCause.MAGIC, history.source().knownCause());
            assertNull(history.source().sourcePositionRaw()); assertEquals(copiedA.position(), history.source().getSourcePosition());
            assertTrue(target.combat.outputs.isEmpty()); assertTrue(target.combat.events.isEmpty(), "Import must not refire damage events");

            // The private UUID lookup also resolves uncached native EntityReferences.
            copiedB.lastHurtByPlayer = EntityReference.of(A);
            assertSame(copiedA, copiedB.getKillCredit());
            assertSame(copiedB, target.world.world().getEntityInAnyDimension(B));
            assertNull(target.world.world().getPlayerInAnyDimension(new UUID(0, 999)));

            var graph = new RollbackStateGraph(value -> false, field -> true, 500_000);
            var checkpoint = graph.capture(List.copyOf(imported.values()), List.of());
            assertTrue(imported.get(B).damage(target.world.world().damageSources().playerAttack(copiedA), 8));
            assertEquals(12, copiedB.getHealth(), "Imported immunity/last damage should apply only the stronger-hit difference");
            assertEquals(2, copiedB.combatTracker.entries.size());
            var outputs = List.copyOf(target.combat.outputs);
            checkpoint.restore();
            assertEquals(16, copiedB.getHealth()); assertEquals(1, copiedB.combatTracker.entries.size());
            assertSame(copiedA, copiedB.getKillCredit());
            assertTrue(imported.get(B).damage(target.world.world().damageSources().playerAttack(copiedA), 8));
            assertEquals(12, copiedB.getHealth()); assertEquals(outputs, target.combat.outputs);
            assertEquals(7, b.getHealth()); assertTrue(b.combatTracker.entries.isEmpty()); assertSame(originalConnection, b.connection);
            assertNull(org.bukkit.Bukkit.getServer());
            return null;
        });
    }

    @Test void historicalPositionsEventDamagersAndCombatTimeoutsSurviveRepeatedImports() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var first = source.player(A); var second = source.player(B);
            var a = (ServerPlayer) first.ownedPlayer(); var b = (ServerPlayer) second.ownedPlayer();
            var point = new Vec3(1.2, 3.4, 5.6);
            var history = new DamageSource(source.world.world().damageSources().generic().typeHolder(), point)
                    .eventEntityDamager(a).knownCause(DamageCause.CUSTOM).critical();
            b.combatTracker.recordDamage(history, 3);
            b.combatTracker.entries.add(new CombatEntry(history, 5, FallLocation.GENERIC, 7));
            var roster = PaperRollbackRosterSeed.capture(List.of(a, b), EPOCH);
            var target = new Scene(); var imported = target.imported(roster);
            var copiedA = (ServerPlayer) imported.get(A).ownedPlayer(); var copiedB = (ServerPlayer) imported.get(B).ownedPlayer();
            var damage = copiedB.combatTracker.entries.getFirst().source();
            assertSame(damage, copiedB.combatTracker.entries.getLast().source());
            assertEquals(point, damage.sourcePositionRaw()); assertEquals(point, damage.getSourcePosition());
            assertNull(damage.getEntity()); assertNull(damage.getDirectEntity()); assertSame(copiedA, damage.eventEntityDamager());
            assertEquals(DamageCause.CUSTOM, damage.knownCause()); assertTrue(damage.isCritical());
            assertEquals(FallLocation.GENERIC, copiedB.combatTracker.entries.getLast().fallLocation());
            assertEquals(7, copiedB.combatTracker.entries.getLast().fallDistance());
            copiedB.tickCount += 99; copiedB.combatTracker.recheckStatus(); assertEquals(2, copiedB.combatTracker.entries.size());
            copiedB.tickCount += 2; copiedB.combatTracker.recheckStatus(); assertTrue(copiedB.combatTracker.entries.isEmpty());
            assertEquals(2, b.combatTracker.entries.size());
            var repeated = new Scene().imported(roster);
            var repeatedB = (ServerPlayer) repeated.get(B).ownedPlayer();
            assertEquals(2, repeatedB.combatTracker.entries.size());
            assertNotSame(damage, repeatedB.combatTracker.entries.getFirst().source());
            assertSame(repeated.get(A).ownedPlayer(), repeatedB.combatTracker.entries.getFirst().source().eventEntityDamager());
            return null;
        });
    }

    @Test void missingParticipantsAndMismatchedServicesFailBeforeRosterConstruction() throws Exception {
        onTickThread(() -> {
            var source = new Scene(); var first = source.player(A); var second = source.player(B);
            var a = (ServerPlayer) first.ownedPlayer(); var b = (ServerPlayer) second.ownedPlayer();
            assertTrue(second.damage(source.world.world().damageSources().playerAttack(a), 4));
            var incomplete = PaperRollbackRosterSeed.capture(List.of(b), EPOCH);
            var target = new Scene();
            assertThrows(IllegalArgumentException.class, () -> target.imported(incomplete));
            assertNull(target.world.world().getPlayerByUUID(B));
            var complete = PaperRollbackRosterSeed.capture(List.of(b, a), EPOCH);
            assertThrows(IllegalArgumentException.class, () -> complete.instantiate(target.world, EPOCH, Map.of(A, services(A))));
            assertNull(target.world.world().getPlayerByUUID(A));
            var imported = target.imported(complete); assertEquals(Set.of(A, B), imported.keySet());
            assertThrows(IllegalArgumentException.class, () -> target.imported(complete));
            assertEquals(2, imported.size());
            var standalone = PaperRollbackPlayerSeed.capture(b, EPOCH);
            assertThrows(IllegalArgumentException.class, () -> standalone.instantiate(new Scene().world, EPOCH, 1, 200_000,
                    PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty()));
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackRosterSeed.capture(List.of(a, a), EPOCH));
            var foreign = (ServerPlayer) new Scene().player(B).ownedPlayer();
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackRosterSeed.capture(List.of(a, foreign), EPOCH));
            b.currentExplosionCause = foreign;
            assertThrows(IllegalArgumentException.class, () -> PaperRollbackPlayerSeed.capture(b, EPOCH));
            return null;
        });
    }

    private static PaperRollbackRosterSeed.Services services(UUID id) {
        return new PaperRollbackRosterSeed.Services(id.getLeastSignificantBits(), 300_000,
                PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty());
    }
    private static final class Scene {
        final PaperRollbackDamageNativeTest.Combat combat = new PaperRollbackDamageNativeTest.Combat();
        final PaperRollbackWorldAccessNativeTest.Queries queries = new PaperRollbackWorldAccessNativeTest.Queries(new RollbackBlockStore.Bounds(-5, -4, -5, 12, 12, 16));
        final PaperRollbackWorldAccess world = new PaperRollbackWorldAccess(queries, combat, 4);
        Scene() { for (int x = -3; x < 8; x++) for (int z = -3; z < 10; z++) queries.block(x, 0, z, Material.STONE, "minecraft:stone"); }
        PaperRollbackNativePlayerState player(UUID id) {
            var state = PaperRollbackNativePlayerState.serverPlayer(world, new GameProfile(id, "player" + id.getLeastSignificantBits()),
                    ClientInformation.createDefault(), GameType.SURVIVAL, id.getLeastSignificantBits(), 300_000);
            var player = state.ownedPlayer(); player.valid = true; player.setPos(.5, 1, id.equals(A) ? .5 : 2);
            player.setOnGround(true); player.tickCount = 100; return state;
        }
        Map<UUID, PaperRollbackNativePlayerState> imported(PaperRollbackRosterSeed roster) {
            var services = new LinkedHashMap<UUID, PaperRollbackRosterSeed.Services>();
            roster.participants().forEach(id -> services.put(id, services(id)));
            return roster.instantiate(world, EPOCH, services);
        }
    }
}
