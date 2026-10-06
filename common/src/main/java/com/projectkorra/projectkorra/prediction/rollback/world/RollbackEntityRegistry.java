package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

/** Bounded session membership and spatial queries over logical entity views. */
public final class RollbackEntityRegistry implements RollbackStateCell<RollbackEntityRegistry.State> {
    public static final class State {
        private final RollbackEntityRegistry owner;
        private final Map<UUID, Entity> entities;
        private State(RollbackEntityRegistry owner) {
            this.owner = owner;
            entities = Map.copyOf(owner.entities);
        }
    }

    private final Thread thread = Thread.currentThread();
    private final World world;
    private final int maximumEntities;
    private final Map<UUID, Entity> entities = new TreeMap<>();
    private final Map<Integer, Entity> networkIds = new HashMap<>();

    public RollbackEntityRegistry(World world, int maximumEntities) {
        this.world = Objects.requireNonNull(world, "world");
        if (maximumEntities < 1 || maximumEntities > 16_384) throw new IllegalArgumentException("Entity budget");
        this.maximumEntities = maximumEntities;
    }

    public void add(Entity entity) {
        addAll(List.of(entity));
    }

    /** Validate a bootstrap cohort completely before publishing any member. */
    public void addAll(Collection<? extends Entity> candidates) {
        checkThread();
        var added = new TreeMap<UUID, Entity>();
        var addedNetwork = new HashMap<Integer, Entity>();
        for (Entity entity : candidates) {
            RollbackEntityBody body = RollbackEntityBody.logicalBody(entity);
            if (body.world() != world) throw new IllegalArgumentException("Entity belongs to another logical world");
            if (!body.valid()) throw new IllegalArgumentException("Cannot register a removed entity");
            UUID id = body.identity().uuid();
            int networkId = body.identity().networkId();
            if (entities.containsKey(id) || networkIds.containsKey(networkId) || added.putIfAbsent(id, entity) != null
                    || addedNetwork.putIfAbsent(networkId, entity) != null) throw new IllegalArgumentException("Duplicate entity identity");
            if (added.size() > maximumEntities - entities.size()) throw new IllegalStateException("Entity budget exceeded");
        }
        entities.putAll(added); networkIds.putAll(addedNetwork);
    }

    /** Previous checkpoints and ability references retain removed views as needed. */
    public void pruneRemoved() {
        checkThread();
        entities.entrySet().removeIf(entry -> {
            if (entry.getValue().isValid()) return false;
            networkIds.remove(entry.getValue().getEntityId());
            return true;
        });
    }

    public Entity get(UUID id) { checkThread(); return present(entities.get(id)); }
    public Entity get(int networkId) { checkThread(); return present(networkIds.get(networkId)); }
    public List<Entity> entities() {
        checkThread();
        return entities.values().stream().filter(Entity::isValid).toList();
    }
    public List<Player> players() {
        return entities().stream().filter(Player.class::isInstance).map(Player.class::cast).toList();
    }

    /** Read current provisional boxes every time; movement never leaves a stale spatial cache. */
    public Collection<Entity> nearby(BoundingBox bounds, Predicate<Entity> filter) {
        checkThread();
        requireBounds(Objects.requireNonNull(bounds, "bounds"));
        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : entities.values()) {
            if (!entity.isValid()) continue;
            BoundingBox box = entity.getBoundingBox();
            requireBounds(box);
            if (box.getMinX() < bounds.getMaxX() && box.getMaxX() > bounds.getMinX()
                    && box.getMinY() < bounds.getMaxY() && box.getMaxY() > bounds.getMinY()
                    && box.getMinZ() < bounds.getMaxZ() && box.getMaxZ() > bounds.getMinZ()) candidates.add(entity);
        }
        // Finish broadphase before callbacks. A filter may remove/spawn an entity.
        return filter == null ? List.copyOf(candidates) : candidates.stream().filter(filter).toList();
    }

    @Override public State captureRollbackState() { checkThread(); return new State(this); }
    // A different view with the same UUID can have been spawned on the discarded
    // branch. Membership here is deliberately checked by identity, not UUID equality.
    @SuppressWarnings("WrapperReferenceEquality")
    @Override public void restoreRollbackState(State state) {
        checkThread();
        if (state.owner != this) throw new IllegalArgumentException("Registry checkpoint belongs to another world");
        for (var entry : entities.entrySet()) {
            if (state.entities.get(entry.getKey()) != entry.getValue()) {
                RollbackEntityBody.logicalBody(entry.getValue()).discardFromRestoredRegistry();
            }
        }
        entities.clear(); entities.putAll(state.entities);
        networkIds.clear();
        for (Entity entity : entities.values()) networkIds.put(entity.getEntityId(), entity);
    }
    @Override public Collection<?> rollbackReferences() { checkThread(); return List.copyOf(entities.values()); }

    private static Entity present(Entity entity) { return entity != null && entity.isValid() ? entity : null; }
    private void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Logical entity registry crossed threads");
    }
    private static void requireBounds(BoundingBox box) {
        if (!Double.isFinite(box.getMinX()) || !Double.isFinite(box.getMinY()) || !Double.isFinite(box.getMinZ())
                || !Double.isFinite(box.getMaxX()) || !Double.isFinite(box.getMaxY()) || !Double.isFinite(box.getMaxZ())
                || box.getMinX() > box.getMaxX() || box.getMinY() > box.getMaxY() || box.getMinZ() > box.getMaxZ()) {
            throw new IllegalArgumentException("Entity query bounds");
        }
    }
}
