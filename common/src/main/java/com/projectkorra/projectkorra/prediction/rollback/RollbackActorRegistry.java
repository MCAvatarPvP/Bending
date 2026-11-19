package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.EntityType;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import java.util.*;
import java.util.function.Function;

/** Owns the identities, native tick order and checkpoint membership of actors created by bending. */
public final class RollbackActorRegistry implements RollbackStateCell<RollbackActorRegistry.Checkpoint> {
    public interface Actor<S> extends RollbackStateCell<S> {
        Entity view();
        void tick();
        byte[] exportState();
    }
    public static final class Checkpoint {
        private final RollbackActorRegistry owner;
        private final int cursor;
        private final List<Actor<?>> actors;
        private Checkpoint(RollbackActorRegistry owner) {
            this.owner = owner; cursor = owner.cursor; actors = List.copyOf(owner.actors.values());
        }
    }
    private final Thread thread = Thread.currentThread();
    private final UUID session;
    private final int maximumActors;
    private final NavigableMap<Integer, Actor<?>> actors = new TreeMap<>();
    private int cursor;
    private RollbackWorld world;
    public RollbackActorRegistry(UUID session, int maximumActors) {
        this.session = Objects.requireNonNull(session);
        if (maximumActors < 1 || maximumActors > RollbackActorData.MAXIMUM_ACTORS) throw new IllegalArgumentException("Private actor limit");
        this.maximumActors = maximumActors;
    }
    public RollbackEntityBody.Identity allocate(RollbackWorld target, String name, EntityType type) {
        bind(target); prune();
        if (type == EntityType.PLAYER || actors.size() >= maximumActors) throw new IllegalArgumentException("Private actor limit/type");
        RollbackEntityBody.Identity id;
        do {
            cursor = Math.incrementExact(cursor);
            id = new RollbackEntityBody.Identity(RollbackActorData.uuid(session, cursor), -cursor, name, type);
        } while (world.entities().get(id.uuid()) != null || world.entities().get(id.networkId()) != null);
        return id;
    }
    public <A extends Actor<?>> A own(A actor) {
        check(); var body = RollbackEntityBody.logicalBody(actor.view()); var id = body.identity();
        int sequence = -id.networkId(); bind((RollbackWorld) body.world()); prune();
        if (sequence <= 0 || sequence > cursor || !id.uuid().equals(RollbackActorData.uuid(session, sequence))
                || id.type() == EntityType.PLAYER || actors.size() >= maximumActors || actors.putIfAbsent(sequence, actor) != null)
            throw new IllegalArgumentException("Actor was not allocated by this arena");
        return actor;
    }
    public Actor<?> get(UUID id) {
        check(); if (world == null) return null;
        var found = world.entities().get(id);
        return found == null ? null : actors.get(-found.getEntityId());
    }
    public List<Actor<?>> active() { check(); prune(); return List.copyOf(actors.values()); }

    /** Inspect visible actors without changing membership while a frame's presentation is being recorded. */
    public void forEachActive(java.util.function.Consumer<Actor<?>> reader) {
        check();
        for (var actor : actors.values()) if (actor.view().isValid()) reader.accept(actor);
    }

    public List<RollbackGraphCodec.Binding> bindings() {
        return active().stream().map(actor -> {
            Entity view = actor.view();
            Class<?> contract = view.getClass();
            while (contract != null && (contract.getClassLoader() != Entity.class.getClassLoader()
                    || !contract.getPackageName().equals(Entity.class.getPackageName())))
                contract = contract.getSuperclass();
            if (contract == null || !Entity.class.isAssignableFrom(contract))
                throw new IllegalStateException("Missing shared actor facade contract");
            return new RollbackGraphCodec.Binding("actor/" + view.getUniqueId(), contract, view);
        }).toList();
    }
    public void tick() {
        for (var actor : active()) if (actor.view().isValid()) actor.tick();
        prune();
    }
    private void prune() { actors.values().removeIf(actor -> !actor.view().isValid()); }
    /** Native damage histories can reference actors after they leave active membership. */
    public RollbackActorData history(Collection<RollbackActorData.Row> rows) {
        check();
        if (rows.isEmpty()) return null;
        return new RollbackActorData(session, cursor, rows.stream()
                .sorted(Comparator.comparingInt(row -> -row.identity().networkId())).toList());
    }
    public RollbackActorData exportState() { return exportState(null); }

    public void requireAllocation(RollbackActorData data) {
        check();
        if (!session.equals(data.session()) || cursor != data.cursor()) throw new IllegalArgumentException("Foreign damage history");
    }
    public RollbackActorData exportState(RollbackActorData history) {
        check();
        var captured = new HashMap<UUID, RollbackActorData.Row>();
        if (history != null) {
            requireAllocation(history);
            for (var row : history.actors()) captured.put(row.identity().uuid(), row);
        }
        var rows = active().stream().map(actor -> {
            var identity = RollbackEntityBody.logicalBody(actor.view()).identity();
            var row = captured.get(identity.uuid());
            if (row == null) return new RollbackActorData.Row(identity, actor.exportState());
            if (!row.identity().equals(identity)) throw new IllegalArgumentException("Combat source differs from the active actor");
            return row;
        }).toList();
        return new RollbackActorData(session, cursor, rows);
    }
    /** Decode fresh native actors without publishing them, then atomically exchange private membership. */
    public void importState(RollbackWorld target, RollbackActorData data, Function<RollbackActorData.Row, Actor<?>> decode) {
        bind(target);
        if (!session.equals(data.session()) || data.actors().size() > maximumActors) throw new IllegalArgumentException("Foreign actor repair");
        var replacement = new TreeMap<Integer, Actor<?>>();
        for (var row : data.actors()) {
            var actor = Objects.requireNonNull(decode.apply(row));
            var body = RollbackEntityBody.logicalBody(actor.view());
            if (body.world() != target || !actor.view().isValid() || !body.identity().equals(row.identity()))
                throw new IllegalArgumentException("Decoded private actor identity differs");
            replacement.put(-row.identity().networkId(), actor);
        }
        target.entities().replaceSpawned(actors.values().stream().map(Actor::view).toList(),
                replacement.values().stream().map(Actor::view).toList());
        actors.clear(); actors.putAll(replacement); cursor = data.cursor();
    }
    @Override public Checkpoint captureRollbackState() { check(); return new Checkpoint(this); }
    @Override public void restoreRollbackState(Checkpoint saved) {
        check(); if (saved.owner != this) throw new IllegalArgumentException("Foreign actor registry checkpoint");
        cursor = saved.cursor; actors.clear();
        for (var actor : saved.actors) actors.put(-actor.view().getEntityId(), actor);
    }
    @Override public Collection<?> rollbackReferences() { check(); return List.copyOf(actors.values()); }
    private void bind(RollbackWorld target) {
        check(); if (world == null) world = Objects.requireNonNull(target);
        if (target != world) throw new IllegalArgumentException("Actor belongs to another private world");
    }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Actor registry crossed threads"); }
}
