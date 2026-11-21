package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementInput;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateGraph;
import com.projectkorra.projectkorra.prediction.rollback.RollbackVerticalInput;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.world.LevelLoadingScreen;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.tutorial.TutorialManager;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objenesis.ObjenesisStd;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackVerticalInputTest {
    private static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void sharedVerticalWritesMatchTheActualClientMovementBodyAcross384Cases() throws Throwable {
        var oracle = new Oracle(); int cases = 0;
        var reference = new StringBuilder("# Native ClientPlayerEntity.tickMovement vertical input, Minecraft 1.21.11; flags speed initialY finalXYZ writeCount.\n");
        for (int flags = 0; flags < 32; flags++) for (float speed : new float[]{0, .05F, .15F, -.1F}) for (double y : new double[]{0, -.3, .75}) {
            var shared = new Body(flags, speed, y); var nativeBody = new Body(flags, speed, y);
            oracle.run(nativeBody); RollbackVerticalInput.apply(shared);
            assertEquals(nativeBody.writes, shared.writes); assertEquals(nativeBody.velocity, shared.velocity); cases++;
            var result = nativeBody.velocity;
            reference.append(flags).append(' ').append(Float.toHexString(speed)).append(' ').append(Double.toHexString(y)).append(' ')
                    .append(Double.toHexString(result.x)).append(' ').append(Double.toHexString(result.y)).append(' ').append(Double.toHexString(result.z))
                    .append(' ').append(nativeBody.writes.size()).append('\n');
        }
        assertEquals(384, cases);
        try (var input = getClass().getResourceAsStream("/rollback/client-vertical-input.txt")) {
            assertNotNull(input, "NATIVE_VERTICAL_REFERENCE\n" + reference);
            assertEquals(new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n"), reference.toString());
        }
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 1})
    void fullNativeFlightTickUsesVerticalKeysAndRewindsPermissionSpeedAndMomentum(int direction) throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = scene.roster.players().get(A); var player = state.ownedPlayer();
        scene.roster.players().get(B).ownedPlayer().setPosition(3, 1, 3);
        player.setPosition(.5, 2, .5); player.setVelocity(Vec3d.ZERO); player.setOnGround(false); player.setPose(EntityPose.STANDING);
        player.getAbilities().allowFlying = true; player.getAbilities().flying = true; player.getAbilities().setFlySpeed(.15F);
        player.setSneaking(direction < 0);
        var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        step(state, direction > 0); var position = player.getEntityPos(); var velocity = player.getVelocity();
        assertEquals(Integer.signum(direction), Double.compare(position.y, 2));
        saved.restore(); player.getAbilities().allowFlying = false; player.getAbilities().flying = false; player.getAbilities().setFlySpeed(.01F);
        step(state, direction > 0); assertNotEquals(velocity, player.getVelocity());
        saved.restore(); step(state, direction > 0);
        assertTrue(player.getAbilities().allowFlying); assertTrue(player.getAbilities().flying); assertEquals(.15F, player.getAbilities().getFlySpeed());
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity());
    }
    private static void step(FabricRollbackNativePlayerState state, boolean jump) {
        state.movementInput(new RollbackMovementInput(0, 0, jump, 0, 0)); state.tick();
    }
    @Test void nativeWaterContactAppliesSneakDescentAndRewindsItsMomentum() throws Exception {
        var scene = new FabricRollbackPrivateQueriesTest.Scene(); var state = scene.roster.players().get(A); var player = state.ownedPlayer();
        scene.roster.players().get(B).ownedPlayer().setPosition(3, 1, 3);
        for (int y = 1; y <= 3; y++) scene.block(new BlockPos(0, y, 0), Material.WATER, "minecraft:water[level=0]");
        player.setPosition(.5, 2, .5); player.setVelocity(Vec3d.ZERO); player.setOnGround(false); player.setPose(EntityPose.STANDING);
        player.getAbilities().allowFlying = false; player.getAbilities().flying = false; player.setSneaking(false); player.setSprinting(false);
        var saved = new RollbackStateGraph(value -> false, field -> true, 800_000).capture(List.of(scene.queries), List.of());
        step(state, false); assertTrue(player.isTouchingWater()); double ordinaryY = player.getY();
        saved.restore(); player.setSneaking(true); step(state, false);
        var position = player.getEntityPos(); var velocity = player.getVelocity(); assertTrue(position.y < ordinaryY);
        saved.restore(); player.setSneaking(true); step(state, false);
        assertEquals(position, player.getEntityPos()); assertEquals(velocity, player.getVelocity());
    }
    private static final class Body implements RollbackVerticalInput.Body {
        final int flags; final float speed; Vec3d velocity; final List<Vec3d> writes = new ArrayList<>();
        Body(int flags, float speed, double y) { this.flags = flags; this.speed = speed; velocity = new Vec3d(-0D, y, .25); }
        @Override public boolean touchingWater() { return (flags & 1) != 0; }
        @Override public boolean affectedByFluids() { return (flags & 2) != 0; }
        @Override public boolean sneaking() { return (flags & 4) != 0; }
        @Override public boolean jumping() { return (flags & 8) != 0; }
        @Override public boolean flying() { return (flags & 16) != 0; }
        @Override public float flySpeed() { return speed; }
        @Override public void addVertical(double amount) { set(velocity.add(0, amount, 0)); }
        void set(Vec3d next) { velocity = next; writes.add(next); }
    }
    /** Execute the real client body until its mount/base-travel boundary, substituting only isolated native reads/services. */
    private static final class Oracle {
        private static final RuntimeException END = new RuntimeException("vertical input complete");
        Body body;
        final PlayerAbilities abilities = new PlayerAbilities();
        final MinecraftClient client;
        final ClientPlayerEntity player;
        final MethodHandle tick;
        Oracle() throws Exception {
            var allocator = new ObjenesisStd(false);
            client = RollbackNativeQueryShell.create(MinecraftClient.class)
                    .constant(MinecraftClient::getTutorialManager, allocator.newInstance(TutorialManager.class)).instance();
            client.currentScreen = allocator.newInstance(LevelLoadingScreen.class);
            player = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                    .constant(PlayerEntity::getAbilities, abilities)
                    .constant(PlayerEntity::isSwimming, false).constant(PlayerEntity::hasVehicle, false)
                    .query(PlayerEntity::isSneaking, false, args -> body.sneaking()).constant(PlayerEntity::isSleeping, false)
                    .constant(PlayerEntity::isUsingItem, false).constant(PlayerEntity::isSprinting, false)
                    .constant(PlayerEntity::isGliding, false)
                    .query(PlayerEntity::isTouchingWater, false, args -> body.touchingWater())
                    .query(PlayerEntity::shouldSwimInFluids, false, args -> body.affectedByFluids())
                    .query(value -> value.isSubmergedIn(FluidTags.WATER), false, args -> false)
                    .query(PlayerEntity::getVelocity, Vec3d.ZERO, args -> body.velocity)
                    .outputQuery(value -> value.setVelocity(Vec3d.ZERO), args -> body.set((Vec3d) args[0]))
                    .nativeAction(PlayerEntity::knockDownwards, args -> { })
                    .query(ClientPlayerEntity::getJumpingMount, null, args -> { throw END; }).instance();
            player.noClip = true; player.input = new Input();
            var method = ClientPlayerEntity.class.getDeclaredMethod("tickMovement");
            var getter = MethodHandles.dropArguments(MethodHandles.constant(MinecraftClient.class, client), 0, ClientPlayerEntity.class);
            tick = new RollbackNativeMethods().copy(method).read(ClientPlayerEntity.class.getDeclaredField("client"), getter)
                    .replace(PlayerEntity.class.getDeclaredMethod("canChangeIntoPose", EntityPose.class),
                            MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, PlayerEntity.class, EntityPose.class))
                    .replace(ClientPlayerEntity.class.getDeclaredMethod("isCamera"),
                            MethodHandles.dropArguments(MethodHandles.constant(boolean.class, true), 0, ClientPlayerEntity.class))
                    .build().get(method);
        }
        void run(Body next) throws Throwable {
            body = next; abilities.flying = body.flying(); abilities.allowFlying = false; abilities.setFlySpeed(body.speed);
            player.input.playerInput = new PlayerInput(false, false, false, false, body.jumping(), body.sneaking(), false);
            try { tick.invokeExact(player); fail("The oracle must stop before base travel"); }
            catch (RuntimeException failure) { assertSame(END, failure); }
        }
    }
}
