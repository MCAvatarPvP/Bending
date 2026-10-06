package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Logical entity state shared by typed Entity/Player/etc. views. Geometry and velocity
 * are values; optional native storage stays behind a checkpointed adapter. Physics/event policy is supplied by
 * the session adapter. Checkpoint this through RollbackStateGraph so referenced damage
 * events, metadata payloads, passengers and specialized view state rewind together.
 */
public final class RollbackEntityBody implements RollbackStateCell<RollbackEntityBody.State> {
    public record Identity(UUID uuid, int networkId, String name, EntityType type) {
        public Identity {
            Objects.requireNonNull(uuid, "uuid");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(type, "type");
        }
    }

    public record Pose(double x, double y, double z, float yaw, float pitch) {
        public Pose {
            finite(x, y, z);
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Entity rotation");
        }
        public static Pose from(Location location) {
            return new Pose(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        }
        public Location at(World world) {
            Location result = new Location(world, x, y, z);
            result.setYaw(yaw);
            result.setPitch(pitch);
            return result;
        }
    }

    public record Motion(double x, double y, double z) {
        public Motion { finite(x, y, z); }
        public static Motion from(Vector value) { return new Motion(value.getX(), value.getY(), value.getZ()); }
        public Vector vector() { return new Vector(x, y, z); }
    }

    /** Native capture supplies the actual relative hitbox, including the current pose. */
    public record Kinematics(Pose pose, Motion velocity, RollbackBlockStore.Box bounds,
                             double height, boolean onGround, double fallDistance, boolean velocityChanged) {
        public Kinematics {
            Objects.requireNonNull(pose, "pose");
            Objects.requireNonNull(velocity, "velocity");
            Objects.requireNonNull(bounds, "bounds");
            if (!Double.isFinite(height) || height < 0 || !Double.isFinite(fallDistance)) throw new IllegalArgumentException("Entity dimensions");
        }
    }

    /**
     * One authoritative movement store shared with native physics. Returned values
     * are detached; implementations reject foreign/native live entities and validate
     * complete writes before changing state. Other native fields rewind through the
     * same state cell. Reads/writes may occur inside private native event callbacks.
     */
    public interface KinematicsSource<S> extends RollbackStateCell<S> {
        Identity identity();
        Kinematics readKinematics();
        void writeKinematics(Kinematics value);
        /** Exact native eye height for targeting nonliving bodies; never infer it from their bounding box. */
        default double eyeHeight() { throw new IllegalStateException("Native entity has no captured eye-height adapter"); }
    }

    /** All callbacks operate on logical state and a scoped event bus, never live entities. */
    public interface Rules {
        /** Return the accepted pose, or null if cancelled. Caller updates logical position. */
        Pose teleport(RollbackEntityBody entity, Pose destination);
        boolean canMount(RollbackEntityBody vehicle, RollbackEntityBody passenger);
    }

    /** Implemented by typed platform views so related entities cannot leak native handles. */
    public interface View {
        RollbackEntityBody body();
    }

    public static final class State {
        private final RollbackEntityBody owner;
        private final Data data;
        private State(RollbackEntityBody owner, Data data) { this.owner = owner; this.data = data.copy(); }
    }

    private static final class Data {
        Kinematics kinematics;
        boolean valid = true, dead, silent, invulnerable, gravity = true, persistent = true;
        int fireTicks;
        RollbackEntityBody vehicle;
        final List<RollbackEntityBody> passengers = new ArrayList<>();
        final Map<String, List<StoredMetadata>> metadata = new LinkedHashMap<>();
        EntityDamageEvent lastDamage;
        byte[] nativeState = new byte[0];

        Data copy() {
            Data copy = new Data();
            copy.kinematics = kinematics;
            copy.valid = valid; copy.dead = dead; copy.silent = silent; copy.invulnerable = invulnerable;
            copy.gravity = gravity; copy.persistent = persistent; copy.fireTicks = fireTicks;
            copy.vehicle = vehicle;
            copy.passengers.addAll(passengers);
            metadata.forEach((key, values) -> copy.metadata.put(key, new ArrayList<>(values)));
            copy.lastDamage = lastDamage;
            copy.nativeState = nativeState.clone();
            return copy;
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Identity identity;
    private final World world;
    private final Rules rules;
    private final KinematicsSource<?> nativeKinematics;
    private Entity view;
    private Data data = new Data();

    public RollbackEntityBody(Identity identity, World world, Kinematics kinematics, Rules rules) {
        this(identity, world, Objects.requireNonNull(kinematics, "kinematics"), null, rules);
    }

    /** Session bootstrap supplies the logical world corresponding to the private native world. */
    public static RollbackEntityBody nativeBacked(Identity identity, World world, KinematicsSource<?> source, Rules rules) {
        Objects.requireNonNull(source, "source");
        if (!Objects.requireNonNull(identity, "identity").equals(source.identity())) {
            throw new IllegalArgumentException("Native movement belongs to another entity");
        }
        return new RollbackEntityBody(identity, world, null, source, rules);
    }

    private RollbackEntityBody(Identity identity, World world, Kinematics kinematics, KinematicsSource<?> source, Rules rules) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.world = Objects.requireNonNull(world, "world");
        this.rules = Objects.requireNonNull(rules, "rules");
        nativeKinematics = source;
        data.kinematics = kinematics;
    }

