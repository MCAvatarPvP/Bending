package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ProjectileImpact;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ProjectileOwner;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

/** Provisional clouds follow the final Paper impact of the specific snowball that created them. */
final class ClientImpactClouds<E> {
    private record Key(long action, int ordinal, UUID owner) { }
    private record Timed<T>(T data, long tick) { }
    private static final class Shot<E> {
        final Key key;
        final List<E> clouds = new ArrayList<>();
        Boolean accepted;
        Shot(Key key) { this.key = key; }
    }
    private final Map<Key, Shot<E>> shots = new HashMap<>();
    private final Map<E, Shot<E>> sources = new IdentityHashMap<>();
    private final Map<UUID, Timed<ProjectileOwner>> owners = new HashMap<>();
    private final Map<UUID, Timed<ProjectileImpact>> outcomes = new HashMap<>();
    private final Consumer<E> discard;
    private final LongUnaryOperator localSequence;
    ClientImpactClouds(Consumer<E> discard, LongUnaryOperator localSequence) {
        this.discard = discard;
        this.localSequence = localSequence;
    }

    void launch(long action, int ordinal, UUID owner, E source) {
        var shot = new Shot<E>(new Key(action, ordinal, owner));
        shots.put(shot.key, shot);
        sources.put(source, shot);
    }

    void cloud(E source, E cloud) {
        Shot<E> shot = sources.get(source);
        if (shot == null) return;
        if (Boolean.FALSE.equals(shot.accepted)) discard.accept(cloud);
        else if (shot.accepted == null) shot.clouds.add(cloud);
    }

    void owner(ProjectileOwner owner, long tick) {
        if (owner.type().equals("SNOWBALL")) {
            owners.put(owner.entityUuid(), new Timed<>(owner, tick));
            reconcile();
        }
    }

    void outcome(ProjectileImpact outcome, long tick) {
        outcomes.put(outcome.entityUuid(), new Timed<>(outcome, tick));
        reconcile();
    }

    private void reconcile() {
        var pending = outcomes.entrySet().iterator();
        while (pending.hasNext()) {
            var item = pending.next();
            var known = owners.get(item.getKey());
            if (known == null) continue;
            var outcome = item.getValue().data;
            var owner = known.data;
            if (owner.actionSequence() != outcome.actionSequence() || owner.entityId() != outcome.entityId()
                    || owner.ordinal() != outcome.ordinal() || !owner.owner().equals(outcome.owner())
                    || !owner.sessionId().equals(outcome.sessionId())) {
                pending.remove();
                continue;
            }
            var shot = shots.get(new Key(localSequence.applyAsLong(owner.actionSequence()), owner.ordinal(), owner.owner()));
            if (shot == null) continue;
            if (shot.accepted == null) {
                shot.accepted = outcome.createdCloud();
                if (!shot.accepted) shot.clouds.forEach(discard);
                shot.clouds.clear();
            }
            pending.remove();
        }
    }

    void expire(long oldestTick) {
        reconcile();
        outcomes.values().removeIf(receipt -> receipt.tick < oldestTick);
        owners.values().removeIf(receipt -> receipt.tick < oldestTick);
    }

    void retire(long action) {
        shots.keySet().removeIf(key -> key.action == action);
        sources.values().removeIf(shot -> shot.key.action == action);
    }

    void clear() {
        sources.clear(); shots.clear(); owners.clear(); outcomes.clear();
    }
}
