package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.world.EntityView;
import net.minecraft.world.GameMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackEntityCollisionsTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    /** Same contacts are checked against the actual EntityGetter in PaperRollbackEntityCollisionsNativeTest. */
    @Test void privateHardCollisionQueryMatchesPaperReferenceAtGrazingAndFlatBoxes() {
        var world = new FabricRollbackWorldAccess(new FabricRollbackWorldAccessTest.Queries());
        var body = new PlayerEntity(world.world(), new GameProfile(new UUID(0, 7), "collider")) {
            @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
            @Override public boolean isCollidable(Entity entity) { return true; }
        };
        var entities = new EntityView() {
            @Override public List<Entity> getOtherEntities(Entity except, Box bounds, Predicate<? super Entity> predicate) {
                return body != except && body.getBoundingBox().intersects(bounds) && predicate.test(body) ? List.of(body) : List.of();
            }
            @Override public <T extends Entity> List<T> getEntitiesByType(TypeFilter<Entity, T> filter, Box bounds, Predicate<? super T> predicate) { throw new AssertionError("Unused typed query"); }
            @Override public List<? extends PlayerEntity> getPlayers() { return List.of(body); }
        };
        var query = new Box(0, 0, 0, 1, 1, 1);
        for (double overlap : new double[]{-2e-7, -1e-7, -5e-8, 0, 5e-8, 1e-7, 2e-7}) {
            body.setBoundingBox(new Box(1 - overlap, 0, 0, 2, 1, 1));
            assertEquals(overlap > 1e-7 ? 1 : 0, FabricRollbackEntityCollisions.collisions(entities, null, query).size());
        }
        body.setBoundingBox(query);
        assertTrue(FabricRollbackEntityCollisions.collisions(entities, null, new Box(.5, 0, 0, .5, 1, 1)).isEmpty());
        assertTrue(FabricRollbackEntityCollisions.collisions(entities, body, query).isEmpty());
    }
}
