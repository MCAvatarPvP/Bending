package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackRosterData;
import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class FabricRollbackPlayerRendererTest {
    static final UUID A = new UUID(0, 7001), B = new UUID(0, 7002);
    @BeforeAll static void bootstrap() { FabricRollbackTestRegistry.bootstrap(); }
    static FabricRollbackRoster roster() throws Exception {
        var queries = new FabricRollbackWorldAccessTest.Queries(); queries.time = 20;
        try (var input = Objects.requireNonNull(FabricRollbackPlayerRendererTest.class.getResourceAsStream("/rollback/player-roster.base64"))) {
            return FabricRollbackRoster.instantiate(RollbackRosterData.decode(Base64.getMimeDecoder().decode(input.readAllBytes())), Set.of(A, B), queries, 0, 300_000);
        }
    }
    static Map<UUID, FabricRollbackPlayerView> views(FabricRollbackRoster roster) {
        var result = new TreeMap<UUID, FabricRollbackPlayerView>(); var viewer = roster.players().get(A).ownedPlayer();
        roster.players().forEach((id, state) -> result.put(id, state.use(player -> FabricRollbackPlayerView.capture(player, viewer)))); return result;
    }
    @Test void nativeExtractionUsesDetachedThreeDimensionalMotionBeforeShadowsAndCorrectionsReplaceIt() throws Exception {
        var privateRoster = roster(); var visibleRoster = roster(); var player = privateRoster.players().get(B).ownedPlayer();
        var visible = visibleRoster.players().get(B).ownedPlayer(); var original = visible.getEntityPos(); var before = views(privateRoster);
        player.setPosition(3, 2.5, 2); player.lastRenderX = 1; player.lastRenderY = 1.5; player.lastRenderZ = 0;
        player.lastBodyYaw = 0; player.bodyYaw = 30; player.lastHeadYaw = 170; player.headYaw = -170; player.lastPitch = 10; player.setPitch(40);
        player.hurtTime = 5; player.setPose(EntityPose.CROUCHING);
        player.limbAnimator.lastSpeed = 1.4F; player.limbAnimator.speed = .3F; player.limbAnimator.animationProgress = 4; player.limbAnimator.timeScale = .9F;
        var next = views(privateRoster); float amplitude = player.limbAnimator.getAmplitude(.5F), phase = player.limbAnimator.getAnimationProgress(.5F);
        player.setPosition(-2, 1, -2); player.hurtTime = 0; player.limbAnimator.reset();
        try (var owner = new FabricRollbackPlayerRenderer(UUID.randomUUID(), visibleRoster.world().world(), Set.of(A, B), () -> true, () -> Vec3d.ZERO, position -> 123)) {
            owner.publish(1, 0, next);
            var renderer = new ObjenesisStd(false).newInstance(ProbeRenderer.class);
            assertEquals(original.x, renderer.getAndUpdateRenderState(visible, .5F).x, "UI previews must retain ordinary entity state");
            try (var extraction = FabricRollbackPlayerRenderer.extracting()) {
            var state = renderer.getAndUpdateRenderState(visible, .5F);
            assertEquals(new Vec3d(2, 2, 1), new Vec3d(state.x, state.y, state.z)); assertEquals(2, renderer.shadowX);
            assertEquals(123, state.light); assertEquals(9, state.squaredDistanceToCamera);
            assertTrue(state.hurt); assertEquals(EntityPose.CROUCHING, state.pose); assertTrue(state.isInSneakingPose);
            assertEquals(15, state.bodyYaw); assertEquals(165, state.relativeHeadYaw); assertEquals(25, state.pitch);
            assertEquals(amplitude, state.limbSwingAmplitude); assertEquals(phase, state.limbSwingAnimationProgress);
            assertEquals("cosmetic label", state.displayName.getString()); assertEquals(original, visible.getEntityPos());
            var frustum = new ProbeFrustum(); assertTrue(renderer.shouldRender(visible, frustum, 0, 0, 0));
            assertEquals(next.get(B).motion().cullingBounds(), frustum.seen); assertEquals(new BlockPos(3, 2, 2), FabricRollbackPlayerRenderer.blockPos(visible));
            owner.publish(1, 1, before); var corrected = renderer.getAndUpdateRenderState(visible, 1);
            assertEquals(before.get(B).motion().position(), new Vec3d(corrected.x, corrected.y, corrected.z));
            assertEquals(before.get(B).living().hurtTime() > 0 || before.get(B).living().deathTime() > 0, corrected.hurt);
            assertEquals(original, visible.getEntityPos());
            }
            var uninitialized = new ObjenesisStd(false).newInstance(WorldRenderer.class);
            var extract = WorldRenderer.class.getDeclaredMethod("fillEntityRenderStates", net.minecraft.client.render.Camera.class, Frustum.class,
                    net.minecraft.client.render.RenderTickCounter.class, net.minecraft.client.render.state.WorldRenderState.class);
            extract.setAccessible(true);
            var exception = assertThrows(java.lang.reflect.InvocationTargetException.class, () -> extract.invoke(uninitialized, null, null, null, null));
            assertInstanceOf(NullPointerException.class, exception.getCause());
            assertEquals(original.x, renderer.getAndUpdateRenderState(visible, .5F).x, "Failed world extraction must release its scope");
        }
        // Loading the real world renderer validates its transformed chunk-readiness injection without opening a game window.
        Class.forName(WorldRenderer.class.getName(), false, WorldRenderer.class.getClassLoader()).getDeclaredMethods();
    }
    @Test void leaseIsExactToWorldRosterIdentityAndConnectionAndStopsWithoutTouchingOtherSessions() throws Exception {
        var roster = roster(); var foreign = roster(); var views = views(roster); var current = new AtomicBoolean(true);
        var player = roster.players().get(B).ownedPlayer();
        try (var extraction = FabricRollbackPlayerRenderer.extracting();
             var owner = new FabricRollbackPlayerRenderer(UUID.randomUUID(), roster.world().world(), Set.of(A, B), current::get, () -> Vec3d.ZERO, pos -> 0);
             var replacement = new FabricRollbackPlayerRenderer(UUID.randomUUID(), roster.world().world(), Set.of(A, B), current::get, () -> Vec3d.ZERO, pos -> 0)) {
            owner.publish(2, 3, views);
            assertThrows(IllegalStateException.class, () -> replacement.publish(2, 3, views));
            assertThrows(IllegalArgumentException.class, () -> owner.publish(1, 3, views));
            assertThrows(IllegalArgumentException.class, () -> owner.publish(2, 2, views));
            assertThrows(IllegalArgumentException.class, () -> owner.publish(2, 3, Map.of(A, views.get(A))));
            var changed = new HashMap<>(views); var b = views.get(B);
            changed.put(B, new FabricRollbackPlayerView(B, 999, b.motion(), b.living(), b.cameraMotion(), b.health()));
            assertThrows(IllegalArgumentException.class, () -> owner.publish(2, 3, changed));
            var untouched = new PlayerEntityRenderState(); untouched.x = 99;
            FabricRollbackPlayerRenderer.apply(foreign.players().get(B).ownedPlayer(), untouched, 1); assertEquals(99, untouched.x);
            int id = player.getId(); player.setId(999);
            try { FabricRollbackPlayerRenderer.apply(player, untouched, 1); assertEquals(99, untouched.x); } finally { player.setId(id); }
            var failure = assertThrows(CompletionException.class, () -> CompletableFuture.runAsync(() -> owner.publish(2, 3, views)).join());
            assertInstanceOf(IllegalStateException.class, failure.getCause());
            owner.close(); replacement.publish(2, 3, views); owner.close();
            var active = new PlayerEntityRenderState(); FabricRollbackPlayerRenderer.apply(player, active, 1); assertEquals(b.motion().position().x, active.x);
            current.set(false); active.x = 99; FabricRollbackPlayerRenderer.apply(player, active, 1); assertEquals(99, active.x);
            assertThrows(IllegalStateException.class, () -> replacement.publish(3, 3, views));
        }
        var after = new PlayerEntityRenderState(); after.x = 99; FabricRollbackPlayerRenderer.apply(player, after, 1); assertEquals(99, after.x);
    }
    private static final class ProbeFrustum extends Frustum {
        Box seen;
        ProbeFrustum() { super(new Matrix4f(), new Matrix4f()); }
        @Override public boolean isVisible(Box box) { seen = box; return true; }
    }
    private static final class ProbeRenderer extends EntityRenderer<Entity, PlayerEntityRenderState> {
        double shadowX;
        private ProbeRenderer(EntityRendererFactory.Context context) { super(context); }
        @Override public PlayerEntityRenderState createRenderState() { return new PlayerEntityRenderState(); }
        @Override public void updateRenderState(Entity entity, PlayerEntityRenderState state, float delta) {
            state.x = entity.getX(); state.y = entity.getY(); state.z = entity.getZ(); state.displayName = Text.literal("cosmetic label");
        }
        @Override protected void updateShadow(Entity entity, PlayerEntityRenderState state) { shadowX = state.x; }
    }
}