    /** Bind once, before the entity enters the session registry or any checkpoint. */
    public void bind(Entity entity) {
        checkThread();
        if (view != null) throw new IllegalStateException("Entity body already has a view");
        if (!(entity instanceof View logical) || logical.body() != this || !(entity instanceof RollbackStateCell<?>)) {
            throw new IllegalArgumentException("Entity view must be logical and checkpointable");
        }
        view = entity;
    }

    public Entity view() {
        checkThread();
        if (view == null) throw new IllegalStateException("Entity body has not been bound");
        return view;
    }
    public Identity identity() { checkThread(); return identity; }
    public World world() { checkThread(); return world; }
    /** Opaque, checkpointed backing identity for loader adapters; never a native entity handle. */
    public KinematicsSource<?> kinematicsSource() { checkThread(); checkNativeIdentity(); return nativeKinematics; }
    public Kinematics kinematics() {
        checkThread(); checkNativeIdentity();
        return nativeKinematics == null ? data.kinematics : Objects.requireNonNull(nativeKinematics.readKinematics(), "native kinematics");
    }
    public void kinematics(Kinematics value) {
        checkThread(); checkNativeIdentity(); Objects.requireNonNull(value, "kinematics");
        if (nativeKinematics == null) data.kinematics = value;
        else nativeKinematics.writeKinematics(value);
    }

    private void checkNativeIdentity() {
        if (nativeKinematics != null && !identity.equals(nativeKinematics.identity())) {
            throw new IllegalStateException("Native movement identity changed");
        }
    }
    public Location location() { return kinematics().pose.at(world); }
    public Vector velocity() { return kinematics().velocity.vector(); }
    public void velocity(Vector velocity) {
        Kinematics current = kinematics();
        kinematics(new Kinematics(current.pose, Motion.from(velocity), current.bounds, current.height,
                current.onGround, current.fallDistance, true));
    }
    public void rotation(float yaw, float pitch) {
        Kinematics current = kinematics();
        Pose p = current.pose;
        kinematics(new Kinematics(new Pose(p.x, p.y, p.z, yaw, pitch), current.velocity, current.bounds,
                current.height, current.onGround, current.fallDistance, current.velocityChanged));
    }
    public void fallDistance(float distance) {
        Kinematics current = kinematics();
        kinematics(new Kinematics(current.pose, current.velocity, current.bounds, current.height,
                current.onGround, distance, current.velocityChanged));
    }
    public BoundingBox bounds() {
        Kinematics current = kinematics();
        Pose p = current.pose;
        RollbackBlockStore.Box b = current.bounds;
        return new BoundingBox(new Vector(p.x + b.minX(), p.y + b.minY(), p.z + b.minZ()),
                new Vector(p.x + b.maxX(), p.y + b.maxY(), p.z + b.maxZ()));
    }
    public boolean teleport(Location destination) {
        checkThread();
        Objects.requireNonNull(destination, "destination");
        if (destination.getWorld() != world) throw new IllegalArgumentException("Teleport left the captured logical world");
        if (!data.valid || data.dead || !data.passengers.isEmpty()) return false;
        Pose accepted = rules.teleport(this, Pose.from(destination));
        if (accepted == null) return false;
        // A cancelled teleport must not dismount; accepted teleports do.
        dismount();
        Kinematics current = kinematics();
        kinematics(new Kinematics(accepted, current.velocity, current.bounds, current.height,
                current.onGround, current.fallDistance, current.velocityChanged));
        return true;
    }

    public boolean valid() { checkThread(); return data.valid; }
    public boolean dead() { checkThread(); return data.dead; }
    /** Native physics/health adapters may update liveness without emitting a live removal. */
    public void liveness(boolean valid, boolean dead) { checkThread(); data.valid = valid; data.dead = dead; }
    public void remove() {
        checkThread();
        dismount();
        eject();
        data.valid = false;
        data.dead = true;
    }
    /**
     * A spawned view absent from the restored registry belongs to a discarded branch.
     * Do not mutate other bodies here: their checkpoint may already have been restored.
     */
    void discardFromRestoredRegistry() {
        checkThread();
        data.valid = false;
        data.dead = true;
        data.vehicle = null;
        data.passengers.clear();
    }
    public int fireTicks() { checkThread(); return data.fireTicks; }
    public void fireTicks(int value) { checkThread(); data.fireTicks = value; }
    public boolean silent() { checkThread(); return data.silent; }
    public void silent(boolean value) { checkThread(); data.silent = value; }
    public boolean invulnerable() { checkThread(); return data.invulnerable; }
    public void invulnerable(boolean value) { checkThread(); data.invulnerable = value; }
    public boolean gravity() { checkThread(); return data.gravity; }
    public void gravity(boolean value) { checkThread(); data.gravity = value; }
    public boolean persistent() { checkThread(); return data.persistent; }
    public void persistent(boolean value) { checkThread(); data.persistent = value; }
    public EntityDamageEvent lastDamage() { checkThread(); return data.lastDamage; }
    public void lastDamage(EntityDamageEvent value) { checkThread(); data.lastDamage = value; }

