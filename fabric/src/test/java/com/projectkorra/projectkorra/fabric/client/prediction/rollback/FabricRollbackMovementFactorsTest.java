package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementFactors;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackEngine;
import com.projectkorra.projectkorra.prediction.rollback.RollbackSimulation;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStep;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UseEffectsComponent;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec2f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackMovementFactorsTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void sharedFactorsMatchTheActualNativeClientFormulaAndPublishItsCrossLoaderReference() throws Exception {
        var oracle = new Oracle();
        float diagonal = (float) (1 / Math.sqrt(2));
        float[][] axes = {{0, 0}, {1, 0}, {0, -1}, {diagonal, diagonal}, {-.4F, .7F}, {1, 1}, {1e-10F, -1e-10F}, {-0F, 0}};
        var result = new StringBuilder("# Native ClientPlayerEntity.applyMovementSpeedFactors, Minecraft 1.21.11; hexadecimal float inputs/results.\n");
        for (var input : axes) for (int flags = 0; flags < 8; flags++) for (float item : new float[]{0, .2F, 1}) for (float sneak : new float[]{0, .3F, 1}) {
            boolean using = (flags & 1) != 0, mounted = (flags & 2) != 0, slow = (flags & 4) != 0;
            var actual = oracle.apply(input[0], input[1], using, mounted, slow, item, sneak);
            var shared = RollbackMovementFactors.apply(input[0], input[1], using && !mounted ? item : 1, slow ? sneak : 1);
            assertEquals(Float.floatToIntBits(actual.x), Float.floatToIntBits(shared.strafe()));
            assertEquals(Float.floatToIntBits(actual.y), Float.floatToIntBits(shared.forward()));
            result.append(Float.toHexString(input[0])).append(' ').append(Float.toHexString(input[1])).append(' ').append(flags).append(' ')
                    .append(Float.toHexString(item)).append(' ').append(Float.toHexString(sneak)).append(' ')
                    .append(Float.toHexString(actual.x)).append(' ').append(Float.toHexString(actual.y)).append('\n');
        }
        try (var reference = getClass().getResourceAsStream("/rollback/client-movement-factors.txt")) {
            assertNotNull(reference, "NATIVE_MOVEMENT_REFERENCE\n" + result);
            assertEquals(new String(reference.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), result.toString());
        }
    }

    @Test void ownedNativeTickReadsItemAndPosePolicyOnceAndRewindsMovementWithThePlayer() throws Exception {
        var state = roster().players().get(A); var player = (FabricRollbackSimulatedPlayer) state.ownedPlayer(); var oracle = new Oracle();
        player.setPosition(.5, 1, .5); player.setOnGround(true); player.setSneaking(true);
        player.setPose(EntityPose.CROUCHING); player.getAttributeInstance(EntityAttributes.SNEAKING_SPEED).setBaseValue(.45);
        state.use(value -> {
            var apple = new ItemStack(Items.APPLE, 4); apple.set(DataComponentTypes.USE_EFFECTS, new UseEffectsComponent(false, true, .35F));
            value.setStackInHand(Hand.MAIN_HAND, apple); value.setCurrentHand(Hand.MAIN_HAND); return null;
        });
        var checkpoint = state.captureRollbackState();
        var expected = oracle.apply(.6F, .8F, true, false, true, .35F, .45F);
        step(state);
        assertEquals(expected.x, player.sidewaysSpeed); assertEquals(expected.y, player.forwardSpeed);
        var position = player.getEntityPos(); var velocity = player.getVelocity(); int age = player.age;
        state.restoreRollbackState(checkpoint);
        state.use(value -> { value.clearActiveItem(); value.setSneaking(false); value.setPose(EntityPose.STANDING); return null; });
        step(state);
        var full = oracle.apply(.6F, .8F, false, false, false, 1, 1);
        assertEquals(full.x, player.sidewaysSpeed); assertEquals(full.y, player.forwardSpeed); assertTrue(full.y > expected.y);
        assertTrue(player.getEntityPos().squaredDistanceTo(.5, 1, .5) > position.squaredDistanceTo(.5, 1, .5));
        state.restoreRollbackState(checkpoint);
        step(state);
        assertEquals(expected.x, player.sidewaysSpeed); assertEquals(expected.y, player.forwardSpeed);
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity()); assertEquals(age, player.age);
        assertEquals(4, player.getActiveItem().getCount());
        state.use(value -> { value.clearActiveItem(); value.setPose(EntityPose.SWIMMING); return null; });
        assertTrue(player.isCrawling());
        state.movementInput(new RollbackMovementInput(.6F, .8F, false, 0, 0));
        state.use(value -> { player.tickMovementInput(); return null; });
        var crawl = oracle.apply(.6F, .8F, false, false, true, 1, .45F);
        assertEquals(crawl.x, player.sidewaysSpeed); assertEquals(crawl.y, player.forwardSpeed);
    }

    private static void step(FabricRollbackNativePlayerState state) {
        var simulation = new RollbackSimulation<FabricRollbackNativePlayerState.Checkpoint, Boolean, Void>() {
            @Override public FabricRollbackNativePlayerState.Checkpoint snapshot() { return state.captureRollbackState(); }
            @Override public void restore(FabricRollbackNativePlayerState.Checkpoint saved) { state.restoreRollbackState(saved); }
            @Override public Boolean predict(UUID participant, Boolean previous) { return previous; }
            @Override public void step(long tick, Map<UUID, Boolean> inputs, RollbackStep<Void> effects) {
                state.movementInput(new RollbackMovementInput(.6F, .8F, false, 0, 0)); state.tick();
            }
        };
        new RollbackEngine<>(simulation, Map.of(A, false), new RollbackEngine.Limits(3, 1, 10, 50_000_000), 1000).advance();
    }

    /** Native client method is the oracle, rather than a second copy of the shared formula. */
    private static final class Oracle {
        boolean using, mounted, slow; double sneak;
        final ClientPlayerEntity player = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                .query(PlayerEntity::isUsingItem, false, args -> using)
                .query(PlayerEntity::hasVehicle, false, args -> mounted)
                .query(ClientPlayerEntity::shouldSlowDown, false, args -> slow)
                .query(value -> value.getAttributeValue(EntityAttributes.SNEAKING_SPEED), 0D, args -> {
                    assertEquals(EntityAttributes.SNEAKING_SPEED, args[0]); return sneak;
                }).instance();
        Vec2f apply(float x, float z, boolean using, boolean mounted, boolean slow, float itemSpeed, float sneakSpeed) {
            this.using = using; this.mounted = mounted; this.slow = slow; sneak = sneakSpeed;
            player.activeItemStack = new ItemStack(Items.APPLE);
            player.activeItemStack.set(DataComponentTypes.USE_EFFECTS, new UseEffectsComponent(false, true, itemSpeed));
            return player.applyMovementSpeedFactors(new Vec2f(x, z));
        }
    }
}
