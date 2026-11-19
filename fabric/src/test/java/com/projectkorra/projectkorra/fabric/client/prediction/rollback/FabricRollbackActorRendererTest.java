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
        var liveWorld = RollbackNativeQueryShell.create(ClientWorld.class)
                .constant(ClientWorld::getEnabledFeatures, FeatureFlags.VANILLA_FEATURES)
                .query(world -> world.getEntity(id), null, ignored -> null)
                .query(world -> world.getEntityById(0), null, ignored -> null)
                .constant(ClientWorld::getRegistryManager, FabricRollbackTestRegistry.simulationRegistries())
                .instance();
        var privateWorld = FabricRollbackPlayerRendererTest.roster().world().world();
        var arrow = EntityType.ARROW.create(privateWorld, SpawnReason.TRIGGERED);
        assertNotNull(arrow);
        arrow.setUuid(id);
        arrow.setId(-41);
        arrow.refreshPositionAndAngles(1, 10, 20, 30, 35);
        var actors = new AtomicReference<List<Entity>>(List.of(arrow));
        var renderer = new FabricRollbackActorRenderer(liveWorld, actors::get);
        renderer.publish(1);
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
        renderer.publish(1);
        assertEquals(List.of(model), list(renderer));
        assertEquals(arrow.getEntityPos(), model.getEntityPos());
        actors.set(List.of());
        renderer.publish(2);
        assertTrue(list(renderer).isEmpty());
        assertFalse(renderer.owns(model));
    }

    private static List<Entity> list(FabricRollbackActorRenderer renderer) {
        var entries = new ArrayList<Entity>();
        renderer.append(List.of()).forEach(entries::add);
        return entries;
    }
}
