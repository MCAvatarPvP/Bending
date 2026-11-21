package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackContextAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerContext;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerContextDataTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void actualPaperContextImportsNativeControlsContactsAndRewindsRetainedPolicyTogether() throws Exception {
        byte[] bytes = fixture(); var context = RollbackPlayerContext.decode(bytes); var state = player();
        FabricRollbackPlayerContextData.apply(state, context, 0);
        assertArrayEquals(bytes, FabricRollbackPlayerContextData.capture(state, 0).encode());
        var retained = FabricRollbackPlayerContextData.retained(state);
        assertEquals(-100_000_000L, retained.lastJump()); assertEquals(-200_000_000L, retained.eatingStart());
        assertEquals(context.input(), retained.input()); assertEquals(new Vector(.15, -.05, .25), retained.clientMovement());
        assertEquals(Set.of(new UUID(0, 999)), retained.collisionExemptions());
        state.use(value -> {
            var a = value.getAbilities(); assertTrue(a.invulnerable); assertTrue(a.flying); assertTrue(a.allowFlying);
            assertFalse(a.creativeMode); assertFalse(a.allowModifyWorld); assertEquals(.08F, a.getFlySpeed()); assertEquals(.12F, a.getWalkSpeed());
            assertEquals(.75, value.getFluidHeight(FluidTags.WATER)); assertTrue(value.isSubmergedIn(FluidTags.WATER));
            assertEquals(Set.of("duel", "\u6c34"), value.getCommandTags());
            assertArrayEquals(new double[]{.2, -.3, .4}, ((EntityRollbackContextAccess) value).rollback$pistons());
            return null;
        });
        var other = player(); FabricRollbackPlayerContextData.apply(other, context, 0);
        var saved = new RollbackStateGraph(value -> false, field -> true, 300_000).capture(List.of(state), List.of());
        state.use(value -> {
            value.getAbilities().flying = false; value.getAbilities().setFlySpeed(.01F); value.getCommandTags().clear();
            var access = (EntityRollbackContextAccess) value; access.rollback$fluidHeights().clear(); access.rollback$eyeFluids().clear();
            access.rollback$pistons()[0] = 0;
            return null;
        });
        state.importedContext(new FabricRollbackPlayerContextData.Retained(retained.input(), new Vector(0, 0, 0), 33, -1, Set.of()));
        assertFalse(Arrays.equals(bytes, FabricRollbackPlayerContextData.capture(state, 0).encode()));
        assertArrayEquals(bytes, FabricRollbackPlayerContextData.capture(other, 0).encode());
        saved.restore();
        assertSame(retained, FabricRollbackPlayerContextData.retained(state));
        assertArrayEquals(bytes, FabricRollbackPlayerContextData.capture(state, 0).encode());
        FabricRollbackPlayerContextData.apply(state, context, 6_000_000_000L);
        assertEquals(5_900_000_000L, FabricRollbackPlayerContextData.retained(state).lastJump());
        assertEquals(5_800_000_000L, FabricRollbackPlayerContextData.retained(state).eatingStart());
        assertArrayEquals(bytes, FabricRollbackPlayerContextData.capture(state, 6_000_000_000L).encode());
    }

    @Test void clockOverflowSentinelCollisionAndReplayRejectBeforeNativeOrRetainedStateChanges() throws Exception {
        var context = RollbackPlayerContext.decode(fixture()); var state = player();
        assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerContextData.capture(state, 0));
        FabricRollbackPlayerContextData.apply(state, context, 0);
        state.use(value -> { value.getAbilities().flying = false; return null; });
        byte[] before = FabricRollbackPlayerContextData.capture(state, 0).encode();
        assertThrows(ArithmeticException.class, () -> FabricRollbackPlayerContextData.apply(state, context, Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class, () -> FabricRollbackPlayerContextData.apply(state, context, 199_999_999));
        var simulation = new RollbackSimulation<Boolean, Boolean, Boolean>() {
            @Override public Boolean snapshot() { return true; }
            @Override public void restore(Boolean ignored) { }
            @Override public Boolean predict(UUID player, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Boolean> effects) {
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerContextData.apply(state, context, 0));
                assertThrows(IllegalStateException.class, () -> FabricRollbackPlayerContextData.capture(state, 0));
            }
        };
        new RollbackEngine<>(simulation, Map.of(new UUID(0, 451), false), new RollbackEngine.Limits(1, 1, 4, 50_000_000), 0).advance();
        assertArrayEquals(before, FabricRollbackPlayerContextData.capture(state, 0).encode());
    }
    private byte[] fixture() throws Exception {
        try (var resource = Objects.requireNonNull(getClass().getResourceAsStream("/rollback/player-context.base64"))) {
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
