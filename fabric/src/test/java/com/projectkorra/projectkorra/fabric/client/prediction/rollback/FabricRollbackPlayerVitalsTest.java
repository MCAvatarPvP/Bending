package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerVitals;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerVitalsTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void actualPaperVitalsImportAndRewindTrackedPoseHiddenEffectsAndNondefaultAttributes() throws Exception {
        byte[] bytes = fixture(); var vitals = RollbackPlayerVitals.decode(bytes);
        var state = player(); FabricRollbackPlayerVitals.apply(state, vitals);
        var imported = FabricRollbackPlayerVitals.capture(state);
        assertVitalsEquals(vitals, imported);
        byte[] importedBytes = imported.encode();
        state.use(player -> {
            assertEquals(17, player.getHealth()); assertEquals(180, player.getAir()); assertEquals(EntityPose.CROUCHING, player.getPose());
            var speed = player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED);
            assertEquals(2, speed.getModifiers().size()); assertEquals(1, speed.getPersistentModifiers().size());
            assertEquals(42, player.getAttributeValue(EntityAttributes.FOLLOW_RANGE));
            var effect = player.getStatusEffect(StatusEffects.SPEED);
            assertEquals(40, effect.getDuration()); assertEquals(2, effect.getAmplifier()); assertFalse(effect.isAmbient());
            return null;
        });
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 300_000).capture(List.of(state), List.of());
        var other = player(); FabricRollbackPlayerVitals.apply(other, vitals);
        assertNotSame(state.use(player -> player.getStatusEffect(StatusEffects.SPEED)), other.use(player -> player.getStatusEffect(StatusEffects.SPEED)));
        state.use(player -> {
            player.setHealth(3); player.setAir(8);
            player.getStatusEffect(StatusEffects.SPEED).upgrade(new StatusEffectInstance(StatusEffects.SPEED, 3, 5));
            player.getAttributeInstance(EntityAttributes.MOVEMENT_SPEED).clearModifiers();
            player.getAttributeInstance(EntityAttributes.FOLLOW_RANGE).setBaseValue(99);
            return null;
        });
        assertFalse(Arrays.equals(importedBytes, FabricRollbackPlayerVitals.capture(state).encode()));
        assertVitalsEquals(vitals, FabricRollbackPlayerVitals.capture(other));
        checkpoint.restore();
        assertArrayEquals(importedBytes, FabricRollbackPlayerVitals.capture(state).encode());
    }

    @Test void wrongSerializersEffectsAndRegistriesRejectWithoutPartiallyUpdatingThePrivatePlayer() throws Exception {
        var vitals = RollbackPlayerVitals.decode(fixture()); var state = player();
        byte[] before = FabricRollbackPlayerVitals.capture(state).encode();
        var tracked = vitals.tracked(); tracked[2] = (byte) 127;
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerVitals.apply(state,
                new RollbackPlayerVitals(tracked, vitals.effects(), vitals.attributes())));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerVitals.apply(state,
                new RollbackPlayerVitals(vitals.tracked(), new byte[]{99}, vitals.attributes())));
        var unknown = new ArrayList<>(vitals.attributes());
        unknown.add(new RollbackPlayerVitals.Attribute("test:unknown", 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerVitals.apply(state,
                new RollbackPlayerVitals(vitals.tracked(), vitals.effects(), unknown)));
        assertArrayEquals(before, FabricRollbackPlayerVitals.capture(state).encode());
    }

    private static void assertVitalsEquals(RollbackPlayerVitals expected, RollbackPlayerVitals actual) throws Exception {
        assertArrayEquals(expected.tracked(), actual.tracked());
        assertEquals(expected.attributes(), actual.attributes());
        // Native compound encoders may order keys differently across Paper and Fabric.
        try (var first = new DataInputStream(new ByteArrayInputStream(expected.effects()));
             var second = new DataInputStream(new ByteArrayInputStream(actual.effects()))) {
            assertEquals(NbtIo.read(first, new NbtSizeTracker(RollbackPlayerVitals.MAXIMUM_NBT_ALLOCATION, RollbackPlayerVitals.MAXIMUM_NBT_DEPTH)),
                    NbtIo.read(second, new NbtSizeTracker(RollbackPlayerVitals.MAXIMUM_NBT_ALLOCATION, RollbackPlayerVitals.MAXIMUM_NBT_DEPTH)));
            assertEquals(0, first.available()); assertEquals(0, second.available());
        }
    }

    private byte[] fixture() throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-vitals.base64"))) {
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
