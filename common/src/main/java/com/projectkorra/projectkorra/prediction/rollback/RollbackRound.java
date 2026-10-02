package com.projectkorra.projectkorra.prediction.rollback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Shared Neptune duel decisions for the authoritative server and predicting client.
 * Replay never calls live match code or a scheduler. Only the session's confirmed
 * frontier may publish irreversible results. Checkpoints exclude delivery receipts.
 */
public final class RollbackRound implements RollbackStateCell<RollbackRound.Checkpoint> {
    public record Defeat(UUID session, long tick, UUID player, UUID lastAttacker) { }
    public enum DamageResult { ALLOW, CANCEL, DEFEAT }

    private final Thread thread = Thread.currentThread();
    private final UUID session;
    private final Map<UUID, UUID> sides;
    private final Set<UUID> defeated = new LinkedHashSet<>();
    private final Map<UUID, UUID> attackers = new LinkedHashMap<>();
    private final List<Defeat> defeats = new ArrayList<>();
    // Irreversible delivery state is deliberately outside checkpoints.
    private final List<Defeat> finalized = new ArrayList<>();
    private long tick, confirmed;
    private boolean publishing, failed, ended;

    /** Each player maps to its captured side; solo opponents have distinct sides. */
    public RollbackRound(UUID session, Map<UUID, UUID> sides) {
        this.session = Objects.requireNonNull(session, "session");
        this.sides = Map.copyOf(sides);
        if (sides.size() < 2 || sides.size() > 128 || Set.copyOf(sides.values()).size() < 2) {
            throw new IllegalArgumentException("A rollback round requires opposing participants");
        }
    }

    public void beginTick(long value) {
        check();
        if (value != tick + 1) throw new IllegalArgumentException("Round ticks must advance one step at a time");
        tick = value;
    }

    /** Called after other private damage modifiers/cancellation, using replica health. */
    public DamageResult damage(UUID victim, UUID attacker, double health, double finalDamage,
                               boolean holdingTotem, boolean cancelled) {
        check(); participant(victim);
        if (attacker != null) participant(attacker);
        if (tick == 0 || tick <= confirmed) throw new IllegalStateException("Damage requires an unconfirmed simulation tick");
        if (!Double.isFinite(health) || !Double.isFinite(finalDamage)) throw new IllegalArgumentException("Non-finite private damage state");
        // Friendly-fire and ability damage modifiers are resolved upstream by
        // the captured match rules, just as before MatchListener.onDamage.
        if (cancelled || ended || defeated.contains(victim) || (attacker != null && defeated.contains(attacker))) return DamageResult.CANCEL;
        if (attacker != null) attackers.put(victim, attacker);
        if (!endsLife(health, finalDamage, holdingTotem)) return DamageResult.ALLOW;
        defeated.add(victim);
        defeats.add(new Defeat(session, tick, victim, attackers.get(victim)));
        ended = sides.entrySet().stream().filter(entry -> !defeated.contains(entry.getKey()))
                .map(Map.Entry::getValue).distinct().limit(2).count() < 2;
        return DamageResult.DEFEAT;
    }

    public Set<UUID> participants() { check(); return sides.keySet(); }
    public boolean active(UUID player) { check(); participant(player); return !ended && !defeated.contains(player); }
    public long tick() { check(); return tick; }
    public boolean ended() { check(); return ended; }
    public List<Defeat> provisionalDefeats() { check(); return List.copyOf(defeats.subList(finalized.size(), defeats.size())); }

    /** Matches Neptune's ordinary lethal-hit rule, including the totem exception. */
    public static boolean endsLife(double health, double finalDamage, boolean holdingTotem) {
        return !(finalDamage <= 0 || finalDamage < health || holdingTotem);
    }

    @Override public Checkpoint captureRollbackState() { return snapshot(); }
    @Override public void restoreRollbackState(Checkpoint state) { restore(state); }

    public Checkpoint snapshot() { check(); return new Checkpoint(this, tick, Set.copyOf(defeated), Map.copyOf(attackers), List.copyOf(defeats), ended); }
    public void restore(Checkpoint saved) {
        check();
        if (Objects.requireNonNull(saved, "checkpoint").owner != this) throw new IllegalArgumentException("Foreign round checkpoint");
        if (saved.tick < confirmed || saved.defeats.size() < finalized.size()
                || !saved.defeats.subList(0, finalized.size()).equals(finalized)
                || saved.defeats.stream().filter(defeat -> defeat.tick() <= confirmed).count() != finalized.size()) {
            throw new IllegalStateException("A round cannot rewrite a finalized defeat");
        }
        tick = saved.tick; ended = saved.ended;
        defeated.clear(); defeated.addAll(saved.defeated);
        attackers.clear(); attackers.putAll(saved.attackers);
        defeats.clear(); defeats.addAll(saved.defeats);
    }

    /** Only the rollback engine's confirmed tick may cross this irreversible boundary. */
    public void finalizeThrough(long frontier, Consumer<Defeat> delivery) {
        check(); Objects.requireNonNull(delivery, "delivery");
        if (frontier < confirmed || frontier > tick) throw new IllegalArgumentException("Invalid confirmed frontier");
        confirmed = frontier;
        publishing = true;
        try {
            while (finalized.size() < defeats.size()) {
                var defeat = defeats.get(finalized.size());
                if (defeat.tick() > frontier) break;
                // If a live side effect fails halfway through, abort the session;
                // retrying Match.onDeath could duplicate statistics or round tasks.
                finalized.add(defeat);
                delivery.accept(defeat);
            }
        } catch (RuntimeException | Error failure) { failed = true; throw failure; }
        finally { publishing = false; }
    }

    private void participant(UUID player) {
        if (!sides.containsKey(Objects.requireNonNull(player, "player"))) throw new IllegalArgumentException("Player is outside the captured round");
    }
    private void check() {
        if (Thread.currentThread() != thread || publishing || failed) throw new IllegalStateException("Round is outside its simulation thread, publishing or failed");
    }
    public static final class Checkpoint {
        private final RollbackRound owner;
        private final long tick;
        private final Set<UUID> defeated;
        private final Map<UUID, UUID> attackers;
        private final List<Defeat> defeats;
        private final boolean ended;
        private Checkpoint(RollbackRound owner, long tick, Set<UUID> defeated, Map<UUID, UUID> attackers, List<Defeat> defeats, boolean ended) {
            this.owner = owner; this.tick = tick; this.defeated = defeated; this.attackers = attackers; this.defeats = defeats; this.ended = ended;
        }
    }
}