    public boolean addPassenger(Entity entity) {
        checkThread();
        RollbackEntityBody passenger = logicalBody(entity);
        if (passenger == this || passenger.world != world || !valid() || dead() || !passenger.valid() || passenger.dead()) return false;
        if (data.passengers.contains(passenger)) return false;
        for (RollbackEntityBody ancestor = this; ancestor != null; ancestor = ancestor.data.vehicle) {
            if (ancestor == passenger) return false;
        }
        if (data.passengers.size() >= 64) throw new IllegalStateException("Passenger budget exceeded");
        if (!rules.canMount(this, passenger)) return false;
        passenger.dismount();
        data.passengers.add(passenger);
        passenger.data.vehicle = this;
        return true;
    }
    public List<Entity> passengers() { checkThread(); return data.passengers.stream().map(RollbackEntityBody::view).toList(); }
    public boolean insideVehicle() { checkThread(); return data.vehicle != null; }
    public boolean eject() {
        checkThread();
        if (data.passengers.isEmpty()) return false;
        for (RollbackEntityBody passenger : data.passengers) passenger.data.vehicle = null;
        data.passengers.clear();
        return true;
    }
    private void dismount() {
        if (data.vehicle != null) {
            data.vehicle.data.passengers.remove(this);
            data.vehicle = null;
        }
    }

    public void metadata(String key, MetadataValue value) {
        checkThread();
        Objects.requireNonNull(key, "key");
        Object owner, payload;
        if (value instanceof FixedMetadataValue fixed) { owner = fixed.owner(); payload = fixed.value(); }
        else if (value instanceof StoredMetadata stored) { owner = stored.owner; payload = stored.value; }
        else throw new IllegalArgumentException("Logical metadata must be a captured fixed value");
        if (!data.metadata.containsKey(key) && data.metadata.size() >= 256) throw new IllegalStateException("Entity metadata budget exceeded");
        List<StoredMetadata> values = data.metadata.computeIfAbsent(key, ignored -> new ArrayList<>());
        values.removeIf(entry -> entry.owner == owner);
        if (values.size() >= 64) throw new IllegalStateException("Metadata owner budget exceeded");
        values.add(new StoredMetadata(owner, payload));
    }
    public List<MetadataValue> metadata(String key) {
        checkThread();
        List<StoredMetadata> values = data.metadata.get(key);
        return values == null ? List.of() : List.copyOf(values);
    }
    public boolean hasMetadata(String key) { checkThread(); return data.metadata.containsKey(key); }
    public void removeMetadata(String key, Object owner) {
        checkThread();
        List<StoredMetadata> values = data.metadata.get(key);
        if (values == null) return;
        values.removeIf(entry -> entry.owner == owner);
        if (values.isEmpty()) data.metadata.remove(key);
    }

    /** Detached native physics state supplements, rather than replaces, the exposed fields. */
    public byte[] nativeState() { checkThread(); return data.nativeState.clone(); }
    public void nativeState(byte[] value) {
        checkThread();
        if (Objects.requireNonNull(value, "nativeState").length > 1_048_576) throw new IllegalArgumentException("Entity native state budget");
        data.nativeState = value.clone();
    }

    @Override public State captureRollbackState() { checkThread(); checkNativeIdentity(); view(); return new State(this, data); }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (state.owner != this) throw new IllegalArgumentException("Entity checkpoint belongs to another body");
        data = state.data.copy();
    }
    @Override public Collection<?> rollbackReferences() {
        checkThread();
        List<Object> references = new ArrayList<>();
        references.add(view());
        if (nativeKinematics != null) references.add(nativeKinematics);
        if (data.vehicle != null) references.add(data.vehicle);
        references.addAll(data.passengers);
        if (data.lastDamage != null) references.add(data.lastDamage);
        data.metadata.values().forEach(references::addAll);
        return references;
    }

    // Logical views are bound once by identity; accepting an equal replacement
    // wrapper would allow a different mutable view to masquerade as this body.
    @SuppressWarnings("WrapperReferenceEquality")
    public static RollbackEntityBody logicalBody(Entity entity) {
        if (!(entity instanceof View logical)) throw new IllegalArgumentException("Expected a logical entity");
        RollbackEntityBody body = logical.body();
        if (body.view() != entity) throw new IllegalArgumentException("Entity is not its body's bound view");
        return body;
    }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Logical entity crossed threads");
    }
    private static void finite(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Entity coordinates");
    }

    /** Owner is an identity key; mutable payloads are separately visited by the state graph. */
    private static final class StoredMetadata implements MetadataValue, RollbackStateCell<Void> {
        private final Object owner, value;
        private StoredMetadata(Object owner, Object value) { this.owner = owner; this.value = value; }
        @Override public Object value() { return value; }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void ignored) { }
        @Override public Collection<?> rollbackReferences() { return value == null ? List.of() : List.of(value); }
    }
}
