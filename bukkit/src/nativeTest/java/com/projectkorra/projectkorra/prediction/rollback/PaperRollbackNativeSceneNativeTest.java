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
    @Test void interruptedPartialRosterCanBeDiscardedBeforeACompleteStagedImport() throws Exception {
        onTickThread(() -> {
            var live = source();
            var roster = PaperRollbackRosterSeed.capture(live, 0);
            var settings = seed();
            var round = new RollbackRound(new UUID(7, 11), Map.of(live.getFirst().getUUID(), new UUID(0, 1),
                    live.getLast().getUUID(), new UUID(0, 2)));
            var registry = PaperRollbackEnvironmentNativeTest.registries();
            var services = new TreeMap<UUID, PaperRollbackRosterSeed.Services>();
            roster.participants().forEach(id -> services.put(id, new PaperRollbackRosterSeed.Services(id.getLeastSignificantBits(),
                    300_000, PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty())));
            java.util.function.Supplier<java.util.function.Supplier<PaperRollbackNativeScene>> prepare = () -> {
                var items = new RollbackNativeItems<>(new PaperRollbackItems(registry));
                var bindings = new PaperRollbackNativeScene.Bindings(new PaperRollbackBlockRules(items), items,
                        unused(RollbackWorld.Actions.class), registry, new Events());
                return PaperRollbackNativeScene.prepare(settings, roster, round, registry, FeatureFlags.DEFAULT_FLAGS,
                        bindings, services, 0, 100, 128, 9);
            };
            var abandoned = prepare.get();
            assertNull(abandoned.get());
            var complete = prepare.get();
            assertNull(complete.get()); assertNull(complete.get());
            var scene = complete.get();
            assertEquals(roster.participants(), scene.roster().keySet());
            for (var source : live) {
                var copy = (ServerPlayer) scene.roster().get(source.getUUID()).ownedPlayer();
                assertNotSame(copy, source); assertNotSame(copy.level(), source.level());
                assertEquals(source.position(), copy.position());
                assertEquals(source.getHealth(), copy.getHealth());
            }
            return null;
        });
    }

    @Test void nativeRopeDartArrowUsesTheTargetScoreboardForFriendlyFire() throws Exception {
        onTickThread(() -> {
            var source = source();
            var attackerId = source.getFirst().getUUID();
            var defenderId = source.getLast().getUUID();
            var captured = PaperRollbackRosterSeed.capture(source, 0);
            var seed = seed();
            var teams = new RollbackCombatTeams(Map.of(defenderId, Set.of(attackerId, defenderId)));
            var settings = RollbackWorldSettings.decode(seed.settings()
                    .withInteractions(RollbackDuelInteractions.STANDARD.withTeams(teams)).encode());
            var arena = new RollbackWorldSeed(seed.world(), seed.name(), seed.minimumY(), seed.maximumY(),
                    seed.dayTime(), seed.storm(), settings, seed.border(), seed.environment(), seed.terrain(), seed.light(), seed.scheduledTicks());
            var retired = new ArrayList<org.bukkit.event.entity.EntityRemoveEvent>();
            var scene = scene(arena, captured, captured.participants(), new Events() {
                @Override public void gameEvent(Object event, Object pos, Object context) { }
                @Override public void event(org.bukkit.event.Event event) {
                    if (event instanceof org.bukkit.event.entity.EntityRemoveEvent removal) retired.add(removal);
                    else throw new AssertionError(event);
                }
            });
            var shooter = (ServerPlayer) scene.roster().get(attackerId).ownedPlayer();
            var defender = (ServerPlayer) scene.roster().get(defenderId).ownedPlayer();
            var itemOwner = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(new UUID(82, 32), 16, itemOwner);
            actors.bind(scene);
            var views = bindAbilityViews(scene, actors, itemOwner);
            var arrow = actors.spawn(scene.logical(), new RollbackEntityBody.Pose(0, 2, 0, 0, 0),
                    com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
            scene.logical().entities().add(arrow);
            var nativeArrow = (PaperRollbackArrow) actors.nativeEntity(arrow);
            shooter.setPos(.5, 1, -2); defender.setPos(.5, 1, 2);
            nativeArrow.setOwner(shooter);
            nativeArrow.setPos(.5, 2, -.5);
            nativeArrow.setDeltaMovement(0, 0, 4);
            assertFalse(shooter.canHarmPlayer(defender));
            assertTrue(defender.canHarmPlayer(shooter));
            assertEquals(teams, PaperRollbackScoreboards.captureCombatTeams(List.of(shooter, defender)));
            var scan = net.minecraft.world.entity.projectile.arrow.AbstractArrow.class
                    .getDeclaredMethod("findHitEntities", net.minecraft.world.phys.Vec3.class, net.minecraft.world.phys.Vec3.class);
            scan.setAccessible(true);
            assertTrue(((Collection<?>) scan.invoke(nativeArrow, nativeArrow.position(), nativeArrow.position().add(nativeArrow.getDeltaMovement()))).isEmpty());
            nativeArrow.setOwner(defender);
            nativeArrow.setPos(.5, 2, .5);
            nativeArrow.setDeltaMovement(0, 0, -4);
            var hits = (Collection<?>) scan.invoke(nativeArrow, nativeArrow.position(), nativeArrow.position().add(nativeArrow.getDeltaMovement()));
            assertEquals(List.of(shooter), hits.stream().map(hit -> ((net.minecraft.world.phys.EntityHitResult) hit).getEntity()).toList());
            views.players().get(defenderId).state().hidden(Set.of(attackerId));
            assertFalse(defender.getBukkitEntity().canSee(shooter.getBukkitEntity()));
            assertTrue(((Collection<?>) scan.invoke(nativeArrow, nativeArrow.position(), nativeArrow.position().add(nativeArrow.getDeltaMovement()))).isEmpty());
            views.players().get(defenderId).state().hidden(Set.of());
            var caster = views.players().get(defenderId);
            var target = views.players().get(attackerId);
            arrow.setShooter(caster);
            arrow.setDamage(0);
            me.literka.abilities.RopeDart dart;
            var previousConfig = com.projectkorra.projectkorra.configuration.ConfigManager.defaultConfig;
            try {
                var settingsForAbility = new RollbackConfiguration.Settings("fixture", Map.of(), Map.of(), true);
                com.projectkorra.projectkorra.configuration.ConfigManager.defaultConfig =
                        new com.projectkorra.projectkorra.configuration.Config(settingsForAbility) { };
                dart = new org.objenesis.ObjenesisStd().newInstance(me.literka.abilities.RopeDart.class);
            } finally {
                com.projectkorra.projectkorra.configuration.ConfigManager.defaultConfig = previousConfig;
            }
            var abilityType = com.projectkorra.projectkorra.ability.CoreAbility.class;
            var casterField = abilityType.getDeclaredField("player");
            casterField.setAccessible(true);
            casterField.set(dart, caster);
            var shot = dart.getClass().getDeclaredField("arrow");
            shot.setAccessible(true);
            shot.set(dart, arrow);
            var started = abilityType.getDeclaredField("started");
            started.setAccessible(true);
            started.set(dart, true);
            var constructor = com.projectkorra.projectkorra.ability.CoreAbility.RollbackRegistry.class
                    .getDeclaredConstructor(List.class, Map.class, int.class, int.class, long.class);
            constructor.setAccessible(true);
            var abilities = constructor.newInstance(List.of(dart), Map.of(dart.getClass(), Map.of()), 1, 64, 0);
            var listener = new me.literka.ChiListener();
            var impacts = new ArrayList<com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent>();
            var bus = (com.projectkorra.projectkorra.platform.PKEventBus) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.PKEventBus.class}, (proxy, method, args) -> {
                        assertEquals("call", method.getName());
                        var hit = (com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent) args[0];
                        listener.onProjectileHit(hit);
                        impacts.add(hit);
                        return null;
                    });
            var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        assertEquals("events", method.getName());
                        return bus;
                    });
            var registries = new RollbackPrivateRegistries();
            // The scene owns these exact native-backed views; the separate ability graph retains their identities.
            var ownedViews = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            ownedViews.addAll(List.of(caster, arrow, target));
            var domain = RollbackDomain.create(new RollbackStateGraph(ownedViews::contains, field -> true, 2048),
                    List.of(), List.of(registries), platform, abilities::install);
            var fluidField = net.minecraft.world.entity.Entity.class.getDeclaredField("fluidHeight");
            fluidField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var fluids = (it.unimi.dsi.fastutil.objects.Object2DoubleMap<net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid>>) fluidField.get(nativeArrow);
            float initialHealth = shooter.getHealth();
            domain.call(() -> {
                try (var clock = RollbackClock.at(1000, 1, 50_000_000)) {
                    var actor = (PaperRollbackActor) actors.registry().get(arrow.getUniqueId());
                    nativeArrow.setPos(.5, 2, 4.5);
                    actor.tick();
                    assertTrue(impacts.isEmpty());
                    assertSame(defender.getBukkitEntity(), nativeArrow.projectileSource);
                    actor.captureRollbackState();
                    var water = net.minecraft.tags.FluidTags.WATER;
                    var lava = net.minecraft.tags.FluidTags.LAVA;
                    fluids.clear();
                    fluids.defaultReturnValue(-1);
                    fluids.put(water, .25);
                    var entries = fluids.object2DoubleEntrySet();
                    var keys = fluids.keySet();
                    var values = fluids.values();
                    assertEquals(1, entries.size());
                    var airborne = actor.captureRollbackState();
                    fluids.clear();
                    fluids.put(lava, .75);
                    fluids.defaultReturnValue(-2);
                    var altered = actor.captureRollbackState();
                    var position = nativeArrow.position();
                    var velocity = nativeArrow.getDeltaMovement();
                    int age = nativeArrow.tickCount;
                    arrow.setShooter(target);
                    assertSame(shooter.getBukkitEntity(), nativeArrow.projectileSource);
                    nativeArrow.setDeltaMovement(1, 0, 0);
                    actor.tick();
                    actor.restoreRollbackState(altered);
                    assertEquals(.75, fluids.getDouble(lava));
                    assertEquals(-2, fluids.getDouble(water));
                    actor.restoreRollbackState(airborne);
                    assertSame(keys, fluids.keySet());
                    assertSame(values, fluids.values());
                    assertSame(entries, fluids.object2DoubleEntrySet());
                    assertTrue(keys.contains(water));
                    assertTrue(values.contains(.25));
                    assertEquals(.25, entries.iterator().next().getDoubleValue());
                    assertEquals(-1, fluids.getDouble(lava));
                    assertSame(defender, nativeArrow.getOwner());
                    assertSame(defender.getBukkitEntity(), nativeArrow.projectileSource);
                    assertEquals(position, nativeArrow.position());
                    assertEquals(velocity, nativeArrow.getDeltaMovement());
                    assertEquals(age, nativeArrow.tickCount);
                    actor.tick();
                }
                return null;
            });
            assertEquals(1, impacts.size());
            assertSame(arrow, impacts.getFirst().getEntity());
            assertSame(target, impacts.getFirst().getHitEntity());
            assertTrue(impacts.getFirst().isCancelled());
            assertEquals(initialHealth, shooter.getHealth());
            assertTrue(nativeArrow.isRemoved());
            assertNull(shot.get(dart));
            assertEquals(target.getLocation().getZ(), dart.getLocation().getZ());
            assertEquals(1, retired.size());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nativeStickyBombAttachesOncePerBranchAndNeverCallsLiveCraftEvents(boolean wall) throws Exception {
        onTickThread(() -> {
            var roster = PaperRollbackRosterSeed.capture(source(), 0);
            var removals = new ArrayList<org.bukkit.event.entity.EntityRemoveEvent>();
            var sounds = new ArrayList<String>();
            var actions = (RollbackWorld.Actions)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{RollbackWorld.Actions.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("sound")) throw new AssertionError(method);
                        sounds.add((String)args[3]); return null;
                    });
            var scene = scene(seed(), roster, roster.participants(), new Events() {
                @Override public void gameEvent(Object event, Object pos, Object source) { }
                @Override public void event(org.bukkit.event.Event event) {
                    removals.add(assertInstanceOf(org.bukkit.event.entity.EntityRemoveEvent.class, event));
                }
            }, actions);
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(UUID.randomUUID(), 16, items);
            actors.bind(scene);
            var participants = bindAbilityViews(scene, actors, items).players().values().stream().toList();
            var caster = participants.getFirst(); var target = participants.getLast();
            actors.nativeEntity(caster).setPos(.5, 4, -2);
            actors.nativeEntity(target).setPos(.5, 4, wall ? 8 : 2);
            if (wall) scene.logical().getBlockAt(0, 5, 2).setType(Material.STONE, false);
            var shot = actors.spawn(scene.logical(), new RollbackEntityBody.Pose(.5, 5.25, .5, 0, 0),
                    com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet.class);
            scene.logical().entities().add(shot);
            shot.setShooter(caster);
            var bullet = actors.nativeEntity(shot);
            bullet.setNoGravity(true);
            bullet.setDeltaMovement(0, 0, 3);
            StickyBombImpactScenario.verify(caster, target, shot, actors.registry(), new RollbackStepOutput<>(), actors.registry()::tick, wall);
            assertEquals(List.of("BLOCK_HONEY_BLOCK_STEP", "BLOCK_HONEY_BLOCK_STEP"), sounds);
            assertEquals(List.of(org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN, org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN),
                    removals.stream().map(org.bukkit.event.entity.EntityRemoveEvent::getCause).toList());
            return null;
        });
    }

    @Test void damageHistoryKeepsTheExactRetiredSnowballStateAcrossRewind() throws Exception {
        onTickThread(() -> {
            var source = source();
            var roster = PaperRollbackRosterSeed.capture(source, 0);
            var scene = scene(seed(), roster, roster.participants());
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(new UUID(19, 42), 16, items);
            actors.bind(scene);
            var defender = scene.roster().get(source.getFirst().getUUID());
            var defenderBody = (ServerPlayer) defender.ownedPlayer();
            var caster = scene.roster().get(source.getLast().getUUID()).ownedPlayer();
            var ball = actors.spawn(scene.logical(), new RollbackEntityBody.Pose(0, 4, 0, 0, 0),
                    com.projectkorra.projectkorra.platform.mc.entity.Snowball.class);
            scene.logical().entities().add(ball);
            var nativeBall = (PaperRollbackSnowball) actors.nativeEntity(ball);
            nativeBall.setOwner(caster);
            var damage = scene.world().world().damageSources().thrown(nativeBall, caster);
            var lastDamage = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("lastDamageSource");
            lastDamage.setAccessible(true);
            lastDamage.set(defenderBody, damage);
            var actor = (PaperRollbackActor) actors.registry().get(ball.getUniqueId());
            var flying = actor.captureRollbackState();
            defender.captureRollbackState();
            nativeBall.tickCount = 17;
            PaperRollbackPrivateAccess.retirePrivateActor(nativeBall);
            assertTrue(actors.registry().active().isEmpty());
            var impact = defender.captureRollbackState();
            var wrapperOnly = new RollbackStateGraph(value -> scene.world().checkpointExternal(defenderBody, value)
                    || PaperRollbackMetadata.frozen(value), field -> true, 50_000, scene.world()::checkpointReferent)
                    .capture(List.of(nativeBall.getBukkitEntity()), List.of());
            flying.restore();
            nativeBall.tickCount = 2;
            lastDamage.set(defenderBody, null);
            assertFalse(nativeBall.isRemoved());
            defender.restoreRollbackState(impact);
            assertSame(damage, lastDamage.get(defenderBody));
            assertSame(nativeBall, damage.getDirectEntity());
            assertTrue(nativeBall.isRemoved());
            assertEquals(17, nativeBall.tickCount);
            assertNull(actors.registry().get(nativeBall.getUUID()));
            flying.restore();
            assertFalse(nativeBall.isRemoved());
            wrapperOnly.restore();
            assertTrue(nativeBall.isRemoved());
            assertEquals(17, nativeBall.tickCount);

            var originals = new TreeMap<UUID, RollbackRosterData.Player>();
            var livePlayers = new TreeMap<UUID, ServerPlayer>();
            for (var player : source) {
                originals.put(player.getUUID(), PaperRollbackPlayerSeed.capture(player, 0).portable(71));
                livePlayers.put(player.getUUID(), player);
            }
            var initial = new RollbackRosterData(roster.worldTime(), originals);
            defenderBody.combatTracker.entries.add(new net.minecraft.world.damagesource.CombatEntry(damage, 0, null, 0));
            var outgoing = RollbackRosterData.decode(scene.captureRoster(initial, 0).encode());
            var history = outgoing.combatActors();
            assertEquals(1, history.actors().size());
            assertEquals(nativeBall.getUUID(), history.actors().getFirst().identity().uuid());
            var active = PaperRollbackActorRestoration.Captured.capture(history.session(), scene, history);
            assertTrue(active.data().actors().isEmpty());
            ServerPlayer liveTarget = source.getFirst();
            var stage = new PaperRollbackActorRestoration(liveTarget.level(), livePlayers, active,
                    body -> PaperRollbackPrivateAccess.actorWrapper(body, liveTarget.level().getCraftServer(),
                            org.bukkit.entity.EntityType.SNOWBALL));
            var bodies = stage.combatHistory(history, active.data());
            var returned = bodies.get(nativeBall.getUUID());
            assertNotSame(nativeBall, returned);
            assertSame(liveTarget.level(), returned.level());
            assertEquals(17, returned.tickCount);
            assertTrue(returned.isRemoved());
            assertFalse(returned.valid);
            assertFalse(returned.inWorld);
            stage.commit();
            var gameplay = PaperRollbackRosterComponents.prepare(livePlayers, outgoing, 0, bodies);
            gameplay.commit();
            var liveDamage = (net.minecraft.world.damagesource.DamageSource) lastDamage.get(liveTarget);
            assertSame(liveDamage, liveTarget.combatTracker.entries.getLast().source());
            assertSame(returned, liveDamage.getDirectEntity());
            assertSame(source.getLast(), liveDamage.getEntity());
            assertSame(source.getLast(), ((net.minecraft.world.entity.projectile.Projectile) returned).getOwner());
            assertTrue(stage.bindings().isEmpty());
            assertNull(liveTarget.level().getEntityInAnyDimension(returned.getUUID()));

            var impostor = new PaperRollbackSnowball(scene);
            impostor.setId(nativeBall.getId());
            impostor.setUUID(nativeBall.getUUID());
            PaperRollbackPrivateAccess.retirePrivateActor(impostor);
            lastDamage.set(defenderBody, scene.world().world().damageSources().thrown(impostor, caster));
            assertThrows(IllegalStateException.class, defender::captureRollbackState);
            assertThrows(IllegalArgumentException.class, () -> scene.captureRoster(initial, 0));
            return null;
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Snowball", "Arrow", "ShulkerBullet"})
    void acceptedPrivateProjectileDamagesOnlyItsPrivateTargetAcrossReplay(String kind) throws Exception {
        onTickThread(() -> {
            var roster = PaperRollbackRosterSeed.capture(source(), 0);
            var damage = new ArrayList<org.bukkit.event.entity.EntityDamageByEntityEvent>();
            var events = new ArrayList<org.bukkit.event.Event>();
            var scene = scene(seed(), roster, roster.participants(), new Events() {
                @Override public void gameEvent(Object event, Object pos, Object source) { }
                @Override public void output(PaperRollbackCombatAccess.Output output) { }
                @Override public void event(org.bukkit.event.Event event) {
                    events.add(event);
                    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent byEntity) damage.add(byEntity);
                }
            });
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(UUID.randomUUID(), 16, items);
            actors.bind(scene);
            var views = bindAbilityViews(scene, actors, items).players().values().stream().toList();
            var caster = views.getFirst(); var target = views.getLast();
            var targetBody = scene.roster().get(target.getUniqueId());
            actors.nativeEntity(caster).setPos(.5, 4, -2);
            actors.nativeEntity(target).setPos(.5, 4, 2);
            var pose = new RollbackEntityBody.Pose(.5, 5.25, .5, 0, 0);
            com.projectkorra.projectkorra.platform.mc.entity.Projectile shot = switch (kind) {
                case "Snowball" -> actors.spawn(scene.logical(), pose, com.projectkorra.projectkorra.platform.mc.entity.Snowball.class);
                case "Arrow" -> actors.spawn(scene.logical(), pose, com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
                default -> actors.spawn(scene.logical(), pose, com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet.class);
            };
            scene.logical().entities().add(shot);
            shot.setShooter(caster);
            var projectile = actors.nativeEntity(shot);
            projectile.setNoGravity(true); projectile.setDeltaMovement(0, 0, 3);
            if (projectile instanceof PaperRollbackArrow arrow) {
                arrow.setRemainingFireTicks(80);
                arrow.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 80));
            }
            var actor = (PaperRollbackActor) actors.registry().get(shot.getUniqueId());
            var graph = new RollbackStateGraph(value -> false, field -> true, 2048);
            var bus = new RollbackEventBus(256, 16);
            var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform)Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("events")) return bus;
                        throw new AssertionError(method);
                    });
            var domain = RollbackDomain.create(graph, List.of(), List.of(), platform, () -> { });
            var before = targetBody.captureRollbackState();
            var beforeShooter = scene.roster().get(caster.getUniqueId()).captureRollbackState();
            var flight = actor.captureRollbackState();
            var logical = RollbackEntityBody.logicalBody(shot);
            var logicalBefore = logical.captureRollbackState();
            double health = target.getHealth();
            for (int pass = 1; pass <= 2; pass++) {
                if (pass == 2) {
                    targetBody.restoreRollbackState(before); scene.roster().get(caster.getUniqueId()).restoreRollbackState(beforeShooter); actor.restoreRollbackState(flight); logical.restoreRollbackState(logicalBefore);
                }
                domain.call(() -> { try (var clock = RollbackClock.at(1000, 1, 50_000_000)) { actor.tick(); } return null; });
                assertTrue(projectile.isRemoved());
                assertEquals(pass, damage.size());
                assertSame(projectile.getBukkitEntity(), damage.getLast().getDamager());
                assertSame(targetBody.ownedPlayer().getBukkitEntity(), damage.getLast().getEntity());
                if (kind.equals("Snowball")) assertEquals(health, target.getHealth());
                else assertTrue(target.getHealth() < health);
                if (kind.equals("Arrow")) {
                    assertTrue(targetBody.ownedPlayer().hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS));
                    assertEquals(pass, events.stream().filter(org.bukkit.event.entity.EntityCombustByEntityEvent.class::isInstance).count());
                }
                if (kind.equals("ShulkerBullet")) assertTrue(targetBody.ownedPlayer().hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION));
            }
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void smokescreenCloudRestoresTheBlindnessBehindPapersColorView() throws Exception {
        onTickThread(() -> {
            var captured = PaperRollbackRosterSeed.capture(source(), 0);
            var scene = scene(seed(), captured, captured.participants());
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(new UUID(222, 333), 16, items);
            actors.bind(scene);
            var shooter = bindAbilityViews(scene, actors, items).players().values().iterator().next();
            var cloud = actors.spawn(scene.logical(), new RollbackEntityBody.Pose(0, 3, 0, 0, 0),
                    com.projectkorra.projectkorra.platform.mc.entity.AreaEffectCloud.class);
            scene.logical().entities().add(cloud);
            var body = (net.minecraft.world.entity.AreaEffectCloud) actors.nativeEntity(cloud);
            var blindness = new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 40, 2);
            // CraftAreaEffectCloud.addCustomEffect delegates here after resolving its Bukkit potion registry key.
            body.addEffect(blindness);
            cloud.setSource(shooter);
            cloud.setDuration(80);
            cloud.setRadius(3);
            cloud.setColor(com.projectkorra.projectkorra.platform.mc.Color.BLACK);
            cloud.setWaitTime(0);
            cloud.setReapplicationDelay(10);
            var contents = body.potionContents;
            var field = contents.getClass().getDeclaredField("customEffects");
            field.setAccessible(true);
            var view = (List<?>) field.get(contents);
            assertEquals("com.google.common.collect.Lists$TransformingRandomAccessList", view.getClass().getName());
            assertNotSame(view.getFirst(), view.getFirst());
            var actor = (PaperRollbackActor) actors.registry().get(cloud.getUniqueId());
            var checkpoint = actor.captureRollbackState();
            for (int pass = 0; pass < 2; pass++) {
                assertTrue(blindness.update(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.BLINDNESS, 100, 2)));
                body.setPotionContents(net.minecraft.world.item.alchemy.PotionContents.EMPTY);
                body.setRadius(1);
                actor.restoreRollbackState(checkpoint);
                assertSame(contents, body.potionContents);
                assertSame(view, field.get(body.potionContents));
                assertEquals(40, blindness.getDuration());
                assertEquals(40, body.potionContents.customEffects().getFirst().getDuration());
                assertEquals(2, body.potionContents.customEffects().getFirst().getAmplifier());
                assertEquals(3, body.getRadius());
                assertSame(actors.nativeEntity(shooter), body.getOwner());
            }
            return null;
        });
    }

    @Test void enrolledSmokescreenAppliesBlindnessAgainAndExpiresAcrossRewind() throws Exception {
        onTickThread(() -> {
            var original = source();
            var captured = PaperRollbackRosterSeed.capture(original, 0);
            var pulses = new ArrayList<List<UUID>>();
            var removals = new ArrayList<org.bukkit.event.entity.EntityRemoveEvent>();
            var scene = scene(seed(), captured, captured.participants(), new Events() {
                @Override public void event(org.bukkit.event.Event event) {
                    if (event instanceof org.bukkit.event.entity.AreaEffectCloudApplyEvent pulse) {
                        pulses.add(pulse.getAffectedEntities().stream().map(org.bukkit.entity.Entity::getUniqueId).toList());
                        if (pulses.size() == 1) pulse.setCancelled(true);
                    } else if (event instanceof org.bukkit.event.entity.EntityRemoveEvent removal) removals.add(removal);
                }
                @Override public void output(PaperRollbackCombatAccess.Output output) { }
                @Override public void gameEvent(Object event, Object pos, Object source) { }
            });
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var session = new UUID(723, 886);
            var actors = new PaperRollbackActors(session, 16, items);
            actors.bind(scene);
            var roster = bindAbilityViews(scene, actors, items).players();
            var participants = roster.values().stream().toList();
            var shooter = participants.getFirst();
            var defender = participants.getLast();
            var defenderState = scene.roster().get(defender.getUniqueId());
            var target = defenderState.ownedPlayer();
            var caster = scene.roster().get(shooter.getUniqueId()).ownedPlayer();
            var round = new RollbackRound(session, Map.of(shooter.getUniqueId(), shooter.getUniqueId(), defender.getUniqueId(), defender.getUniqueId()));
            scene.world().bindRound(round);
            caster.setPos(8, 4, 0);
            target.setPos(1, 4, 0);
            target.setNoGravity(true);
            var cloud = actors.spawn(scene.logical(), new RollbackEntityBody.Pose(0, 4, 0, 0, 0),
                    com.projectkorra.projectkorra.platform.mc.entity.AreaEffectCloud.class);
            scene.logical().entities().add(cloud);
            var body = (net.minecraft.world.entity.AreaEffectCloud) actors.nativeEntity(cloud);
            var blindness = net.minecraft.world.effect.MobEffects.BLINDNESS;
            body.addEffect(new net.minecraft.world.effect.MobEffectInstance(blindness, 40, 2));
            cloud.setSource(shooter); cloud.setDuration(22); cloud.setRadius(3);
            cloud.setColor(com.projectkorra.projectkorra.platform.mc.Color.BLACK);
            cloud.setWaitTime(0); cloud.setReapplicationDelay(10);
            var actor = (PaperRollbackActor) actors.registry().get(cloud.getUniqueId());
            var logical = RollbackEntityBody.logicalBody(cloud);
            var beforeActors = actors.registry().captureRollbackState();
            var beforeEntities = scene.logical().entities().captureRollbackState();
            var beforeCloud = actor.captureRollbackState();
            var beforeLogical = logical.captureRollbackState();
            var beforeTarget = defenderState.captureRollbackState();
            var beforeRound = round.captureRollbackState();
            var bus = new RollbackEventBus(32, 8);
            var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("events")) return bus;
                        throw new AssertionError(method);
                    });
            var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 2048),
                    List.of(), List.of(), platform, () -> { });
            for (int pass = 0; pass < 3; pass++) {
                if (pass > 0) {
                    actor.restoreRollbackState(beforeCloud); logical.restoreRollbackState(beforeLogical);
                    scene.logical().entities().restoreRollbackState(beforeEntities);
                    actors.registry().restoreRollbackState(beforeActors);
                    defenderState.restoreRollbackState(beforeTarget); round.restoreRollbackState(beforeRound);
                    pulses.clear(); removals.clear();
                }
                var current = actor;
                for (int step = 1; step <= 23; step++) {
                    int tick = step;
                    var owned = current;
                    domain.call(() -> {
                        round.beginTick(tick);
                        try (var clock = RollbackClock.at(1000, tick, 50_000_000); var scope = new RollbackControlEvents(roster)) {
                            defenderState.tick();
                            owned.tick();
                        }
                        return null;
                    });
                    if (tick < 10) assertFalse(target.hasEffect(blindness));
                    if (tick == 10 || tick == 20) {
                        assertEquals(40, target.getEffect(blindness).getDuration());
                        assertEquals(2, target.getEffect(blindness).getAmplifier());
                    }
                    if (tick == 15) assertEquals(35, target.getEffect(blindness).getDuration());
                    assertFalse(caster.hasEffect(blindness));
                    assertEquals(tick >= 22, current.entity().isRemoved());
                    if (pass == 2 && tick == 12) {
                        var repair = RollbackActorData.decode(actors.registry().exportState().encode());
                        var row = repair.actors().getFirst();
                        var frame = RollbackActorFrame.decode(row.state());
                        assertEquals(Map.of(target.getUUID(), 20), frame.cloudVictims());
                        var pending = new ArrayList<PaperRollbackAreaEffectCloud.Applications>();
                        var live = original.getFirst().level();
                        var staged = PaperRollbackActorRestoration.stage(live, entity -> PaperRollbackPrivateAccess.actorWrapper(entity,
                                live.getCraftServer(), org.bukkit.entity.EntityType.AREA_EFFECT_CLOUD), row, pending::add);
                        var liveBodies = new HashMap<UUID, net.minecraft.world.entity.Entity>();
                        original.forEach(player -> liveBodies.put(player.getUUID(), player));
                        assertThrows(IllegalArgumentException.class, () -> pending.getFirst().bind(Map.of()));
                        pending.getFirst().bind(liveBodies);
                        var field = net.minecraft.world.entity.AreaEffectCloud.class.getDeclaredField("victims");
                        field.setAccessible(true);
                        var stagedVictims = (Map<?, ?>) field.get(staged);
                        assertSame(liveBodies.get(target.getUUID()), stagedVictims.keySet().iterator().next());
                        assertEquals(20, stagedVictims.values().iterator().next());
                        actors.importState(repair);
                        current = (PaperRollbackActor) actors.registry().get(row.identity().uuid());
                        assertNotSame(owned.entity(), current.entity());
                    }
                }
                var hit = List.of(defender.getUniqueId());
                assertEquals(List.of(hit, hit, List.of(), hit), pulses);
                assertEquals(1, removals.size());
                assertSame(current.entity().getBukkitEntity(), removals.getFirst().getEntity());
                assertEquals(org.bukkit.event.entity.EntityRemoveEvent.Cause.DESPAWN, removals.getFirst().getCause());
            }
            return null;
        });
    }

    @Test void enrolledSnowballHitReachesTheBendingDamageListenerWithItsActorAndDefender() throws Exception {
        onTickThread(() -> {
            var captured = PaperRollbackRosterSeed.capture(source(), 0);
            var events = new ArrayList<org.bukkit.event.entity.EntityDamageByEntityEvent>();
            var scene = scene(seed(), captured, captured.participants(), new Events() {
                @Override public void event(org.bukkit.event.Event event) {
                    if (event instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damage) events.add(damage);
                }
                @Override public void output(PaperRollbackCombatAccess.Output output) { }
                @Override public void gameEvent(Object event, Object pos, Object source) { }
            });
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var session = new UUID(123, 459);
            var actors = new PaperRollbackActors(session, 16, items);
            actors.bind(scene);
            var roster = bindAbilityViews(scene, actors, items).players();
            var order = roster.values().stream().toList();
            var shooter = order.getFirst(); var defender = order.getLast();
            var round = new RollbackRound(session, Map.of(shooter.getUniqueId(), shooter.getUniqueId(),
                    defender.getUniqueId(), defender.getUniqueId()));
            scene.world().bindRound(round);
            var ball = actors.spawn(scene.logical(), new RollbackEntityBody.Pose(0, 4, 0, 0, 0),
                    com.projectkorra.projectkorra.platform.mc.entity.Snowball.class);
            scene.logical().entities().add(ball);
            ball.setShooter(shooter);
            var nativeBall = (PaperRollbackSnowball) actors.nativeEntity(ball);
            var nativeTarget = actors.nativeEntity(defender);
            var bus = new RollbackEventBus(32, 8);
            var observed = new ArrayList<com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent>();
            var listener = new Object() {
                @com.projectkorra.projectkorra.platform.mc.event.EventHandler
                public void onDamage(com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent event) {
                    observed.add(event); event.setCancelled(true);
                }
            };
            var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("events")) return bus;
                        throw new AssertionError(method);
                    });
            var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 2048),
                    List.of(), List.of(), platform, () -> bus.registerListener(listener));
            domain.call(() -> {
                round.beginTick(1);
                try (var clock = RollbackClock.at(1_000, 1, 50_000_000); var scope = new RollbackControlEvents(roster)) {
                    nativeBall.preHitTargetOrDeflectSelf(new net.minecraft.world.phys.EntityHitResult(nativeTarget));
                    assertEquals(1, observed.size());
                    assertSame(ball, observed.getFirst().getDamager());
                    assertSame(defender, observed.getFirst().getEntity());
                    assertEquals(1, events.size());
                    assertSame(nativeBall.getBukkitEntity(), events.getFirst().getDamager());
                    assertTrue(events.getFirst().isCancelled());
                    assertTrue(round.provisionalDefeats().isEmpty());
                    assertTrue(nativeBall.isRemoved());
                }
                return null;
            });
            return null;
        });
    }

    @Test void privateSnowballImpactDeliversExactLogicalActorsWithoutALiveBukkitServer() throws Exception {
        onTickThread(() -> {
            var roster = PaperRollbackRosterSeed.capture(source(), 0);
            var outputs = new ArrayList<PaperRollbackCombatAccess.Output>();
            var removed = new ArrayList<org.bukkit.event.Event>();
            var blockHits = new ArrayList<Object>();
            var captured = seed();
            var geometry = new RollbackStructurePieces(List.of(new RollbackStructurePieces.Piece(
                    "minecraft:fortress", -3, 4, -3, -1, 9, -1)));
            var settings = RollbackWorldSettings.decode(captured.settings().withStructures(geometry).encode());
            assertEquals(geometry, settings.structures());
            var arena = new RollbackWorldSeed(captured.world(), captured.name(), captured.minimumY(), captured.maximumY(),
                    captured.dayTime(), captured.storm(), settings, captured.border(), captured.environment(), captured.terrain(),
                    captured.light(), captured.scheduledTicks());
            var scene = scene(arena, roster, roster.participants(), new Events() {
                @Override public void event(org.bukkit.event.Event event) { removed.add(event); }
                @Override public void output(PaperRollbackCombatAccess.Output output) { outputs.add(output); }
                @Override public void gameEvent(Object event, Object position, Object context) { blockHits.add(event); }
            });
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(new UUID(72, 34), 16, items);
            actors.bind(scene);
            assertTrue(scene.world().world().isLoaded(new net.minecraft.core.BlockPos(-1, 4, -1)));
            assertFalse(scene.world().world().isLoaded(new net.minecraft.core.BlockPos(32, 4, 0)));
            var level = scene.world().world();
            var structure = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE)
                    .get(net.minecraft.resources.Identifier.parse("minecraft:fortress")).orElseThrow();
            var inFortress = net.minecraft.advancements.criterion.LocationPredicate.Builder.inStructure(structure).build();
            assertTrue(inFortress.matches(level, -1, 4, -1));
            assertFalse(inFortress.matches(level, -1, 3, -1));
            assertFalse(inFortress.matches(level, 32, 4, 0));
            var pose = new RollbackEntityBody.Pose(0, 4, 0, 0, 0);
            var ball = actors.spawn(scene.logical(), pose, com.projectkorra.projectkorra.platform.mc.entity.Snowball.class);
            var target = actors.spawn(scene.logical(), pose, com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay.class);
            var dart = actors.spawn(scene.logical(), pose, com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
            scene.logical().entities().add(dart);
            scene.logical().entities().add(ball);
            scene.logical().entities().add(target);
            var events = new ArrayList<com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent>();
            var bus = (com.projectkorra.projectkorra.platform.PKEventBus) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.PKEventBus.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("call")) throw new AssertionError(method);
                        var hit = (com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent) args[0];
                        events.add(hit);
                        hit.setCancelled(hit.getHitEntity() != null);
                        return null;
                    });
            var platform = (com.projectkorra.projectkorra.platform.ProjectKorraPlatform) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{com.projectkorra.projectkorra.platform.ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("events")) return bus;
                        throw new AssertionError(method);
                    });
            var domain = RollbackDomain.create(new RollbackStateGraph(value -> false, field -> true, 2048),
                    List.of(), List.of(), platform, () -> { });
            domain.call(() -> {
                try (var clock = RollbackClock.at(1000, 1, 50_000_000)) {
                    var nativeBall = (PaperRollbackSnowball) actors.actor(ball.getUniqueId());
                    var result = nativeBall.preHitTargetOrDeflectSelf(new net.minecraft.world.phys.EntityHitResult(actors.actor(target.getUniqueId())));
                    assertSame(net.minecraft.world.entity.projectile.ProjectileDeflection.NONE, result);
                    assertFalse(nativeBall.isRemoved());
                    var pos = new net.minecraft.core.BlockPos(0, 4, 0);
                    nativeBall.preHitTargetOrDeflectSelf(new net.minecraft.world.phys.BlockHitResult(pos.getCenter(),
                            net.minecraft.core.Direction.UP, pos, false));
                    assertTrue(nativeBall.isRemoved());

                    var nativeDart = (PaperRollbackArrow) actors.actor(dart.getUniqueId());
                    nativeDart.snapTo(8, 4, 8, 0, 0);
                    nativeDart.setDeltaMovement(0, 0, 2);
                    var dartActor = actors.registry().get(dart.getUniqueId());
                    dartActor.tick();
                    assertEquals(10, nativeDart.getZ(), 1e-5);
                    dart.setDamage(0);
                    dart.setPickupStatus(com.projectkorra.projectkorra.platform.mc.entity.Arrow.PickupStatus.DISALLOWED);
                    assertEquals(com.projectkorra.projectkorra.platform.mc.entity.Arrow.PickupStatus.DISALLOWED, dart.getPickupStatus());
                    dart.setShooter(target);
                    assertSame(target, dart.getShooter());
                    nativeDart.preHitTargetOrDeflectSelf(new net.minecraft.world.phys.EntityHitResult(actors.actor(target.getUniqueId())));
                    assertFalse(nativeDart.isRemoved());
                    assertFalse(dart.isInBlock());
                    assertFalse(dart.isInWaterOrBubbleColumn());
                    nativeDart.preHitTargetOrDeflectSelf(new net.minecraft.world.phys.BlockHitResult(pos.getCenter(),
                            net.minecraft.core.Direction.UP, pos, false));
                    assertTrue(dart.isInBlock());
                }
                return null;
            });
            assertEquals(4, events.size());
            assertSame(dart, events.get(2).getEntity());
            assertSame(target, events.get(2).getHitEntity());
            assertEquals(4, events.getLast().getHitBlock().getY());
            assertEquals(2L, blockHits.stream().filter(net.minecraft.world.level.gameevent.GameEvent.PROJECTILE_LAND::equals).count());
            assertTrue(blockHits.contains(net.minecraft.world.level.gameevent.GameEvent.PROJECTILE_SHOOT));
            assertEquals(1, removed.size());
            assertEquals(org.bukkit.event.entity.EntityRemoveEvent.Cause.HIT,
                    ((org.bukkit.event.entity.EntityRemoveEvent)removed.getFirst()).getCause());
            assertEquals(new PaperRollbackCombatAccess.StatusOutput(ball.getUniqueId(), (byte)3), outputs.getFirst());
            assertSame(ball, events.getFirst().getEntity());
            assertSame(target, events.getFirst().getHitEntity());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void privateMiningHonorsTheKitKeepsNativeDropsAndDefersObjectiveCallbacks() throws Exception {
        onTickThread(() -> {
            var configField = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
            configField.setAccessible(true);
            var previous = configField.get(null);
            var config = new io.papermc.paper.configuration.GlobalConfiguration();
            config.misc = new org.objenesis.ObjenesisStd().newInstance(io.papermc.paper.configuration.GlobalConfiguration.Misc.class);
            configField.set(null, config);
            try {
                var roster = PaperRollbackRosterSeed.capture(source(), 0);
                var initial = seed();
                var rules = new RollbackDuelInteractions(false, true, Set.of("GOLD_ORE"), Set.of())
                        .withLiveBreakMaterials(PaperRollbackMining.liveMaterials());
                assertTrue(rules.liveBreakMaterials().contains("GOLD_ORE"));
                var arena = new RollbackWorldSeed(initial.world(), initial.name(), initial.minimumY(), initial.maximumY(),
                        initial.dayTime(), initial.storm(), initial.settings().withInteractions(rules), initial.border(),
                        initial.environment(), initial.terrain(), initial.light(), initial.scheduledTicks());
                var events = new ArrayList<org.bukkit.event.Event>();
                var scene = scene(arena, roster, roster.participants(), new Events() {
                    @Override public void event(org.bukkit.event.Event event) { events.add(event); }
                    @Override public void gameEvent(Object event, Object pos, Object context) { }
                    @Override public void output(PaperRollbackCombatAccess.Output ignored) { }
                });
                var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
                var actors = new PaperRollbackActors(new UUID(72, 44), 16, items);
                actors.bind(scene);
                var services = new TreeMap<UUID, RollbackRosterViews.Services>();
                scene.roster().forEach((id, nativePlayer) -> {
                    var body = (ServerPlayer) nativePlayer.ownedPlayer();
                    var profile = new RollbackPlayerState.Profile(body.getGameProfile().name(), "SURVIVAL", "RIGHT", true, false, true, 50);
                    services.put(id, new RollbackRosterViews.Services(profile, Set.of(),
                            new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(),
                            actors, unused(RollbackPlayerState.Rules.class)));
                });
                var views = PaperRollbackRosterViews.bind(scene.logical(), scene.world(), roster.participants(), scene.roster(), items, services);
                var player = views.players().values().iterator().next();
                var body = (ServerPlayer) scene.roster().get(player.getUniqueId()).ownedPlayer();
                var step = new RollbackStep<Object>(1, 10);
                var mining = new PaperRollbackMining(scene, actors, body, player, step);
                var pos = new RollbackBlockStore.Position(0, 4, 0);
                var block = scene.logical().getBlockAt(0, 4, 0);
                block.setType(Material.STONE, false);
                assertFalse(mining.mayBreak(pos));
                block.setType(Material.OAK_PLANKS, false);
                var axe = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_AXE);
                body.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, axe);
                try (var clock = RollbackClock.at(1000, 2000, 1, 50_000_000)) {
                    assertTrue(mining.mayBreak(pos));
                    assertTrue(mining.delta(pos) > 0);
                    mining.attack(pos);
                    mining.destroy(pos);
                    assertEquals(Material.AIR, block.getType());
                    assertEquals(1, axe.getDamageValue());
                    assertEquals(1, body.getStats().getValue(net.minecraft.stats.Stats.BLOCK_MINED, net.minecraft.world.level.block.Blocks.OAK_PLANKS));
                    var dropped = (net.minecraft.world.entity.item.ItemEntity) actors.active().getFirst();
                    assertTrue(dropped.getItem().is(net.minecraft.world.item.Items.OAK_PLANKS));
                    block.setType(Material.GOLD_ORE, false);
                    assertTrue(mining.mayBreak(pos));
                    mining.destroy(pos);
                    assertEquals(Material.GOLD_ORE, block.getType());
                }
                assertEquals(1, events.stream().filter(org.bukkit.event.block.BlockBreakEvent.class::isInstance).count());
                var effects = step.finish();
                assertEquals(2, effects.size());
                assertInstanceOf(PaperRollbackMining.Broken.class, effects.getFirst());
                assertEquals(new RollbackDuelInteractions.LiveBreak(player.getUniqueId(), 0, 4, 0, "GOLD_ORE"), effects.getLast());
                return null;
            } finally { configField.set(null, previous); }
        });
    }

    @Test void itemDropsUseTheSelectedSlotAndRewindTheOwnedThrowerAndActor() throws Exception {
        onTickThread(() -> {
            var configField = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
            configField.setAccessible(true);
            var previous = configField.get(null);
            var config = new io.papermc.paper.configuration.GlobalConfiguration();
            config.misc = new org.objenesis.ObjenesisStd().newInstance(io.papermc.paper.configuration.GlobalConfiguration.Misc.class);
            configField.set(null, config);
            try {
                var roster = PaperRollbackRosterSeed.capture(source(), 0);
                var events = new ArrayList<org.bukkit.event.player.PlayerDropItemEvent>();
                var scene = scene(seed(), roster, roster.participants(), new Events() {
                    @Override public void event(org.bukkit.event.Event event) {
                        if (event instanceof org.bukkit.event.player.PlayerDropItemEvent drop) events.add(drop);
                    }
                    @Override public void output(PaperRollbackCombatAccess.Output output) { }
                });
                var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
                var actors = new PaperRollbackActors(new UUID(72, 41), 16, items);
                actors.bind(scene);
                var player = (ServerPlayer) scene.roster().values().iterator().next().ownedPlayer();
                var inventory = player.getInventory();
                var diamonds = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 3);
                inventory.setItem(inventory.getSelectedSlot(), diamonds);
                var frame = new RollbackStateGraph(v -> Proxy.isProxyClass(v.getClass()), f -> true, 600_000)
                        .capture(List.of(scene, actors), List.of());
                byte[] first = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    if (attempt > 0) frame.restore();
                    try (var clock = RollbackClock.at(1000, 2000, 1, 50_000_000)) {
                        actors.dropSelected(player, false);
                        assertEquals(2, inventory.getSelectedItem().getCount());
                        var item = (net.minecraft.world.entity.item.ItemEntity) actors.active().getFirst();
                        assertEquals(1, item.getItem().getCount());
                        assertEquals(40, item.pickupDelay);
                        assertEquals(player.getUUID(), item.thrower.getUUID());
                        assertEquals(player.getEyeY() - (double) 0.3F, item.getY());
                        actors.dropSelected(player, true);
                        assertTrue(inventory.getSelectedItem().isEmpty());
                        assertEquals(2, actors.active().size());
                        var second = (net.minecraft.world.entity.item.ItemEntity) actors.active().getLast();
                        assertEquals(2, second.getItem().getCount());
                        assertEquals(3, player.getStats().getValue(net.minecraft.stats.Stats.ITEM_DROPPED, net.minecraft.world.item.Items.DIAMOND));
                        var state = actors.registry().exportState().encode();
                        if (first == null) first = state; else assertArrayEquals(first, state);
                        second.setPos(4.5, 4.0, 4.5);
                        item.tick();
                        assertEquals(39, item.pickupDelay);
                        item.setNoPickUpDelay();
                        item.playerTouch(player);
                        assertTrue(item.isRemoved());
                        assertTrue(inventory.contains(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND)));
                    }
                }
                assertEquals(4, events.size());
                assertNull(Bukkit.getServer());
                return null;
            } finally { configField.set(null, previous); }
        });
    }

    @Test void confirmedDisplayRetainsIdentityBlockStateAndGraphBindingAcrossRepair() throws Exception {
        onTickThread(() -> {
            var roster = PaperRollbackRosterSeed.capture(source(), 0);
            var scene = scene(seed(), roster, roster.participants());
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var session = new UUID(33, 81);
            var actors = new PaperRollbackActors(session, 32, items);
            actors.bind(scene);
            var pos = new RollbackEntityBody.Pose(0, 4, 0, 0, 0);
            var display = actors.spawn(scene.logical(), pos, com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay.class);
            scene.logical().entities().add(display);
            display.setBlock(new PaperRollbackTerrainTransfer().decode(Material.OAK_PLANKS, "minecraft:oak_planks"));
            var saved = actors.registry().exportState();
            var memory = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000)
                    .capture(List.of(actors), List.of());
            display.setBlock(new PaperRollbackTerrainTransfer().decode(Material.STONE, "minecraft:stone"));
            memory.restore();
            assertArrayEquals(saved.encode(), actors.registry().exportState().encode());
            var bindings = actors.registry().bindings();
            assertEquals(1, bindings.size()); assertSame(display, bindings.getFirst().value());
            actors.importState(saved);
            var restored = actors.registry().bindings().getFirst();
            assertNotSame(display, restored.value()); assertEquals(bindings.getFirst().id(), restored.id());
            assertEquals(com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay.class, restored.type());
            var now = actors.registry().exportState();
            assertEquals(PaperRollbackBlockSnapshots.decode(RollbackActorFrame.decode(saved.actors().getFirst().state()).nbt()),
                    PaperRollbackBlockSnapshots.decode(RollbackActorFrame.decode(now.actors().getFirst().state()).nbt()));
            assertArrayEquals(saved.encode(), now.encode());
            assertNull(Bukkit.getServer());
            return null;
        });
    }

    @Test void parkourFloorReturnsOnlyTheFallingPlayerToTheirOwnCheckpoint() throws Exception {
        onTickThread(() -> {
            var captured = PaperRollbackRosterSeed.capture(source(), 0);
            var scene = scene(seed(), captured, captured.participants());
            var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
            var actors = new PaperRollbackActors(new UUID(243, 13), 16, items);
            actors.bind(scene);
            var players = bindAbilityViews(scene, actors, items).players().values().stream().toList();
            var first = players.getFirst(); var second = players.getLast();
            var state = scene.roster().get(first.getUniqueId());
            var nativePlayer = (ServerPlayer) state.ownedPlayer();
            var opponent = second.getLocation();
            var round = new RollbackRound(new UUID(243, 13), Map.of(first.getUniqueId(), first.getUniqueId(), second.getUniqueId(), second.getUniqueId()));
            var floor = new RollbackArenaFloor(1, Map.of(
                    first.getUniqueId(), new RollbackArenaFloor.Respawn(3.5, 8, -2.5, 73, -18),
                    second.getUniqueId(), new RollbackArenaFloor.Respawn(-8.5, 9, 2.5, -45, 0)));
            floor.validate(round);
            round.beginTick(1);
            nativePlayer.setPos(.5, 1.25, .5);
            nativePlayer.setDeltaMovement(.3, -.8, -.1);
            var original = nativePlayer.position();
            var motion = nativePlayer.getDeltaMovement();
            var before = state.captureRollbackState();
            floor.afterMovement(round, first);
            assertEquals(original, nativePlayer.position());
            for (int replay = 0; replay < 2; replay++) {
                if (replay > 0) {
                    state.restoreRollbackState(before);
                    assertEquals(original, nativePlayer.position());
                    assertEquals(motion, nativePlayer.getDeltaMovement());
                }
                nativePlayer.setPos(.5, 1, .5);
                floor.afterMovement(round, first);
                assertEquals(new net.minecraft.world.phys.Vec3(3.5, 8, -2.5), nativePlayer.position());
                assertEquals(73, nativePlayer.getYRot());
                assertEquals(-18, nativePlayer.getXRot());
                assertEquals(net.minecraft.world.phys.Vec3.ZERO, nativePlayer.getDeltaMovement());
                assertEquals(opponent, second.getLocation());
                assertTrue(round.active(first.getUniqueId()));
                assertTrue(round.provisionalDefeats().isEmpty());
            }
            return null;
        });
    }

    private static RollbackRosterViews bindAbilityViews(PaperRollbackNativeScene scene, PaperRollbackActors actors,
                                                         RollbackNativeItems<net.minecraft.world.item.ItemStack> items) {
        var services = new TreeMap<UUID, RollbackRosterViews.Services>();
        scene.roster().forEach((id, state) -> {
            var player = (ServerPlayer) state.ownedPlayer();
            var profile = new RollbackPlayerState.Profile(player.getGameProfile().name(), "SURVIVAL", "RIGHT", true, false, true, 50);
            services.put(id, new RollbackRosterViews.Services(profile, Set.of(),
                    new com.projectkorra.projectkorra.platform.mc.scoreboard.Scoreboard(), actors, unused(RollbackPlayerState.Rules.class)));
        });
        return PaperRollbackRosterViews.bind(scene.logical(), scene.world(), scene.roster().keySet(), scene.roster(), items, services);
    }

    @Test void nativeParticipantCanFallAcrossTheDimensionFloorAndRewind() throws Exception {
        onTickThread(() -> {
            var captured = PaperRollbackRosterSeed.capture(source(), 0);
            var seed = seed();
            var scene = scene(seed, captured, captured.participants(), new Events() {
                @Override public void event(org.bukkit.event.Event event) { }
                @Override public void output(PaperRollbackCombatAccess.Output output) { }
                @Override public void gameEvent(Object event, Object position, Object context) { }
            });
            var level = scene.world().world();
            var floor = new net.minecraft.core.BlockPos(0, seed.minimumY(), 0);
            var top = new net.minecraft.core.BlockPos(0, seed.maximumY(), 0);
            for (var outside : List.of(floor.below(), top, top.above())) {
                assertTrue(level.getBlockState(outside).is(net.minecraft.world.level.block.Blocks.VOID_AIR));
                assertTrue(level.getFluidState(outside).isEmpty());
                assertNull(level.getBlockEntity(outside));
                assertTrue(level.getBlockStateIfLoaded(outside).is(net.minecraft.world.level.block.Blocks.VOID_AIR));
            }
            assertTrue(level.getBlockState(floor).is(net.minecraft.world.level.block.Blocks.AIR));
            assertThrows(IllegalStateException.class, () -> level.getBlockState(floor.offset(1000, 0, 0)));
            assertThrows(IllegalStateException.class, () -> scene.logical().getBlockAt(0, floor.getY() - 1, 0).setType(Material.STONE));
            var state = scene.roster().values().iterator().next();
            var player = (ServerPlayer) state.ownedPlayer();
            player.setPos(.5, floor.getY() + .25, .5);
            player.setDeltaMovement(0, -.7, 0);
            var before = state.captureRollbackState();
            Runnable falling = () -> {
                for (int step = 1; step <= 20; step++) {
                    try (var clock = RollbackClock.at(1000, step, 50_000_000)) { state.tick(); }
                }
            };
            falling.run();
            assertTrue(player.getY() < floor.getY() - 16);
            var position = player.position();
            var velocity = player.getDeltaMovement();
            state.restoreRollbackState(before);
            falling.run();
            assertEquals(position, player.position());
            assertEquals(velocity, player.getDeltaMovement());
            return null;
        });
    }

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
            var chestPos = new net.minecraft.core.BlockPos(1, 4, 0);
            var chestData = new BlockData(Material.CHEST);
            chestData.setExactState("minecraft:chest");
            scene.logical().getBlockAt(1, 4, 0).setBlockData(chestData, false);
            var tile = scene.world().world().getBlockEntity(chestPos);
            assertInstanceOf(net.minecraft.world.level.block.entity.ChestBlockEntity.class, tile);
            assertEquals(21, scene.world().world().getGameTime());
            var original = new TreeMap<UUID, RollbackRosterData.Player>();
            for (var body : source) original.put(body.getUUID(), PaperRollbackPlayerSeed.capture(body, 0).portable(71));
            var initial = new RollbackRosterData(captured.worldTime(), original);
            var outgoing = scene.captureRoster(initial, 50_000_000L);
            assertEquals(21, outgoing.worldTime());
            var privatePose = outgoing.players().get(player.getUUID()).values().fields().get("entity.position");
            assertNotEquals(initial.players().get(player.getUUID()).values().fields().get("entity.position"), privatePose);
            assertEquals(new net.minecraft.world.phys.Vec3(2, 10, 2), PaperRollbackNativeValues.decode(net.minecraft.world.phys.Vec3.class, privatePose));
            checkpoint.restore();
            assertNull(scene.world().world().getBlockEntity(chestPos));
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
    @Test void privateNeighborShapesAndVanillaLootRewindTogether() throws Exception {
        onTickThread(() -> {
            var configField = io.papermc.paper.configuration.GlobalConfiguration.class.getDeclaredField("instance");
            configField.setAccessible(true);
            var previous = configField.get(null);
            var config = new io.papermc.paper.configuration.GlobalConfiguration();
            config.misc = new org.objenesis.ObjenesisStd().newInstance(io.papermc.paper.configuration.GlobalConfiguration.Misc.class);
            configField.set(null, config);
            try {
                var captured = PaperRollbackRosterSeed.capture(source(), 0);
                var scene = scene(seed(), captured, captured.participants());
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
                assertNull(Bukkit.getServer());
            } finally { configField.set(null, previous); }
            return null;
        });
    }
    @Test void realBlockAndFluidQueuesDeduplicateAndRewind() throws Exception {
        onTickThread(() -> {
            var captured = PaperRollbackRosterSeed.capture(source(), 0);
            var scene = scene(seed(), captured, captured.participants());
            var queues = scene.ticks();
            var logical = scene.logical().getBlockAt(0, 4, 0);
            logical.setType(Material.STONE, false);
            var pos = new net.minecraft.core.BlockPos(0, 4, 0);
            var world = scene.world().world();
            world.scheduleTick(pos, net.minecraft.world.level.block.Blocks.STONE, 2);
            world.scheduleTick(pos, net.minecraft.world.level.block.Blocks.STONE, 2);
            world.scheduleTick(pos, net.minecraft.world.level.material.Fluids.EMPTY, 1);
            var bytes = queues.captureRollbackState().encode();
            assertEquals(2, RollbackScheduledTicks.decode(bytes).entries().size());
            assertEquals(3, queues.captureRollbackState().nextOrder());
            var snapshot = new RollbackStateGraph(value -> Proxy.isProxyClass(value.getClass()), field -> true, 600_000)
                    .capture(List.of(scene), List.of());
            scene.services().advanceTick(1);
            assertEquals(1, queues.captureRollbackState().entries().size());
            scene.services().advanceTick(2);
            assertTrue(queues.captureRollbackState().entries().isEmpty());
            snapshot.restore();
            assertArrayEquals(bytes, queues.captureRollbackState().encode());
            scene.services().advanceTick(1);
            assertEquals(RollbackScheduledTicks.Kind.BLOCK, queues.captureRollbackState().entries().getFirst().kind());
            var lamp = new BlockData(Material.REDSTONE_LAMP); lamp.setExactState("minecraft:redstone_lamp[lit=true]");
            logical.setBlockData(lamp, false);
            world.scheduleTick(pos, net.minecraft.world.level.block.Blocks.REDSTONE_LAMP, 1);
            scene.services().advanceTick(2);
            assertTrue(logical.getBlockData().getExactState().contains("lit=false"));
            logical.setType(Material.WATER, false);
            world.scheduleTick(pos, net.minecraft.world.level.material.Fluids.WATER, 1);
            scene.services().advanceTick(3);
            assertEquals(Material.WATER, scene.logical().getBlockAt(0, 3, 0).getType());
            var flowing = new BlockData(Material.WATER); flowing.setExactState("minecraft:water[level=1]");
            for (int x : new int[]{8, 11}) {
                scene.logical().getBlockAt(x, 10, 0).setBlockData(flowing, false);
                world.scheduleTick(new net.minecraft.core.BlockPos(x, 10, 0), net.minecraft.world.level.material.Fluids.FLOWING_WATER, 1);
            }
            scene.services().advanceTick(4);
            assertEquals(Material.WATER, scene.logical().getBlockAt(8, 10, 0).getType());
            assertEquals(Material.AIR, scene.logical().getBlockAt(11, 10, 0).getType());
            return null;
        });
    }
    private static List<ServerPlayer> source() {
        var combat = new PaperRollbackDamageNativeTest.Combat() {
            @Override public net.minecraft.world.flag.FeatureFlagSet terrainFeatures() { return FeatureFlags.DEFAULT_FLAGS; }
        };
        var world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), combat, 4);
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
        return scene(seed, players, participants, new Events());
    }
    private static PaperRollbackNativeScene scene(RollbackWorldSeed seed, PaperRollbackRosterSeed players, Set<UUID> participants, Events events) {
        return scene(seed, players, participants, events, unused(RollbackWorld.Actions.class));
    }
    private static PaperRollbackNativeScene scene(RollbackWorldSeed seed, PaperRollbackRosterSeed players, Set<UUID> participants, Events events, RollbackWorld.Actions actions) {
        var tiles = new PaperRollbackBlockEntities(PaperRollbackEnvironmentNativeTest.registries());
        var items = new RollbackNativeItems<>(new PaperRollbackItems(PaperRollbackEnvironmentNativeTest.registries()));
        var blocks = new PaperRollbackBlockRules(items);
        var bindings = new PaperRollbackNativeScene.Bindings(blocks, items, actions, tiles, events);
        var services = new TreeMap<UUID, PaperRollbackRosterSeed.Services>();
        players.participants().forEach(id -> services.put(id, new PaperRollbackRosterSeed.Services(id.getLeastSignificantBits(), 300_000,
                PaperRollbackStatistics.Seed.fresh(), PaperRollbackAdvancements.Seed.empty())));
        return new PaperRollbackNativeScene(seed, players, participants, PaperRollbackEnvironmentNativeTest.registries(),
                FeatureFlags.DEFAULT_FLAGS, bindings, services, 0, 100, 128, 9);
    }
    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> { throw new AssertionError("Unexpected " + method); }));
    }
    private static class Events implements PaperRollbackWorldServices.Events<Void> {
        @Override public void event(org.bukkit.event.Event event) {
            if (event instanceof org.bukkit.event.block.BlockFromToEvent) return;
            if (event instanceof org.bukkit.event.block.FluidLevelChangeEvent level) {
                level.setCancelled(level.getBlock().getX() == 8);
                return;
            }
            if (event instanceof org.bukkit.event.block.BlockRedstoneEvent redstone) {
                assertEquals(15, redstone.getOldCurrent());
                assertEquals(0, redstone.getNewCurrent());
                return;
            }
            throw new AssertionError("No gameplay event expected: " + event);
        }
        @Override public void gameEvent(Object event, Object pos, Object context) { throw new AssertionError("No gameplay event expected"); }
        @Override public void output(PaperRollbackCombatAccess.Output output) { throw new AssertionError("No output expected"); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
}
