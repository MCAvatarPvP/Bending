package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Existing World API over one captured simulation world. Terrain, membership and
 * environment rewind together through RollbackStateGraph. No method consults a live
 * world or the global online-player list. Native behavior and effect encoding are
 * mandatory services, not inherited World stubs or alternative ability implementations.
 */
public final class RollbackWorld extends World implements RollbackStateCell<RollbackWorld.State> {
    public enum Dimension { NORMAL, NETHER, THE_END }
    public record Identity(String name, Dimension dimension, int minimumY, int maximumY) {
        public Identity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dimension, "dimension");
            if (maximumY <= minimumY) throw new IllegalArgumentException("World height");
        }
    }
    public record Chunk(int x, int z) { }
    /** Time/weather changes are supplied by the simulation, never sampled during replay. */
    public record Conditions(long time, long fullTime, String difficulty, boolean storm, Set<Chunk> loadedChunks) {
        public Conditions {
            if (time < 0 || time >= 24_000) throw new IllegalArgumentException("World time of day");
            if (!Set.of("PEACEFUL", "EASY", "NORMAL", "HARD").contains(difficulty)) throw new IllegalArgumentException("World difficulty");
            loadedChunks = Set.copyOf(loadedChunks);
        }
    }

    /**
     * Native queries must use the supplied logical world. Spawn methods return a new,
     * unregistered logical view; this class registers it before returning to ability code.
     * Mutation callbacks apply native policy to logical state. Output callbacks must
     * immediately encode detached effects into the current RollbackStep, including any
     * mutable particle/effect payload, rather than retaining it or delivering live effects.
     * Mutable service state must be included among the domain's registered roots.
     */
    public interface Queries {
        RollbackBlockRay.Hit rayTraceBlocks(RollbackWorld world, RollbackBlockRay ray);
        default RollbackBlockRay.Hit rayTraceBlocks(RollbackWorld world, RollbackBlockRay ray, Entity viewer) {
            throw new IllegalStateException("Entity sight requires native collision context");
        }
        RayTraceResult rayTrace(RollbackWorld world, RollbackWorldRay ray, double raySize, Predicate<Entity> filter);
        RollbackBlockStore.Position highestBlock(RollbackWorld world, int x, int z);
    }
    public interface Actions {
        <T extends Entity> T spawn(RollbackWorld world, RollbackEntityBody.Pose position, Class<T> type);
        Entity spawnEntity(RollbackWorld world, RollbackEntityBody.Pose position, EntityType type);
        Item dropItem(RollbackWorld world, RollbackEntityBody.Pose position, ItemStack item, boolean natural);
        FallingBlock fallingBlock(RollbackWorld world, RollbackEntityBody.Pose position, BlockData data);
        Arrow arrow(RollbackWorld world, RollbackEntityBody.Pose position, RollbackEntityBody.Motion direction, float speed, float spread);
        boolean explosion(RollbackWorld world, RollbackEntityBody.Pose position, float power, boolean fire, boolean breakBlocks);
        void sound(RollbackWorld world, RollbackEntityBody.Pose position, Entity source, String sound, boolean named,
                   String category, float volume, float pitch);
        void effect(RollbackWorld world, RollbackEntityBody.Pose position, Effect effect, Object data, int radius);
        void particle(RollbackWorld world, RollbackEntityBody.Pose position, Particle particle,
                      int count, double x, double y, double z, double extra, Object data);
        void lightning(RollbackWorld world, RollbackEntityBody.Pose position, boolean flash);
    }
    public interface Rules extends Queries, Actions { }

    public static final class State {
        private final RollbackWorld owner;
        private final Conditions conditions;
        private State(RollbackWorld owner) { this.owner = owner; conditions = owner.conditions; }
    }

    private final Thread thread = Thread.currentThread();
    private final Identity identity;
    private final RollbackBlockStore terrain;
    private final RollbackEntityRegistry entities;
    private final RollbackItems items;
    private final Queries queries;
    private final Actions rules;
    private Conditions conditions;

    public RollbackWorld(Identity identity, Conditions conditions, RollbackBlockStore.Bounds bounds,
                         Map<RollbackBlockStore.Position, RollbackBlockStore.Cell> seed, RollbackBlockStore.Cell empty,
                         RollbackBlockStore.Rules blockRules, int maximumMutations, int maximumEntities,
                         RollbackItems items, Rules rules) {
        this(identity, conditions, bounds, seed, empty, blockRules, maximumMutations, maximumEntities, items, rules, rules);
    }
    public RollbackWorld(Identity identity, Conditions conditions, RollbackBlockStore.Bounds bounds,
                         Map<RollbackBlockStore.Position, RollbackBlockStore.Cell> seed, RollbackBlockStore.Cell empty,
                         RollbackBlockStore.Rules blockRules, int maximumMutations, int maximumEntities,
                         RollbackItems items, Queries queries, Actions rules) {
        this(identity, conditions, RollbackBlockStore.sparseSeed(bounds, seed, empty), blockRules, maximumMutations, maximumEntities, items, queries, rules);
    }

    public RollbackWorld(Identity identity, Conditions conditions, RollbackBlockStore.Seed seed,
                         RollbackBlockStore.Rules blockRules, int maximumMutations, int maximumEntities,
                         RollbackItems items, Queries queries, Actions rules) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.conditions = Objects.requireNonNull(conditions, "conditions");
        this.items = Objects.requireNonNull(items, "items");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.queries = Objects.requireNonNull(queries, "queries");
        // Captured bounds may include native air cells outside build height for neighbor queries.
        terrain = new RollbackBlockStore(this, seed, blockRules, maximumMutations);
        entities = new RollbackEntityRegistry(this, maximumEntities);
    }

    public RollbackBlockStore terrain() { checkThread(); return terrain; }
    public RollbackEntityRegistry entities() { checkThread(); return entities; }
    public Conditions conditions() { checkThread(); return conditions; }
    public void conditions(Conditions value) { checkThread(); conditions = Objects.requireNonNull(value, "conditions"); }
    @Override public String getName() { checkThread(); return identity.name; }
    @Override public long getTime() { return conditions().time; }
    @Override public long getFullTime() { return conditions().fullTime; }
    @Override public Environment getEnvironment() {
        checkThread();
        return switch (identity.dimension) { case NORMAL -> Environment.NORMAL; case NETHER -> Environment.NETHER; case THE_END -> Environment.THE_END; };
    }
    @Override public int getMinHeight() { checkThread(); return identity.minimumY; }
    @Override public int getMaxHeight() { checkThread(); return identity.maximumY; }
    @Override public Object handle() { checkThread(); return this; }
    @Override public Difficulty getDifficulty() { return conditions().difficulty.equals("PEACEFUL") ? Difficulty.PEACEFUL : Difficulty.valueOf(conditions().difficulty); }
    @Override public boolean hasStorm() { return conditions().storm; }
    @Override public boolean isChunkLoaded(int x, int z) { return conditions().loadedChunks.contains(new Chunk(x, z)); }
    @Override public Block getBlockAt(int x, int y, int z) { checkThread(); return terrain.block(x, y, z); }
    @Override public Block getBlockAt(Location location) {
        position(location);
        return getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }
    @Override public double getTemperature(int x, int y, int z) { return terrain().cell(new RollbackBlockStore.Position(x, y, z)).temperature(); }
    @Override public double getHumidity(int x, int y, int z) { return terrain().cell(new RollbackBlockStore.Position(x, y, z)).humidity(); }
    @Override public Block getHighestBlockAt(Location location) {
        position(location);
        var highest = Objects.requireNonNull(queries.highestBlock(this, location.getBlockX(), location.getBlockZ()), "highest block");
        if (highest.x() != location.getBlockX() || highest.z() != location.getBlockZ()) throw new IllegalStateException("Highest block changed column");
        return getBlockAt(highest.x(), highest.y(), highest.z());
    }
    @Override public List<Entity> getEntities() { return entities().entities(); }
    @Override public Collection<Player> getPlayers() { return entities().players(); }
    @Override public Collection<Entity> getNearbyEntities(BoundingBox box, Predicate<Entity> filter) { return entities().nearby(box, filter); }
    @Override public Collection<LivingEntity> getNearbyLivingEntities(Location location, double x, double y) {
        return getNearbyLivingEntities(location, x, y, x);
    }
    @Override public Collection<LivingEntity> getNearbyLivingEntities(Location location, double x, double y, double z) {
        var point = position(location);
        nonnegative(x); nonnegative(y); nonnegative(z);
        var box = new BoundingBox(new Vector(point.x() - x, point.y() - y, point.z() - z), new Vector(point.x() + x, point.y() + y, point.z() + z));
        return getNearbyEntities(box, LivingEntity.class::isInstance).stream().map(LivingEntity.class::cast).toList();
    }
    @Override public RayTraceResult rayTraceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
        var hit = traceBlocks(origin, direction, range, mode, ignorePassable);
        return hit == null ? null : hit.result();
    }
    /** Native block identity is needed by player targeting even when the hit lies exactly on a face. */
    public RollbackBlockRay.Hit traceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
        return traceBlocks(origin, direction, range, mode, ignorePassable, null);
    }
    public RollbackBlockRay.Hit traceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable, Entity viewer) {
        return traceBlocks(ray(origin, direction, range, mode, ignorePassable), viewer);
    }
    /** Preserve an entity's exact eye endpoint instead of rounding it through normalize/scale. */
    public RollbackBlockRay.Hit traceBlocks(Location origin, Location end, FluidCollisionMode mode, boolean ignorePassable, Entity viewer) {
        var from = position(origin); var to = position(end);
        double x = to.x() - from.x(), y = to.y() - from.y(), z = to.z() - from.z();
        double distance = Math.sqrt(x * x + y * y + z * z);
        var direction = distance == 0 ? new RollbackEntityBody.Motion(0, 0, 1) : new RollbackEntityBody.Motion(x / distance, y / distance, z / distance);
        var blocks = new RollbackBlockRay(from.x(), from.y(), from.z(), to.x(), to.y(), to.z(), RollbackBlockRay.Fluids.valueOf(mode.name()), ignorePassable);
        return traceBlocks(new RollbackWorldRay(blocks, direction, distance), viewer);
    }
    private RollbackBlockRay.Hit traceBlocks(RollbackWorldRay query, Entity viewer) {
        if (viewer != null) requireMember(viewer);
        var hit = viewer == null ? queries.rayTraceBlocks(this, query.blocks()) : queries.rayTraceBlocks(this, query.blocks(), viewer);
        if (hit == null) return null;
        terrain.cell(hit.block());
        checkedHit(hit.result(), query);
        return hit;
    }
    @Override public RayTraceResult rayTrace(Location origin, Vector direction, double range, FluidCollisionMode mode,
                                            boolean ignorePassable, double raySize, Predicate<Entity> filter) {
        var query = ray(origin, direction, range, mode, ignorePassable);
        if (!Double.isFinite(raySize)) throw new IllegalArgumentException("Ray size");
        Predicate<Entity> checkedFilter = entity -> { requireMember(entity); return filter == null || filter.test(entity); };
        return checkedHit(queries.rayTrace(this, query, raySize, checkedFilter), query);
    }

    @Override public <T> T spawn(Location location, Class<T> type) {
        var point = position(location);
        Class<? extends Entity> entityType = Objects.requireNonNull(type, "type").asSubclass(Entity.class);
        return type.cast(register(entityType.cast(rules.spawn(this, point, entityType))));
    }
    @Override public <T> T spawn(Location location, Class<T> type, Consumer<T> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        T value = spawn(location, type);
        consumer.accept(value);
        return value;
    }
    @Override public Entity spawnEntity(Location location, EntityType type) {
        return register(rules.spawnEntity(this, position(location), Objects.requireNonNull(type, "type")));
    }
    @Override public Item dropItem(Location location, ItemStack item) { return drop(location, item, false); }
    @Override public Item dropItemNaturally(Location location, ItemStack item) { return drop(location, item, true); }
    private Item drop(Location location, ItemStack item, boolean natural) {
        var point = position(location);
        return register(rules.dropItem(this, point, items.copy(Objects.requireNonNull(item, "item")), natural));
    }
    @Override public FallingBlock spawnFallingBlock(Location location, BlockData data) {
        var point = position(location);
        BlockData copy = Objects.requireNonNull(data, "data").clone();
        if (copy == data) throw new IllegalArgumentException("Block data must be detached");
        copy.setExactState(data.getExactState());
        return register(rules.fallingBlock(this, point, copy));
    }
    @Override public Arrow spawnArrow(Location location, Vector direction, float speed, float spread) {
        var point = position(location);
        finite(speed); finite(spread);
        return register(rules.arrow(this, point, RollbackEntityBody.Motion.from(Objects.requireNonNull(direction, "direction")), speed, spread));
    }
    @Override public Arrow spawnArrow(Location location, Vector direction, int speed, float spread) { return spawnArrow(location, direction, (float) speed, spread); }
    @Override public boolean createExplosion(double x, double y, double z, float power, boolean fire, boolean breakBlocks) {
        checkThread(); nonnegative(power);
        return rules.explosion(this, new RollbackEntityBody.Pose(x, y, z, 0, 0), power, fire, breakBlocks);
    }
    @Override public boolean createExplosion(Location location, float power) {
        var point = position(location);
        return createExplosion(point.x(), point.y(), point.z(), power, false, true);
    }

    @Override public void playSound(Location location, Sound sound, float volume, float pitch) { playSound(location, sound, SoundCategory.MASTER, volume, pitch); }
    @Override public void playSound(Location location, String sound, float volume, float pitch) {
        sound(position(location), null, Objects.requireNonNull(sound, "sound"), true, SoundCategory.MASTER, volume, pitch);
    }
    @Override public void playSound(Location location, Sound sound, SoundCategory category, float volume, float pitch) {
        sound(position(location), null, Objects.requireNonNull(sound, "sound").name(), false, category, volume, pitch);
    }
    @Override public void playSound(Entity entity, Sound sound, float volume, float pitch) { playSound(entity, sound, SoundCategory.MASTER, volume, pitch); }
    @Override public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch) {
        requireMember(entity);
        sound(RollbackEntityBody.logicalBody(entity).kinematics().pose(), entity, Objects.requireNonNull(sound, "sound").name(), false, category, volume, pitch);
    }
    private void sound(RollbackEntityBody.Pose position, Entity entity, String name, boolean named, SoundCategory category, float volume, float pitch) {
        finite(volume); finite(pitch);
        rules.sound(this, position, entity, name, named, Objects.requireNonNull(category, "category").name(), volume, pitch);
    }
    @Override public void playEffect(Location location, Effect effect, int data) { playEffect(location, effect, data, 64); }
    @Override public void playEffect(Location location, Effect effect, Object data) { effect(location, effect, data, 64); }
    @Override public void playEffect(Location location, Effect effect, int data, int radius) { effect(location, effect, data, radius); }
    private void effect(Location location, Effect effect, Object data, int radius) {
        var point = position(location);
        if (radius < 0) throw new IllegalArgumentException("Effect radius");
        rules.effect(this, point, Objects.requireNonNull(effect, "effect"), data, radius);
    }
    @Override public void spawnParticle(Particle particle, Location location, int count, double x, double y, double z, double extra) {
        spawnParticle(particle, location, count, x, y, z, extra, null);
    }
    @Override public void spawnParticle(Particle particle, Location location, int count, double x, double y, double z) {
        spawnParticle(particle, location, count, x, y, z, 1, null);
    }
    @Override public <T> void spawnParticle(Particle particle, Location location, int count, double x, double y, double z, double extra, T data) {
        var point = position(location);
        if (count < 0) throw new IllegalArgumentException("Particle count");
        finite(x); finite(y); finite(z); finite(extra);
        rules.particle(this, point, Objects.requireNonNull(particle, "particle"), count, x, y, z, extra, data);
    }
    @Override public void strikeLightningEffect(Location location, boolean flash) { rules.lightning(this, position(location), flash); }

    @Override public State captureRollbackState() { checkThread(); return new State(this); }
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (state.owner != this) throw new IllegalArgumentException("World checkpoint belongs to another world");
        conditions = state.conditions;
    }
    @Override public Collection<?> rollbackReferences() { checkThread(); return List.of(terrain, entities); }

    private <T extends Entity> T register(T entity) { checkThread(); entities.add(entity); return entity; }
    private RollbackEntityBody.Pose position(Location location) {
        checkThread();
        if (Objects.requireNonNull(location, "location").getWorld() != this) throw new IllegalArgumentException("Location belongs to another logical world");
        return RollbackEntityBody.Pose.from(location);
    }
    private RollbackWorldRay ray(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
        position(origin);
        return RollbackWorldRay.from(origin.toVector(), direction, range, mode, ignorePassable);
    }
    // Exact view identity is required: an old view can share a UUID with a replacement.
    @SuppressWarnings("WrapperReferenceEquality")
    private void requireMember(Entity entity) {
        checkThread();
        var body = RollbackEntityBody.logicalBody(entity);
        if (body.world() != this || entities.get(body.identity().uuid()) != entity) throw new IllegalArgumentException("Entity is not a current member of this logical world");
    }
    private RayTraceResult checkedHit(RayTraceResult hit, RollbackWorldRay ray) {
        if (hit == null) return null;
        Vector point = Objects.requireNonNull(hit.getHitPosition(), "hit position");
        finite(point.getX()); finite(point.getY()); finite(point.getZ());
        double dx = ray.direction().x(), dy = ray.direction().y(), dz = ray.direction().z();
        double px = point.getX() - ray.blocks().x(), py = point.getY() - ray.blocks().y(), pz = point.getZ() - ray.blocks().z();
        double distance = px * dx + py * dy + pz * dz;
        double ex = px - distance * dx, ey = py - distance * dy, ez = pz - distance * dz;
        // Paper entity rays beginning inside the target select its exit face, which
        // can exceed the nominal distance. Blocks remain constrained to the segment.
        if (distance < -1e-9 || hit.getHitEntity() == null && distance > ray.distance() + 1e-9
                || ex * ex + ey * ey + ez * ez > 1e-12) throw new IllegalStateException("Native ray result left query ray");
        if (hit.getHitEntity() != null) requireMember(hit.getHitEntity());
        return new RayTraceResult(new Vector(point.getX(), point.getY(), point.getZ()), hit.getHitEntity());
    }
    private void checkThread() { if (Thread.currentThread() != thread) throw new IllegalStateException("Logical world crossed threads"); }
    private static void finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite world operation"); }
    private static void nonnegative(double value) { finite(value); if (value < 0) throw new IllegalArgumentException("Negative world operation extent"); }
}
