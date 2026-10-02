package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import static com.projectkorra.projectkorra.fabric.client.prediction.rollback.FabricRollbackPlayerRendererTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackCameraTest {
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }

    @Test void firstPersonUsesDetachedPredictedPositionAndCorrectedEyeHeightWhilePreservingCurrentMouseAim() throws Exception {
        var roster = roster(); var source = roster.players().get(A).ownedPlayer();
        source.setPose(EntityPose.STANDING); source.setPosition(4, 3, 2);
        source.lastX = 2; source.lastY = 1; source.lastZ = 0;
        source.lastRenderX = 90; source.lastRenderY = 90; source.lastRenderZ = 90;
        var first = views(roster); float standingEye = first.get(A).cameraMotion().eyeHeight();
        var world = new Scene(); var visible = new VisiblePlayer(roster, first.get(A), world.world);
        var camera = new Camera(); visible.aim(75, -30); var livePosition = visible.getEntityPos();
        try (var owner = owner(world, camera, () -> true)) {
            owner.publish(1, 0, first);
            source.setPosition(-4, 1, -4);
            camera.update(world.world, visible, false, false, .5F);
            assertEquals(new Vec3d(3, 2.0 + standingEye, 1), camera.getCameraPos());
            assertEquals(BlockPos.ofFloored(camera.getCameraPos()), camera.getBlockPos());
            assertEquals(75, camera.getYaw()); assertEquals(-30, camera.getPitch());
            assertSame(visible, camera.getFocusedEntity()); assertEquals(livePosition, visible.getEntityPos());

            source.setPose(EntityPose.CROUCHING); source.lastX = -2; source.lastY = 1; source.lastZ = -2;
            var crouched = views(roster); float crouchedEye = crouched.get(A).cameraMotion().eyeHeight();
            assertNotEquals(standingEye, crouchedEye);
            owner.publish(2, 0, crouched);
            var interpolated = new Vec3d(-3, 1.0 + (standingEye + crouchedEye) * .5F, -3);
            camera.update(world.world, visible, false, false, .5F); assertEquals(interpolated, camera.getCameraPos());
            owner.publish(2, 0, crouched); // An acknowledgement at the same head must not restart the eye transition.
            camera.update(world.world, visible, false, false, .5F); assertEquals(interpolated, camera.getCameraPos());

            source.setPosition(2, 4, 3); source.lastX = 0; source.lastY = 2; source.lastZ = 1;
            owner.publish(2, 1, views(roster)); // A revised branch must discard the old branch's eye transition.
            visible.aim(-110, 35);
            camera.update(world.world, visible, false, false, .5F);
            assertEquals(new Vec3d(1, 3.0 + crouchedEye, 2), camera.getCameraPos());
            assertEquals(-110, camera.getYaw()); assertEquals(35, camera.getPitch());
            assertEquals(livePosition, visible.getEntityPos());
        }
        camera.update(world.world, visible, false, false, 1);
        assertEquals(livePosition, camera.getCameraPos(), "Closing ownership restores the ordinary camera path");
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void thirdPersonKeepsNativeClippingAndFrontViewAtThePredictedPosition(boolean inverse, boolean blocked) throws Exception {
        var roster = roster(); var source = roster.players().get(A).ownedPlayer();
        source.setPosition(3, 2, 1); source.lastX = 1; source.lastY = 2; source.lastZ = -1;
        source.getAttributeInstance(EntityAttributes.CAMERA_DISTANCE).setBaseValue(7);
        source.getAttributeInstance(EntityAttributes.SCALE).setBaseValue(1.25);
        var frame = views(roster); var view = frame.get(A); var world = new Scene(); var camera = new Camera();
        var visible = new VisiblePlayer(roster, view, world.world); visible.aim(30, -15);
        var reference = new VisiblePlayer(roster, view, world.world); reference.aim(30, -15);
        var eye = view.cameraMotion().previousPosition().lerp(view.motion().position(), .5).add(0, view.cameraMotion().eyeHeight(), 0);
        reference.place(eye); reference.getAttributeInstance(EntityAttributes.CAMERA_DISTANCE).setBaseValue(view.cameraMotion().distance());
        var ordinary = new Camera();
        world.hit = ray -> {
            if (!blocked) return miss(ray);
            var point = ray.getStart().add(ray.getEnd().subtract(ray.getStart()).normalize().multiply(1.5));
            return new BlockHitResult(point, Direction.UP, BlockPos.ofFloored(point), false);
        };
        try (var owner = owner(world, camera, () -> true)) {
            owner.publish(1, 0, frame);
            camera.update(world.world, visible, true, inverse, .5F);
            assertEquals(8, world.rays.size()); var predictedRays = List.copyOf(world.rays);
            for (var ray : predictedRays) assertTrue(ray.getStart().distanceTo(eye) < .18, "Clip rays must originate at the predicted eye");
            assertEquals(view.cameraMotion().distance(), predictedRays.getFirst().getStart().distanceTo(predictedRays.getFirst().getEnd()), 1e-5);
            world.rays.clear(); ordinary.update(world.world, reference, true, inverse, .5F);
            assertEquals(8, world.rays.size());
            for (int index = 0; index < 8; index++) {
                assertEquals(world.rays.get(index).getStart(), predictedRays.get(index).getStart());
                assertEquals(world.rays.get(index).getEnd(), predictedRays.get(index).getEnd());
            }
            assertEquals(ordinary.getCameraPos(), camera.getCameraPos());
            assertEquals(ordinary.getRotation(), camera.getRotation()); assertEquals(ordinary.getBlockPos(), camera.getBlockPos());
            assertEquals(inverse ? 210 : 30, camera.getYaw()); assertEquals(inverse ? 15 : -15, camera.getPitch());
            if (blocked) assertTrue(camera.getCameraPos().distanceTo(eye) < 2);
            else assertEquals(view.cameraMotion().distance(), camera.getCameraPos().distanceTo(eye), 1e-5);
            assertEquals(new Vec3d(30, 20, 30), visible.getEntityPos());
        }
    }

    @Test void spectatedRosterPlayerUsesPredictedRotationAndForeignIdentityOrConnectionUsesOrdinaryCamera() throws Exception {
        var roster = roster(); var source = roster.players().get(B).ownedPlayer();
        source.lastHeadYaw = 170; source.headYaw = -170; source.lastYaw = 20; source.setYaw(30);
        source.lastPitch = -20; source.setPitch(40);
        source.setPosition(2, 3, 4); source.lastX = 0; source.lastY = 1; source.lastZ = 2;
        var frame = views(roster); var world = new Scene(); var camera = new Camera(); var current = new AtomicBoolean(true);
        var visible = new VisiblePlayer(roster, frame.get(B), world.world); visible.aim(50, 5);
        try (var owner = owner(world, camera, current::get)) {
            owner.publish(1, 0, frame);
            camera.update(world.world, visible, false, false, .5F);
            assertEquals(180, camera.getYaw()); assertEquals(10, camera.getPitch());
            assertNotEquals(visible.getEntityPos(), camera.getCameraPos());
            var otherCamera = new Camera(); otherCamera.update(world.world, visible, false, false, .5F);
            assertEquals(visible.getEntityPos(), otherCamera.getCameraPos()); assertEquals(50, otherCamera.getYaw());
            var foreign = new Scene();
            camera.update(foreign.world, visible, false, false, .5F); assertEquals(visible.getEntityPos(), camera.getCameraPos());
            visible.setId(999); camera.update(world.world, visible, false, false, .5F); assertEquals(visible.getEntityPos(), camera.getCameraPos());
            visible.setId(frame.get(B).entityId());
            current.set(false); camera.update(world.world, visible, false, false, .5F); assertEquals(visible.getEntityPos(), camera.getCameraPos());
            assertThrows(IllegalStateException.class, () -> owner.publish(2, 0, frame));
        }
    }

    @Test void failingNativeCameraUpdateClearsItsScopeAndBindingCannotChangeAfterPublication() throws Exception {
        var roster = roster(); var frame = views(roster); var world = new Scene(); var camera = new Camera();
        var visible = new VisiblePlayer(roster, frame.get(A), world.world);
        try (var owner = owner(world, camera, () -> true)) {
            assertThrows(IllegalStateException.class, () -> owner.bindCamera(B, new Camera()));
            owner.publish(1, 0, frame);
            assertThrows(IllegalStateException.class, () -> owner.bindCamera(A, camera));
            world.hit = ray -> { throw new IllegalArgumentException("test raycast failure"); };
            assertThrows(IllegalArgumentException.class, () -> camera.update(world.world, visible, true, false, 1));
            world.hit = FabricRollbackCameraTest::miss;
            camera.update(world.world, visible, true, false, 1); // No reentrancy failure or stale view after the exception.
            owner.close(); camera.update(world.world, visible, false, false, 1);
            assertEquals(visible.getEntityPos(), camera.getCameraPos());
        }
    }

    private static FabricRollbackPlayerRenderer owner(Scene scene, Camera camera, java.util.function.BooleanSupplier current) {
        var owner = new FabricRollbackPlayerRenderer(UUID.randomUUID(), scene.world, Set.of(A, B), current, camera::getCameraPos, pos -> 0);
        owner.bindCamera(A, camera); return owner;
    }
    private static BlockHitResult miss(RaycastContext ray) { return BlockHitResult.createMissed(ray.getEnd(), Direction.UP, BlockPos.ofFloored(ray.getEnd())); }
    private static final class Scene {
        final List<RaycastContext> rays = new ArrayList<>();
        Function<RaycastContext, BlockHitResult> hit = FabricRollbackCameraTest::miss;
        final World world = RollbackNativeQueryShell.create(World.class).query(value -> value.raycast((RaycastContext) null), null, args -> {
            var ray = (RaycastContext) args[0]; rays.add(ray); return hit.apply(ray);
        }).instance();
    }
    /** Real native player fields/attributes with a test-only rendering world; no game client or graphical window. */
    private static final class VisiblePlayer extends PlayerEntity {
        private World visibleWorld;
        VisiblePlayer(FabricRollbackRoster roster, FabricRollbackPlayerView view, World visibleWorld) {
            super(roster.world().world(), new GameProfile(view.id(), "CameraTest"));
            this.visibleWorld = visibleWorld; setId(view.entityId()); place(new Vec3d(30, 20, 30));
        }
        @Override public GameMode getGameMode() { return GameMode.SURVIVAL; }
        @Override public World getEntityWorld() { return visibleWorld == null ? super.getEntityWorld() : visibleWorld; }
        void place(Vec3d position) { setPosition(position); lastX = position.x; lastY = position.y; lastZ = position.z; }
        void aim(float yaw, float pitch) { setYaw(yaw); lastYaw = yaw; headYaw = yaw; lastHeadYaw = yaw; setPitch(pitch); lastPitch = pitch; }
    }
}
