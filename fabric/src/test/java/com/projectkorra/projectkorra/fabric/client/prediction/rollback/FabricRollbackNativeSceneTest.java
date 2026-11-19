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

    private RollbackBootstrapData bootstrapData() throws Exception { return bootstrapData(seed()); }

    private RollbackBootstrapData bootstrapData(RollbackWorldSeed world) throws Exception {
        RollbackBootstrapData reference;
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/duel-bootstrap.base64"))) {
            reference = RollbackBootstrapData.decode(Base64.getMimeDecoder().decode(in.readAllBytes()), "12".repeat(32),
                    new FabricRollbackTerrainTransfer(), new RollbackTerrainCodec.Limits(4_096, 32, 1_048_576, 1_048_576, 65_536));
        }
        // The historical bootstrap fixture predates the captured lighting seed.
        return new RollbackBootstrapData(reference.session(), reference.challenge(), reference.match(), reference.round(),
                reference.epochMillis(), reference.epochNanos(), reference.definitions(), reference.sides(), world, reference.roster(),
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void combatRepairRetainsExactActiveAndRemovedProjectileAliasesWithoutPublishingRetiredActors(boolean retired) throws Exception {
        var bootstrap = bootstrapData();
        var duel = FabricRollbackNativeScene.importDuel(bootstrap, FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var roster = duel.scene().roster();
        var participants = duel.views().players().values().stream().toList();
        var caster = participants.getFirst();
        var shot = caster.launchProjectile(com.projectkorra.projectkorra.platform.mc.entity.Snowball.class);
        var original = duel.actors().nativeEntity(shot);
        var shooter = duel.actors().nativeEntity(caster);
        var world = roster.world().world();
        var source = world.getDamageSources().thrown(original, shooter);
        var bodies = roster.players().values().stream().toList();
        for (var state : bodies) ((com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess)state.ownedPlayer()).rollback$lastDamage(source);
        var actor = (FabricRollbackActor)duel.actors().registry().get(shot.getUniqueId());
        var flying = actor.captureRollbackState();
        original.age = 22;
        if (retired) original.discard();
        var frame = RollbackRosterData.decode(roster.capture(bootstrap.epochNanos()).encode());
        var active = duel.actors().registry().exportState(frame.combatActors());
        assertEquals(1, frame.combatActors().actors().size());
        assertEquals(retired ? 0 : 1, active.actors().size());
        var target = bodies.getLast();
        var onHit = target.captureRollbackState();
        flying.restore();
        original.age = 3;
        target.restoreRollbackState(onHit);
        assertEquals(retired, original.isRemoved());
        if (retired) assertEquals(22, original.age);
        roster.importConfirmed(frame, bootstrap.epochNanos(), () -> duel.actors().importState(active));
        var repaired = ((com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess)bodies.getFirst().ownedPlayer()).rollback$lastDamage().getSource();
        assertNotSame(original, repaired);
        assertEquals(22, repaired.age);
        for (var state : bodies) {
            var repairedSource = ((com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess)state.ownedPlayer()).rollback$lastDamage();
            assertSame(repaired, repairedSource.getSource()); assertSame(shooter, repairedSource.getAttacker());
            state.captureRollbackState();
        }
        assertEquals(retired, repaired.isRemoved());
        assertSame(retired ? null : repaired, world.getEntityAnyDimension(shot.getUniqueId()));
        assertEquals(retired ? 0 : 1, duel.actors().active().size());
        assertEquals(frame.combatActors(), roster.capture(bootstrap.epochNanos()).combatActors());
        var impostor = new net.minecraft.entity.projectile.thrown.SnowballEntity(net.minecraft.entity.EntityType.SNOWBALL, world);
        impostor.setId(repaired.getId()); impostor.setUuid(repaired.getUuid()); impostor.discard();
        ((com.projectkorra.projectkorra.fabric.mixin.client.LivingEntityRollbackHistoryAccess)target.ownedPlayer()).rollback$lastDamage(world.getDamageSources().thrown(impostor, shooter));
        assertThrows(IllegalStateException.class, target::captureRollbackState);
    }

    @Test void nativeDartTargetFilterAndPlayerDamageUseTheCorrectParticipantsScoreboard() throws Exception {
        var ids = new ArrayList<>(bootstrapData().sides().keySet());
        var shooterId = ids.get(0);
        var targetId = ids.get(1);
        var teams = new RollbackCombatTeams(Map.of(targetId, Set.of(shooterId, targetId)));
        var settings = FabricRollbackWorldServicesTest.settings().withInteractions(RollbackDuelInteractions.STANDARD.withTeams(teams));
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(seed(settings)), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var roster = duel.scene().roster();
        var shooter = roster.players().get(shooterId).ownedPlayer();
        var target = roster.players().get(targetId).ownedPlayer();
        var first = duel.views().players().get(shooterId).launchProjectile(com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
        var second = duel.views().players().get(targetId).launchProjectile(com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
        var canHit = net.minecraft.entity.projectile.PersistentProjectileEntity.class.getDeclaredMethod("canHit", net.minecraft.entity.Entity.class);
        canHit.setAccessible(true);
        FabricRollbackGliding.use(roster.world(), () -> {
            assertFalse(shooter.shouldDamagePlayer(target));
            assertTrue(target.shouldDamagePlayer(shooter));
            try {
                assertEquals(false, canHit.invoke(duel.actors().nativeEntity(first), target));
                assertEquals(true, canHit.invoke(duel.actors().nativeEntity(second), shooter));
                var viewer = duel.views().players().get(targetId);
                viewer.state().hidden(Set.of(shooterId));
                assertEquals(false, canHit.invoke(duel.actors().nativeEntity(second), shooter));
                viewer.state().hidden(Set.of());
                assertEquals(true, canHit.invoke(duel.actors().nativeEntity(second), shooter));
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            var world = (net.minecraft.server.world.ServerWorld) roster.world().world();
            assertFalse(shooter.damage(world, world.getDamageSources().playerAttack(target), 4));
            return null;
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nativeStickyBombAttachesOncePerBranchWithoutAnExtraVanillaImpact(boolean wall) throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var participants = duel.views().players().values().stream().toList();
        var caster = participants.getFirst(); var target = participants.getLast();
        caster.state().hidden(Set.of());
        duel.actors().nativeEntity(caster).setPosition(.5, 4, -2);
        duel.actors().nativeEntity(target).setPosition(.5, 4, wall ? 8 : 2);
        var world = duel.scene().logical();
        if (wall) world.getBlockAt(0, 5, 2).setType(Material.STONE, false);
        var shot = world.spawn(new com.projectkorra.projectkorra.platform.mc.Location(world, .5, 5.25, .5),
                com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet.class);
        shot.setShooter(caster);
        var bullet = duel.actors().nativeEntity(shot);
        bullet.setNoGravity(true);
        bullet.setVelocity(0, 0, 3);
        var confirmed = StickyBombImpactScenario.verify(caster, target, shot, duel.actors().registry(), duel.output(),
                () -> new FabricRollbackPhases(duel).tickWorld(1), wall);
        assertEquals(List.of("BLOCK_HONEY_BLOCK_STEP"), confirmed.stream().filter(RollbackArenaActions.SoundOutput.class::isInstance)
                .map(RollbackArenaActions.SoundOutput.class::cast).map(RollbackArenaActions.SoundOutput::sound).toList());
        assertFalse(confirmed.stream().anyMatch(effect -> effect instanceof FabricRollbackWorldAccess.ParticleOutput
                || effect instanceof FabricRollbackWorldAccess.SoundOutput));
    }

    @Test void nativeDartAndSmokescreenUseTheirOwnedArrowAndPotionCloud() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var arrow = caster.launchProjectile(com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
        var nativeArrow = (net.minecraft.entity.projectile.ArrowEntity) duel.actors().nativeEntity(arrow);
        arrow.setDamage(0);
        arrow.setCritical(true);
        arrow.setPickupStatus(com.projectkorra.projectkorra.platform.mc.entity.Arrow.PickupStatus.DISALLOWED);
        assertSame(caster, arrow.getShooter());
        assertTrue(nativeArrow.isCritical());
        assertEquals(net.minecraft.entity.projectile.PersistentProjectileEntity.PickupPermission.DISALLOWED, nativeArrow.pickupType);
        assertFalse(arrow.isInBlock());
        assertFalse(arrow.isInWaterOrBubbleColumn());

        var cloud = duel.scene().logical().spawn(caster.getEyeLocation(), com.projectkorra.projectkorra.platform.mc.entity.AreaEffectCloud.class);
        var nativeCloud = (net.minecraft.entity.AreaEffectCloudEntity) duel.actors().nativeEntity(cloud);
        var effect = com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType.BLINDNESS;
        cloud.addCustomEffect(new com.projectkorra.projectkorra.platform.mc.potion.PotionEffect(effect, 40, 2), false);
        cloud.setSource(caster);
        cloud.setDuration(80);
        cloud.setRadius(4.0F);
        cloud.setColor(com.projectkorra.projectkorra.platform.mc.Color.BLACK);
        cloud.setWaitTime(0);
        cloud.setReapplicationDelay(10);
        cloud.addCustomEffect(new com.projectkorra.projectkorra.platform.mc.potion.PotionEffect(effect, 5, 1), false);
        var access = (com.projectkorra.projectkorra.fabric.mixin.client.AreaEffectCloudEntityAccessor) nativeCloud;
        var contents = access.projectkorra$potionContents();
        assertEquals(1, contents.customEffects().size());
        assertEquals(2, contents.customEffects().getFirst().getAmplifier());
        assertEquals(0, contents.customColor().orElseThrow());
        assertEquals(80, nativeCloud.getDuration());
        assertEquals(4F, nativeCloud.getRadius());
        assertSame(duel.actors().nativeEntity(caster), nativeCloud.getOwner());
        cloud.addCustomEffect(new com.projectkorra.projectkorra.platform.mc.potion.PotionEffect(effect, 5, 1), true);
        assertEquals(1, access.projectkorra$potionContents().customEffects().getFirst().getAmplifier());
    }

    @Test void nativeAdvancementChecksTheCapturedStructurePieceInsteadOfTheWholeVillage() throws Exception {
        var pieces = new RollbackStructurePieces(List.of(new RollbackStructurePieces.Piece("minecraft:village_plains", 2, 3, 4, 5, 9, 8)));
        var settings = FabricRollbackWorldServicesTest.settings().withStructures(pieces);
        var registries = FabricRollbackTestRegistry.simulationRegistries();
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(seed(settings)), registries,
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var world = (net.minecraft.server.world.ServerWorld) duel.scene().roster().world().world();
        var village = registries.getOrThrow(net.minecraft.registry.RegistryKeys.STRUCTURE)
                .getEntry(net.minecraft.util.Identifier.ofVanilla("village_plains")).orElseThrow();
        var allowed = net.minecraft.registry.entry.RegistryEntryList.<net.minecraft.world.gen.structure.Structure>of(village);
        var predicate = net.minecraft.predicate.entity.LocationPredicate.Builder.create().structure(allowed).build();
        assertTrue(predicate.test(world, 2, 3, 4));
        assertTrue(predicate.test(world, 5, 9, 8));
        assertFalse(predicate.test(world, 6, 3, 4));
        assertTrue(world.isPosLoaded(new net.minecraft.util.math.BlockPos(-16, -64, -16)));
        assertFalse(world.isPosLoaded(new net.minecraft.util.math.BlockPos(-16, -65, -16)));
        assertFalse(world.isPosLoaded(new net.minecraft.util.math.BlockPos(4, 320, 4)));
        assertFalse(predicate.test(world, 32, 3, 4));
        assertSame(village.value(), world.getStructureAccessor().getStructureContaining(new net.minecraft.util.math.BlockPos(4, 6, 6), allowed).getStructure());
    }

    @Test void predictedSmokescreenSnowballCreatesOneCloudAcrossRepeatedBlockContacts() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var local = caster.launchProjectile(com.projectkorra.projectkorra.platform.mc.entity.Snowball.class);
        var snowball = (net.minecraft.entity.projectile.thrown.SnowballEntity) duel.actors().nativeEntity(local);
        var hits = new java.util.concurrent.atomic.AtomicInteger();
        assertTrue(com.projectkorra.projectkorra.fabric.client.prediction.entity.ClientProjectileImpact.deliver(
                snowball, net.minecraft.util.hit.HitResult.Type.ENTITY, () -> true));
        assertFalse(snowball.isRemoved());
        for (int frame = 0; frame < 40; frame++) {
            assertTrue(com.projectkorra.projectkorra.fabric.client.prediction.entity.ClientProjectileImpact.deliver(
                    snowball, net.minecraft.util.hit.HitResult.Type.BLOCK, () -> {
                        hits.incrementAndGet();
                        duel.scene().logical().spawn(caster.getEyeLocation(), com.projectkorra.projectkorra.platform.mc.entity.AreaEffectCloud.class);
                        return false;
                    }));
        }
        assertEquals(1, hits.get());
        assertTrue(snowball.isRemoved());
    }

    @Test void capturedKitRulesAllowAPlayerToMinePlanksAndKeepUntouchedArenaStone() throws Exception {
        var rules = new RollbackDuelInteractions(false, false, Set.of("OAK_PLANKS"), Set.of());
        var settings = FabricRollbackWorldServicesTest.settings().withInteractions(rules);
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(seed(settings)), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var body = duel.scene().roster().players().get(caster.getUniqueId()).ownedPlayer();
        body.getAbilities().creativeMode = false;
        body.getAbilities().allowModifyWorld = true;
        body.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_AXE));
        var blocks = new FabricRollbackDigBlocks(duel, caster, body);
        var logical = duel.scene().logical();
        var at = new RollbackBlockStore.Position(2, 10, 2);
        var block = logical.getBlockAt(at.x(), at.y(), at.z());
        block.setType(Material.STONE, false);
        blocks.destroy(at);
        assertEquals(Material.STONE, block.getType());
        block.setType(Material.OAK_PLANKS, false);
        assertTrue(blocks.mayBreak(at));
        assertTrue(blocks.delta(at) > 0);
        blocks.attack(at);
        var engine = new RollbackEngine<>(new RollbackSimulation<Integer, Integer, Object>() {
            public Integer snapshot() { return 0; }
            public void restore(Integer ignored) { }
            public Integer predict(UUID who, Integer previous) { return previous; }
            public void step(long tick, Map<UUID, Integer> inputs, RollbackStep<Object> effects) {
                try (var binding = duel.output().open(effects)) { blocks.destroy(at); }
            }
        }, Map.of(caster.getUniqueId(), 0), RollbackDuelRuntime.ENGINE, 0);
        engine.advance();
        assertEquals(Material.AIR, block.getType());
        var drop = (net.minecraft.entity.ItemEntity)duel.actors().active().getFirst();
        assertTrue(drop.getStack().isOf(net.minecraft.item.Items.OAK_PLANKS));
    }

    @Test void privateInventoryDropConsumesOnlyTheRequestedStackAndItsNativeItemHasTheKitLifetime() throws Exception {
        var rules = RollbackDuelInteractions.STANDARD.withItems(new RollbackItemRules(6000, Map.of("minecraft:diamond", 3), 2.5, false, true));
        var settings = FabricRollbackWorldServicesTest.settings().withInteractions(rules);
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(seed(settings)), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var body = duel.scene().roster().players().get(caster.getUniqueId()).ownedPlayer();
        body.setPosition(2, 3, 2);
        body.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 4));
        duel.actors().dropSelected(body, false);
        assertEquals(3, body.getMainHandStack().getCount());
        var drop = (net.minecraft.entity.ItemEntity) duel.actors().active().getFirst();
        assertEquals(1, drop.getStack().getCount());
        assertEquals(body.getEyeY() - (double).3F, drop.getY());
        assertSame(duel.actors().logical(drop), duel.scene().logical().entities().get(drop.getUuid()));
        var phases = new FabricRollbackPhases(duel);
        phases.tickWorld(1); phases.tickWorld(2);
        assertFalse(drop.isRemoved());
        phases.tickWorld(3);
        assertTrue(drop.isRemoved());
        body.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.GLASS_BOTTLE, 2));
        duel.actors().dropSelected(body, true);
        assertTrue(body.getMainHandStack().isEmpty());
        assertTrue(duel.actors().active().isEmpty());
    }

    @Test void aPassiveAirScooterPassengerStillSamplesNativePlayerControls() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var seat = duel.scene().logical().spawn(caster.getLocation(), com.projectkorra.projectkorra.platform.mc.entity.Slime.class);
        seat.setSize(1);
        assertTrue(seat.addPassenger(caster));
        var player = duel.scene().roster().players().get(caster.getUniqueId());
        var controls = new FabricRollbackClientControls(player);
        var input = new net.minecraft.client.input.Input() {
            @Override public void tick() {
                playerInput = new net.minecraft.util.PlayerInput(true, false, false, false, false, false, true);
                movementVector = new net.minecraft.util.math.Vec2f(0, 1);
            }
        };
        controls.tick(1, input, 7, false);
        assertFalse(controls.sprinting(1));
        assertSame(duel.actors().nativeEntity(seat), player.ownedPlayer().getVehicle());
        assertNull(player.ownedPlayer().getControllingVehicle());
    }

    @Test void stickyBombUsesNativeGravityOnceAndRetainsItsShooterAfterFlightReplay() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var world = duel.scene().logical();
        var origin = new com.projectkorra.projectkorra.platform.mc.Location(world, 1, 16, 1);
        var bomb = world.spawn(origin, com.projectkorra.projectkorra.platform.mc.entity.ShulkerBullet.class, shot -> {
            shot.setGravity(true);
            shot.setSilent(true);
            shot.setInvulnerable(true);
            shot.setPersistent(true);
            shot.setShooter(caster);
            shot.setVelocity(new com.projectkorra.projectkorra.platform.mc.util.Vector(0, 0, .5));
        });
        var actor = (FabricRollbackActor) duel.actors().registry().get(bomb.getUniqueId());
        var checkpoint = actor.captureRollbackState();
        var phases = new FabricRollbackPhases(duel);
        phases.tickWorld(1);
        var position = actor.owned().getEntityPos();
        assertEquals(15.96, position.y, 1e-6);
        assertEquals(1.5, position.z, 1e-6);
        checkpoint.restore();
        phases.tickWorld(1);
        assertEquals(position, actor.owned().getEntityPos());
        assertSame(duel.actors().nativeEntity(caster), ((net.minecraft.entity.projectile.ShulkerBulletEntity) actor.owned()).getOwner());
    }

    @Test void aFlyingPrivateArrowDespawnUsesTheCapturedGraceAndPickupLimit() throws Exception {
        var original = FabricRollbackWorldServicesTest.settings();
        var policy = new RollbackWorldSettings.ArrowLifetime(1, 8, 4, 2, 6);
        var settings = new RollbackWorldSettings(original.gameTime(), original.randomSeed(), original.soundSeed(), original.seaLevel(),
                original.difficulty(), original.policy(), original.rules(), original.legacyBlockData(), original.actorDespawnTime(), policy);
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(seed(settings)), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var caster = duel.views().players().values().iterator().next();
        var arrow = caster.launchProjectile(com.projectkorra.projectkorra.platform.mc.entity.Arrow.class);
        var nativeArrow = (net.minecraft.entity.projectile.ArrowEntity) duel.actors().nativeEntity(arrow);
        nativeArrow.setPosition(1, 16, 1);
        nativeArrow.setNoGravity(true);
        nativeArrow.setVelocity(0, 0, 0.1);
        nativeArrow.pickupType = net.minecraft.entity.projectile.PersistentProjectileEntity.PickupPermission.DISALLOWED;
        var phases = new FabricRollbackPhases(duel);
        phases.tickWorld(1);
        assertFalse(nativeArrow.isRemoved());
        var actor = duel.actors().registry().get(arrow.getUniqueId());
        var fluids = ((com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackContextAccess) nativeArrow).rollback$fluidHeights();
        var water = net.minecraft.registry.tag.FluidTags.WATER;
        fluids.put(water, .25);
        fluids.defaultReturnValue(-.125);
        var entries = fluids.object2DoubleEntrySet();
        var keys = fluids.keySet();
        var values = fluids.values();
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 65_536).capture(List.of(actor), List.of());
        phases.tickWorld(2);
        var afterSecondTick = nativeArrow.getEntityPos();
        fluids.clear();
        fluids.defaultReturnValue(42);
        checkpoint.restore();
        assertEquals(.25, fluids.getDouble(water));
        assertEquals(-.125, fluids.defaultReturnValue());
        assertTrue(keys.contains(water));
        assertTrue(values.contains(.25));
        assertTrue(entries.stream().anyMatch(entry -> entry.getKey().equals(water) && entry.getDoubleValue() == .25));
        phases.tickWorld(2);
        assertEquals(afterSecondTick, nativeArrow.getEntityPos());
        assertSame(duel.actors().nativeEntity(caster), nativeArrow.getOwner());
        assertFalse(nativeArrow.isRemoved());
        phases.tickWorld(3);
        assertTrue(nativeArrow.isRemoved());
        assertFalse(arrow.isValid());
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
        assertTrue(RollbackEntityBody.logicalBody(vehicle).eject());
        assertNull(nativePassenger.getVehicle());
        assertFalse(RollbackEntityBody.logicalBody(passenger).insideVehicle());
    }

    @Test void nativeFallingAcrossTheDimensionFloorReadsVoidAirAndRewindsTheSameMovement() throws Exception {
        var data = bootstrapData();
        var duel = FabricRollbackNativeScene.importDuel(data, FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var state = duel.scene().roster().players().values().iterator().next();
        var body = state.ownedPlayer();
        var world = body.getEntityWorld();
        int min = data.world().minimumY();
        var floor = new net.minecraft.util.math.BlockPos(1, min - 1, 1);
        assertTrue(world.getBlockState(floor).isOf(net.minecraft.block.Blocks.VOID_AIR));
        assertTrue(world.getFluidState(floor).isEmpty());
        assertNull(world.getBlockEntity(floor));
        assertThrows(IllegalStateException.class, () -> world.getBlockState(new net.minecraft.util.math.BlockPos(100, min, 1)));
        body.setPosition(1.5, min + .125, 1.5);
        body.setOnGround(false);
        body.setVelocity(0, -.5, 0);
        var id = body.getUuid();
        var engine = new RollbackEngine<>(new RollbackSimulation<FabricRollbackNativePlayerState.Checkpoint, Integer, Object>() {
            public FabricRollbackNativePlayerState.Checkpoint snapshot() { return state.captureRollbackState(); }
            public void restore(FabricRollbackNativePlayerState.Checkpoint checkpoint) { state.restoreRollbackState(checkpoint); }
            public Integer predict(UUID participant, Integer last) { return last; }
            public void step(long tick, Map<UUID, Integer> inputs, RollbackStep<Object> effects) {
                try (var output = duel.output().open(effects)) { state.tick(); }
            }
        }, Map.of(id, 0), RollbackDuelRuntime.ENGINE, 0);
        engine.advance();
        assertTrue(body.getY() < min);
        engine.advance();
        var expected = body.getEntityPos();
        assertTrue(expected.y < min - .5);
        engine.submit(id, 1, 1);
        assertEquals(1, engine.reconcile().replayedFrom());
        assertEquals(expected, body.getEntityPos());
    }

    @Test void nativeCrosshairUsesCurrentAimPrivateTerrainAndVisibleActorsWhileTheLiveBodiesStayFrozen() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var roster = duel.scene().roster();
        var participants = new ArrayList<>(duel.views().players().values());
        var player = participants.getFirst();
        var victim = participants.get(1);
        player.state().hidden(Set.of());
        var source = roster.players().get(player.getUniqueId());
        var nativePlayer = source.ownedPlayer();
        var privateVictim = roster.players().get(victim.getUniqueId()).ownedPlayer();
        nativePlayer.setPose(net.minecraft.entity.EntityPose.STANDING);
        nativePlayer.refreshPositionAndAngles(1.5, 4, 1.5, 90, 0);
        nativePlayer.lastX = 1.5; nativePlayer.lastY = 4; nativePlayer.lastZ = 1.5;
        privateVictim.setPosition(1.5, 4, 2.425);
        var models = new HashMap<Integer, net.minecraft.entity.Entity>();
        var visibleWorld = RollbackNativeQueryShell.create(net.minecraft.client.world.ClientWorld.class)
                .constant(net.minecraft.client.world.ClientWorld::getEnabledFeatures, FeatureFlags.VANILLA_FEATURES)
                .constant(net.minecraft.client.world.ClientWorld::getRegistryManager, FabricRollbackTestRegistry.simulationRegistries())
                .query(w -> w.getEntityById(0), null, args -> models.get((int)args[0]))
                .query(w -> w.getEntity(new UUID(0, 0)), null, args -> null)
                .instance();
        var views = new TreeMap<UUID, FabricRollbackPlayerView>();
        roster.players().forEach((id, body) -> views.put(id, body.use(p -> FabricRollbackPlayerView.capture(p, nativePlayer))));
        var visibleLocal = new FabricRollbackCameraTest.VisiblePlayer(roster, views.get(player.getUniqueId()), visibleWorld);
        var visibleVictim = new FabricRollbackCameraTest.VisiblePlayer(roster, views.get(victim.getUniqueId()), visibleWorld);
        visibleLocal.place(new net.minecraft.util.math.Vec3d(30, 20, 30));
        visibleVictim.place(new net.minecraft.util.math.Vec3d(30, 20, 41));
        visibleLocal.aim(0, 0);
        models.put(visibleVictim.getId(), visibleVictim);
        var camera = new net.minecraft.client.render.Camera();
        try (var renderer = new FabricRollbackPlayerRenderer(UUID.randomUUID(), visibleWorld, views.keySet(),
                () -> true, camera::getCameraPos, pos -> 0)) {
            renderer.bindCamera(player.getUniqueId(), camera);
            renderer.bindPicking(source, duel.actors());
            renderer.bindActors(new FabricRollbackActorRenderer(visibleWorld, duel.actors()::active));
            renderer.publish(900, 0, views);
            var hit = assertInstanceOf(net.minecraft.util.hit.EntityHitResult.class, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1));
            assertSame(visibleVictim, hit.getEntity());
            assertSame(privateVictim, FabricRollbackCrosshair.packetTarget(visibleVictim, hit));
            assertEquals(hit.getPos().subtract(privateVictim.getEntityPos()), FabricRollbackCrosshair.offset(hit, visibleVictim));
            assertEquals(90, nativePlayer.getYaw());
            assertEquals(11, visibleLocal.distanceTo(visibleVictim));
            var slab = duel.scene().logical().getBlockAt(1, 5, 2);
            slab.setBlockData(new FabricRollbackTerrainTransfer().decode(Material.OAK_SLAB, "minecraft:oak_slab[type=bottom]"), false);
            nativePlayer.setPose(net.minecraft.entity.EntityPose.CROUCHING);
            views.put(player.getUniqueId(), source.use(p -> FabricRollbackPlayerView.capture(p, p)));
            renderer.publish(901, 0, views);
            assertEquals(net.minecraft.util.hit.HitResult.Type.ENTITY, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, .1F).getType());
            assertEquals(net.minecraft.util.hit.HitResult.Type.BLOCK, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1).getType());
            nativePlayer.setPose(net.minecraft.entity.EntityPose.STANDING);
            views.put(player.getUniqueId(), source.use(p -> FabricRollbackPlayerView.capture(p, p)));
            renderer.publish(902, 0, views);
            slab.setType(Material.AIR, false);
            visibleLocal.aim(180, 0);
            assertEquals(net.minecraft.util.hit.HitResult.Type.MISS, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1).getType());
            visibleLocal.aim(0, 0);
            var terrain = duel.scene().logical();
            privateVictim.setPosition(1.5, 4, 4.4);
            var wall = terrain.getBlockAt(1, player.getEyeLocation().getBlockY(), 3);
            wall.setType(Material.STONE, false);
            var block = assertInstanceOf(net.minecraft.util.hit.BlockHitResult.class, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1));
            assertEquals(net.minecraft.util.hit.HitResult.Type.BLOCK, block.getType());
            assertEquals(3, block.getBlockPos().getZ());
            assertTrue(FabricRollbackPlayerRenderer.pickedBlock(visibleLocal, visibleLocal, block.getBlockPos()).isOf(net.minecraft.block.Blocks.STONE));
            wall.setType(Material.AIR, false);
            privateVictim.setPosition(1.5, 4, 10.5);
            assertEquals(net.minecraft.util.hit.HitResult.Type.MISS, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1).getType());
            privateVictim.setPosition(1.5, 4, 2.425);
            var stand = terrain.spawn(new com.projectkorra.projectkorra.platform.mc.Location(terrain, 1.5, 4, 4),
                    com.projectkorra.projectkorra.platform.mc.entity.ArmorStand.class);
            renderer.publish(903, 0, views);
            var attack = new RollbackInputActions.Action(1, 1, RollbackInputActions.Kind.ATTACK, -1,
                    RollbackInputActions.Hand.MAIN, new RollbackInteractionTarget.Actor(stand.getEntityId(), null));
            var edge = new RollbackPlayerInput.Edge(attack, 0, 0);
            assertFalse(RollbackInteractionTarget.allowed(player, edge, 3));
            player.state().hidden(Set.of(victim.getUniqueId()));
            var actor = assertInstanceOf(net.minecraft.util.hit.EntityHitResult.class, FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1));
            assertSame(visibleWorld, actor.getEntity().getEntityWorld());
            assertEquals(stand.getEntityId(), actor.getEntity().getId());
            assertSame(duel.actors().nativeEntity(stand), FabricRollbackCrosshair.packetTarget(actor.getEntity(), actor));
            assertTrue(RollbackInteractionTarget.allowed(player, edge, 3));
            assertNull(FabricRollbackPlayerRenderer.pick(visibleLocal, visibleVictim, 1));
        }
        assertNull(FabricRollbackPlayerRenderer.pick(visibleLocal, visibleLocal, 1));
    }

    @Test void replayResolvesTheRequestedTargetFromTheCapturedEyeAtTheInputEdge() throws Exception {
        var duel = FabricRollbackNativeScene.importDuel(bootstrapData(), FabricRollbackTestRegistry.simulationRegistries(),
                FeatureFlags.DEFAULT_ENABLED_FEATURES, 8192, 128, 300_000);
        var participants = new ArrayList<>(duel.views().players().values());
        var player = participants.getFirst();
        var victim = participants.get(1);
        player.state().hidden(Set.of());
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
    @Test void realBlockAndFluidQueuesDeduplicateAndRewind() throws Exception {
        var captured = players();
        var scene = scene(seed(), captured, captured.players().keySet());
        var queues = scene.ticks();
        var logical = scene.logical().getBlockAt(0, 4, 0);
        logical.setType(Material.STONE, false);
        var pos = new net.minecraft.util.math.BlockPos(0, 4, 0);
        var world = scene.roster().world().world();
        world.scheduleBlockTick(pos, net.minecraft.block.Blocks.STONE, 2);
        world.scheduleBlockTick(pos, net.minecraft.block.Blocks.STONE, 2);
        world.scheduleFluidTick(pos, net.minecraft.fluid.Fluids.EMPTY, 1);
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
        world.scheduleBlockTick(pos, net.minecraft.block.Blocks.REDSTONE_LAMP, 1);
        scene.services().advanceTick(2);
        assertTrue(logical.getBlockData().getExactState().contains("lit=false"));
        logical.setType(Material.WATER, false);
        world.scheduleFluidTick(pos, net.minecraft.fluid.Fluids.WATER, 1);
        scene.services().advanceTick(3);
        assertEquals(Material.WATER, scene.logical().getBlockAt(0, 3, 0).getType());
        var flowing = new BlockData(Material.WATER); flowing.setExactState("minecraft:water[level=1]");
        for (int x : new int[]{8, 11}) {
            scene.logical().getBlockAt(x, 10, 0).setBlockData(flowing, false);
            world.scheduleFluidTick(new net.minecraft.util.math.BlockPos(x, 10, 0), net.minecraft.fluid.Fluids.FLOWING_WATER, 1);
        }
        scene.services().advanceTick(4);
        assertEquals(Material.WATER, scene.logical().getBlockAt(8, 10, 0).getType());
        assertEquals(Material.AIR, scene.logical().getBlockAt(11, 10, 0).getType());
    }
    private static RollbackRosterData players() throws Exception {
        try (var in = Objects.requireNonNull(FabricRollbackNativeSceneTest.class.getResourceAsStream("/rollback/player-roster.base64"))) {
            return RollbackRosterData.decode(Base64.getMimeDecoder().decode(in.readAllBytes()));
        }
    }
    private static RollbackWorldSeed seed() throws Exception { return seed(FabricRollbackWorldServicesTest.settings()); }

    private static RollbackWorldSeed seed(RollbackWorldSettings settings) throws Exception {
        var bounds = new RollbackBlockStore.Bounds(-16, -64, -16, 32, 320, 32);
        int count = 48 * 384 * 48;
        var air = new BlockData(Material.AIR); air.setExactState("minecraft:air");
        var terrain = new RollbackTerrainSeed.Builder(bounds, count * 4 + 1024);
        terrain.append(new RollbackBlockStore.Cell(air, null, Biome.DESERT, (byte) 15, .8, .4), count);
        return new RollbackWorldSeed(new UUID(0, 1), "assembled", -64, 320, 6000, false,
                settings, new RollbackBorderData(0, 0, 29999984, .2, 5, 5, 15, 1000, null),
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
        @Override public boolean cancelFluidLevelChange(com.projectkorra.projectkorra.platform.mc.block.Block block) {
            assertInstanceOf(RollbackWorld.class, block.getWorld());
            return block.getX() == 8;
        }
        @Override public boolean cancelFluidFlow(com.projectkorra.projectkorra.platform.mc.block.Block from,
                                                  com.projectkorra.projectkorra.platform.mc.block.Block to) {
            assertSame(from.getWorld(), to.getWorld());
            assertInstanceOf(RollbackWorld.class, from.getWorld());
            return false;
        }
        @Override public void gameEvent(net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.event.GameEvent> event, net.minecraft.util.math.Vec3d pos, net.minecraft.world.event.GameEvent.Emitter emitter) { throw new AssertionError("No gameplay event expected"); }
        @Override public void waypoint(FabricRollbackWorldAccess.WaypointAction action, net.minecraft.entity.Entity entity) { throw new AssertionError("No waypoint expected"); }
        @Override public void output(FabricRollbackWorldAccess.Output output) { throw new AssertionError("No output expected"); }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
    }
}
