package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.ProjectileOwner;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongUnaryOperator;
import java.util.function.Predicate;

/** Pairs native projectiles with the predicted launch of the same action and caster. */
public final class ClientProjectilePairs<E> {
    private record Key(long localAction, int ordinal) { }
    private record Launch<E>(UUID owner, String type, String ability, E entity) { }
    private record Receipt(ProjectileOwner owner, long tick) { }
    private record Pair<E>(ProjectileOwner owner, Key key, E entity) { }
    private final Map<Key, Launch<E>> launches = new HashMap<>();
    private final Map<Integer, Receipt> pending = new HashMap<>();
    private final Map<Integer, Pair<E>> pairs = new HashMap<>();
    private final Predicate<E> alive;

    ClientProjectilePairs(Predicate<E> alive) { this.alive = alive; }

    void launch(long action, int ordinal, UUID owner, String type, String ability, E entity) {
        if (action > 0 && ordinal > 0 && type != null && entity != null)
            launches.putIfAbsent(new Key(action, ordinal), new Launch<>(owner, type, ability, entity));
    }

    void receipt(ProjectileOwner owner, long tick) {
        if (owner.actionSequence() <= 0 || owner.ordinal() <= 0 || owner.entityId() < 0
                || !(owner.type().equals("ARROW") || owner.type().equals("SNOWBALL") || owner.type().equals("AREA_EFFECT_CLOUD"))) return;
        Pair<E> existing = pairs.get(owner.entityId());
        if (existing != null && existing.owner.entityUuid().equals(owner.entityUuid())) return;
        if (existing != null) pairs.remove(owner.entityId());
        pending.put(owner.entityId(), new Receipt(owner, tick));
    }

    private Pair<E> resolve(int id, LongUnaryOperator localSequence) {
        Pair<E> pair = pairs.get(id);
        if (pair != null) return pair;
        Receipt receipt = pending.get(id);
        if (receipt == null) return null;
        ProjectileOwner owner = receipt.owner;
        Key key = new Key(localSequence.applyAsLong(owner.actionSequence()), owner.ordinal());
        Launch<E> launch = launches.get(key);
        if (launch == null || !launch.owner.equals(owner.owner()) || !launch.type.equals(owner.type())
                || !launch.ability.equalsIgnoreCase(owner.ability())) return null;
        launches.remove(key);
        pending.remove(id);
        pair = new Pair<>(owner, key, launch.entity);
        pairs.put(id, pair);
        return pair;
    }

    boolean hide(int id, UUID uuid, String type, LongUnaryOperator localSequence) {
        Pair<E> pair = resolve(id, localSequence);
        return pair != null && pair.owner.entityUuid().equals(uuid) && pair.owner.type().equals(type)
                && alive.test(pair.entity);
    }

    E remove(int id, LongUnaryOperator localSequence) {
        Pair<E> pair = resolve(id, localSequence);
        pairs.remove(id);
        pending.remove(id);
        return pair == null ? null : pair.entity;
    }

    void expire(long minTick) { pending.values().removeIf(receipt -> receipt.tick < minTick); }
    void retire(long action) { launches.keySet().removeIf(key -> key.localAction == action); }
    void rollback(long action) {
        retire(action);
        pairs.values().removeIf(pair -> pair.key.localAction == action);
    }
    void clear() { launches.clear(); pairs.clear(); pending.clear(); }
}
