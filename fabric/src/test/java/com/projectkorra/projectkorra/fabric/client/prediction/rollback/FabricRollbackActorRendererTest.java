package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.*;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackActorRendererTest {
    @Test void arrowIsRenderedOnceAcrossReplayAndNeverEntersTheOrdinaryWorld() throws Exception {
        FabricRollbackTestRegistry.bootstrap();
        UUID id = UUID.randomUUID();
        var tracked = new AtomicReference<Entity>();
        var liveWorld = RollbackNativeQueryShell.create(ClientWorld.class)
                .constant(ClientWorld::getEnabledFeatures, FeatureFlags.VANILLA_FEATURES)
                .query(world -> world.getEntity(id), null, ignored -> tracked.get())
                .query(world -> world.getEntityById(0), null, ignored -> null)
                .constant(ClientWorld::getRegistryManager, FabricRollbackTestRegistry.simulationRegistries())
                .instance();
        var privateWorld = FabricRollbackPlayerRendererTest.roster().world().world();
        var arrow = new ArrowEntity(EntityType.ARROW, privateWorld);
        arrow.setUuid(id);
        arrow.setId(-41);
        arrow.refreshPositionAndAngles(1, 10, 20, 30, 35);
        var actors = new AtomicReference<List<Entity>>(List.of(arrow));
        var renderer = new FabricRollbackActorRenderer(liveWorld, actors::get);
        renderer.publish();
        var one = list(renderer);
        assertEquals(1, one.size());
        var model = assertInstanceOf(ArrowEntity.class, one.getFirst());
        assertNotSame(arrow, model);
        assertTrue(renderer.owns(model));
        assertFalse(renderer.owns(arrow));
        assertSame(liveWorld, model.getEntityWorld());
        assertEquals(id, model.getUuid());
        assertEquals(-41, model.getId());
        assertEquals(arrow.getEntityPos(), model.getEntityPos());
        arrow.setPosition(2, 9, 21);
        renderer.publish();
        assertEquals(List.of(model), list(renderer));
        assertEquals(arrow.getEntityPos(), model.getEntityPos());
        tracked.set(arrow); // A real spawn packet can arrive after the head was published but before rendering.
        var handoff = new ArrayList<Entity>();
        renderer.append(List.of(arrow)).forEach(handoff::add);
        assertEquals(List.of(arrow), handoff);
        actors.set(List.of());
        renderer.publish();
        assertTrue(list(renderer).isEmpty());
        assertFalse(renderer.owns(model));
    }

    @Test void correctedAndRetiredCloudsRemoveOnlyTheirOwnVanillaParticles() throws Exception {
        FabricRollbackTestRegistry.bootstrap();
        var id = UUID.randomUUID();
        var visuals = new ArrayList<net.minecraft.client.particle.Particle>();
        var allocator = new org.objenesis.ObjenesisStd();
        var particleType = net.minecraft.particle.ParticleTypes.CLOUD;
        var world = RollbackNativeQueryShell.create(ClientWorld.class)
                .constant(ClientWorld::isClient, true)
                .constant(ClientWorld::getEnabledFeatures, FeatureFlags.VANILLA_FEATURES)
                .constant(ClientWorld::getRegistryManager, FabricRollbackTestRegistry.simulationRegistries())
                .query(w -> w.getEntity(id), null, ignored -> null)
                .outputQuery(w -> w.addImportantParticleClient(particleType, 0, 0, 0, 0, 0, 0), ignored -> {
                    var handle = allocator.newInstance(net.minecraft.client.particle.CloudParticle.class);
                    visuals.add(FabricRollbackCloudParticles.created(handle));
                }).instance();
        var model = new FabricRollbackCloudParticles(world);
        var source = new AreaEffectCloudEntity(EntityType.AREA_EFFECT_CLOUD, FabricRollbackPlayerRendererTest.roster().world().world());
        source.setUuid(id);
        source.setRadius(1);
        source.setParticleType(particleType);
        var cloud = FabricRollbackCloudParticles.Frame.capture(source);
        var history = new FabricRollbackParticleHistory<net.minecraft.client.particle.Particle>(UUID.randomUUID(),
                (tick, ordinal, effect, count) -> List.of(), model::draw, net.minecraft.client.particle.Particle::markDead);
        var ordinary = FabricRollbackCloudParticles.created(allocator.newInstance(net.minecraft.client.particle.CloudParticle.class));
        history.frame(1, List.of(cloud));
        assertEquals(4, visuals.size()); // Vanilla ceil(PI * radius^2).
        history.frame(1, List.of(FabricRollbackCloudParticles.Frame.capture(source)));
        history.confirm(1, List.of(new com.projectkorra.projectkorra.prediction.rollback.RollbackEngine.Effect<>(1, 0, cloud)));
        assertEquals(4, visuals.size());
        history.frame(2, List.of(cloud));
        source.setPosition(3, 7, 2);
        history.frame(2, List.of(FabricRollbackCloudParticles.Frame.capture(source)));
        assertEquals(12, visuals.size());
        assertTrue(visuals.subList(4, 8).stream().noneMatch(net.minecraft.client.particle.Particle::isAlive));
        source.setUuid(UUID.randomUUID());
        history.frame(3, List.of(FabricRollbackCloudParticles.Frame.capture(source)));
        assertEquals(16, visuals.size());
        history.retireCloud(id);
        assertTrue(visuals.subList(4, 12).stream().noneMatch(net.minecraft.client.particle.Particle::isAlive));
        assertTrue(visuals.subList(12, 16).stream().allMatch(net.minecraft.client.particle.Particle::isAlive));
        history.clear();
        assertTrue(visuals.subList(12, 16).stream().noneMatch(net.minecraft.client.particle.Particle::isAlive));
        assertTrue(ordinary.isAlive());
        assertTrue(visuals.subList(0, 4).stream().allMatch(net.minecraft.client.particle.Particle::isAlive));
        assertTrue(visuals.subList(4, 12).stream().noneMatch(net.minecraft.client.particle.Particle::isAlive));
    }

    private static List<Entity> list(FabricRollbackActorRenderer renderer) {
        var entries = new ArrayList<Entity>();
        renderer.append(List.of()).forEach(entries::add);
        return entries;
    }
}
