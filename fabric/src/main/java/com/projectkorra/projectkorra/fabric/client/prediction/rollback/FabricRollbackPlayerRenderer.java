package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/** Render-thread lease over a single agreed session. Normal entity state and server interpolation remain untouched. */
public final class FabricRollbackPlayerRenderer implements AutoCloseable {
    private static FabricRollbackPlayerRenderer active;
    private static final ThreadLocal<Extraction> EXTRACTION = new ThreadLocal<>();
    /** World rendering only. Inventory previews and other render passes keep ordinary entity state. */
    public static Extraction extracting() { var scope = new Extraction(EXTRACTION.get()); EXTRACTION.set(scope); return scope; }
    public static final class Extraction implements AutoCloseable {
        private final Extraction previous;
        private final Thread thread = Thread.currentThread();
        private boolean closed;
        private Extraction(Extraction previous) { this.previous = previous; }
        @Override public void close() {
            if (thread != Thread.currentThread()) throw new IllegalStateException("Render extraction crossed threads");
            if (closed) return;
            if (EXTRACTION.get() != this) throw new IllegalStateException("Render extraction scopes closed out of order");
            closed = true; if (previous == null) EXTRACTION.remove(); else EXTRACTION.set(previous);
        }
    }
    private final Thread thread = Thread.currentThread();
    private final UUID session;
    private final World world;
    private final Set<UUID> participants;
    private final BooleanSupplier current;
    private final Supplier<Vec3d> camera;
    private final ToIntFunction<BlockPos> light;
    private Map<UUID, FabricRollbackPlayerView> views = Map.of();
    private Map<UUID, Float> previousEyes = Map.of();
    private UUID viewer;
    private Camera ownedCamera;
    private long tick = -1, revision = -1;
    private boolean closed;

    public FabricRollbackPlayerRenderer(UUID session, World world, Set<UUID> participants, BooleanSupplier current,
                                        Supplier<Vec3d> camera, ToIntFunction<BlockPos> light) {
        this.session = Objects.requireNonNull(session); this.world = Objects.requireNonNull(world); this.participants = Set.copyOf(participants);
        this.current = Objects.requireNonNull(current); this.camera = Objects.requireNonNull(camera); this.light = Objects.requireNonNull(light);
        if (participants.isEmpty() || participants.size() > 128) throw new IllegalArgumentException("Render roster size");
    }
    /** Optional until a complete client session is prepared; only this exact game camera may consume the view. */
    public void bindCamera(UUID viewer, Camera camera) {
        check();
        if (closed || tick >= 0 || ownedCamera != null || !participants.contains(viewer)) throw new IllegalStateException("Camera binding is not available");
        this.viewer = viewer; ownedCamera = Objects.requireNonNull(camera);
    }
    /** Publish only after the replica has produced its complete provisional head, including a replay correction. */
    public void publish(long tick, long revision, Map<UUID, FabricRollbackPlayerView> next) {
        check();
        if (closed || !current.getAsBoolean() || active != null && active != this) throw new IllegalStateException("Render session cannot acquire ownership");
        if (tick < this.tick || revision < this.revision || tick < 0 || revision < 0) throw new IllegalArgumentException("Render revision moved backwards");
        if (!next.keySet().equals(participants)) throw new IllegalArgumentException("Render roster differs from the agreed session");
        var copied = Map.copyOf(next); var ids = new HashSet<Integer>();
        for (var entry : copied.entrySet()) {
            var previous = views.get(entry.getKey());
            if (!entry.getKey().equals(entry.getValue().id()) || !ids.add(entry.getValue().entityId())
                    || previous != null && previous.entityId() != entry.getValue().entityId()) throw new IllegalArgumentException("Render identity changed");
        }
        var eyes = new HashMap<UUID, Float>();
        boolean same = tick == this.tick && revision == this.revision;
        boolean continuous = this.tick >= 0 && tick - this.tick == 1 && revision == this.revision;
        copied.forEach((id, view) -> eyes.put(id, same ? previousEyes.get(id)
                : continuous ? views.get(id).cameraMotion().eyeHeight() : view.cameraMotion().eyeHeight()));
        previousEyes = Map.copyOf(eyes); views = copied; this.tick = tick; this.revision = revision; active = this;
    }
    private FabricRollbackPlayerView lookup(Entity entity) {
        check();
        if (closed || !current.getAsBoolean()) { close(); return null; }
        if (entity.getEntityWorld() != world) return null;
        var view = views.get(entity.getUuid());
        return view != null && view.entityId() == entity.getId() ? view : null;
    }
    public static void apply(Entity entity, EntityRenderState state, float delta) {
        if (EXTRACTION.get() == null) return;
        var owner = active; if (owner == null) return; var view = owner.lookup(entity); if (view == null) return;
        view.apply(state, delta, owner.camera.get(), owner.light.applyAsInt(view.lightPos(delta)));
    }
    /** Null preserves the ordinary renderer's culling decision. */
    public static Boolean shouldRender(Entity entity, Frustum frustum, double x, double y, double z) {
        if (EXTRACTION.get() == null) return null;
        var owner = active; if (owner == null) return null; var view = owner.lookup(entity); if (view == null) return null;
        return entity.shouldRender(view.motion().position().squaredDistanceTo(x, y, z)) && frustum.isVisible(view.motion().cullingBounds());
    }
    public static BlockPos blockPos(Entity entity) {
        if (EXTRACTION.get() == null) return entity.getBlockPos();
        var owner = active; var view = owner == null ? null : owner.lookup(entity); return view == null ? entity.getBlockPos() : view.blockPos();
    }
    public record CameraView(FabricRollbackPlayerView player, float previousEyeHeight, boolean localAim) {
        public Vec3d eyePosition(float delta) { return player.cameraMotion().sample(player.motion().position(), previousEyeHeight, delta); }
        public float yaw(float delta) { return player.cameraMotion().yaw().sample(delta); }
        public float pitch(float delta) { return net.minecraft.util.math.MathHelper.lerp(delta, player.living().pitch().previous(), player.living().pitch().current()); }
    }
    /** Camera.update has its own boundary; it intentionally runs before the world model-extraction scope. */
    public static CameraView camera(Camera camera, Entity entity, World area) {
        var owner = active;
        if (owner == null || owner.ownedCamera != camera || owner.world != area) return null;
        var view = owner.lookup(entity);
        return view == null ? null : new CameraView(view, owner.previousEyes.get(view.id()), owner.viewer.equals(view.id()));
    }
    @Override public void close() { check(); closed = true; views = Map.of(); previousEyes = Map.of(); ownedCamera = null; if (active == this) active = null; }
    public UUID session() { return session; }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Player rendering crossed threads"); }
}
