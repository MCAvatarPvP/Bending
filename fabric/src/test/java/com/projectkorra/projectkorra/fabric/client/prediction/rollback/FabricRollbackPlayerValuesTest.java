package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerValuesTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void actualPaperValueCaptureAppliesWithIdenticalBytesAndRewindsNativeTransientState() throws Exception {
        byte[] bytes = fixture();
        var values = RollbackPlayerValues.decode(bytes);
        var prepared = FabricRollbackPlayerValues.prepare(values);
        assertEquals(32, prepared.retained().fields().size());
        assertEquals(55, prepared.retained().fields().get("entity.totalEntityAge").value());
        var state = player();
        prepared.apply(state);
        assertArrayEquals(bytes, prepared.capture(state).encode());
        state.use(player -> {
            assertEquals(new Vec3d(.5, 1, .5), new Vec3d(player.getX(), player.getY(), player.getZ()));
            assertEquals(new Vec3d(.05, 0, .08), player.getVelocity());
            assertEquals(123, player.age); assertEquals(7, player.timeUntilRegen); assertEquals(6, player.hurtTime);
            assertTrue(player.knockedBack); assertTrue(player.velocityDirty); assertEquals(Hand.OFF_HAND, player.preferredHand);
            assertEquals(8, player.experienceLevel); assertEquals(.4f, player.experienceProgress);
            assertEquals(13, player.getHungerManager().getFoodLevel());
            return null;
        });
        var saved = new RollbackStateGraph(value -> false, field -> true, 300_000).capture(List.of(state), List.of());
        state.use(player -> {
            player.setVelocity(4, 5, 6); player.age = 200; player.timeUntilRegen = 0; player.knockedBack = false;
            return null;
        });
        assertNotEquals(values.fields(), prepared.capture(state).fields());
        saved.restore();
        assertArrayEquals(bytes, prepared.capture(state).encode());
        var simulation = new RollbackSimulation<Boolean, Boolean, Boolean>() {
            @Override public Boolean snapshot() { return true; }
            @Override public void restore(Boolean ignored) { }
            @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Boolean> effects) {
                assertThrows(IllegalStateException.class, () -> prepared.apply(state));
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerValues.prepare(values));
            }
        };
        new RollbackEngine<>(simulation, Map.of(new UUID(0, 451), false), new RollbackEngine.Limits(1, 1, 4, 50_000_000), 0).advance();
    }

    @Test void missingForeignAndWrongTypedFieldsAreRejectedBeforeNativeApplication() throws Exception {
        var values = RollbackPlayerValues.decode(fixture());
        var missing = new TreeMap<>(values.fields()); missing.remove("walk.speed");
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerValues.prepare(new RollbackPlayerValues(missing)));
        var foreign = new TreeMap<>(values.fields()); foreign.put("unregistered.member", new Cell(Kind.BOOLEAN, false));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerValues.prepare(new RollbackPlayerValues(foreign)));
        var wrong = new TreeMap<>(values.fields()); wrong.put("entity.position", new Cell(Kind.ENUM, "STANDING"));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerValues.prepare(new RollbackPlayerValues(wrong)));
        var nullPosition = new TreeMap<>(values.fields()); nullPosition.put("entity.position", new Cell(Kind.VECTOR, null));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerValues.prepare(new RollbackPlayerValues(nullPosition)));
        var wrongEnum = new TreeMap<>(values.fields()); wrongEnum.put("living.swingingArm", new Cell(Kind.ENUM, "NOT_A_HAND"));
        var invalidEnum = new RollbackPlayerValues(wrongEnum);
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerValues.prepare(invalidEnum));
        var retained = new TreeMap<>(values.fields()); retained.put("entity.totalEntityAge", new Cell(Kind.LONG, 55L));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerValues.prepare(new RollbackPlayerValues(retained)));
    }

    private byte[] fixture() throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-values.base64"))) {
            return Base64.getMimeDecoder().decode(resource.readAllBytes());
        }
    }
    private static FabricRollbackNativePlayerState player() {
        var world = new FabricRollbackWorldAccess(new FabricRollbackWorldAccessTest.Queries());
        return new FabricRollbackNativePlayerState(new Player(world.world()), world, 300_000);
    }
    private static final class Player extends PlayerEntity {
        Player(World world) { super(world, new GameProfile(new UUID(0, 451), "imported")); }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
    }
}
