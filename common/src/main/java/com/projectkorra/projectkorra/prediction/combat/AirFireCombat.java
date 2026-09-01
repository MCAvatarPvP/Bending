package com.projectkorra.projectkorra.prediction.combat;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.hit.HitRegistrationPolicy;

import java.util.*;

/**
 * Short authoritative response window for reactive Air/Fire PvP contacts.
 *
 * <p>Abilities continue to simulate and render immediately. Mutations that a
 * remote player cannot undo (damage, velocity and statuses) are held only for
 * that defender's bounded network uncertainty (one to three ticks). A timely
 * registered ability collision or a real movement escape can cancel that
 * contact before it commits. Nothing in this class decides which
 * abilities counter one another; that remains entirely in {@link
 * com.projectkorra.projectkorra.ability.util.CollisionManager}.</p>
 */
public final class AirFireCombat {
    public static final int MAX_ROLLBACK_TICKS = 3;
    private static final int ACTION_RETENTION_TICKS = 12;
    private static final int JITTER_RETENTION_TICKS = 40;
    private static final int CONFIRMED_CONTACT_TICKS = 3;
    private static final double DODGE_DISTANCE_SQUARED = 0.50 * 0.50;
    private static final ThreadLocal<Integer> COMMIT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final Map<ActionKey, ActionClock> ACTIONS = new HashMap<>();
    private static final Map<UUID, NetworkJitter> NETWORK_JITTER = new HashMap<>();
    private static final Map<CoreAbility, Integer> TIMELINE_OFFSETS = new IdentityHashMap<>();
    private static final Map<CoreAbility, AbilityContacts> CONTACTS = new IdentityHashMap<>();
    private static TimelinePositionProvider timelinePositions =
            (target, ticksAgo) -> target.getBoundingBox().getCenter();
    private static ReactiveCollisionPolicy collisionPolicy = ReactiveCollisionPolicy.ALL;
    private static boolean active;

    private AirFireCombat() {
    }

    /** Enables the authoritative ledger. Exact client runtimes leave it off. */
    public static void activate() {
        active = true;
    }

    /**
     * Enables the ledger with a loader-native position source. Paper uses this
     * to reconstruct a defender at the provisional contact's logical tick,
     * even while an ability query is viewing that defender through a
     * historical interpolation frame.
     */
    public static void activate(final TimelinePositionProvider positions) {
        activate(positions, ReactiveCollisionPolicy.ALL);
    }

    /**
     * Enables rollback only for contacts that the live CollisionManager says
     * can actually be defended. Area fields, grabs and activation-time target
     * effects therefore retain their native hit semantics automatically.
     */
    public static void activate(final TimelinePositionProvider positions,
                                final ReactiveCollisionPolicy policy) {
        timelinePositions = positions == null
                ? (target, ticksAgo) -> target.getBoundingBox().getCenter() : positions;
        collisionPolicy = policy == null ? ReactiveCollisionPolicy.NONE : policy;
        activate();
    }

    /** Drops uncommitted contacts when the authoritative endpoint shuts down. */
    public static void deactivate() {
        if (active) {
            for (final Map.Entry<CoreAbility, AbilityContacts> entry
                    : List.copyOf(CONTACTS.entrySet())) {
                if (entry.getValue().removalRequested && !entry.getKey().isRemoved()) {
                    runCommitted(entry.getKey()::remove);
                }
            }
        }
        active = false;
        ACTIONS.clear();
        NETWORK_JITTER.clear();
        TIMELINE_OFFSETS.clear();
        CONTACTS.clear();
        timelinePositions = (target, ticksAgo) -> target.getBoundingBox().getCenter();
        collisionPolicy = ReactiveCollisionPolicy.ALL;
        COMMIT_DEPTH.remove();
    }

    /**
     * Records the bounded age assigned to one accepted semantic input. Child
     * abilities inherit the same action sequence through PredictionDeterminism.
     */
    public static void registerAction(final UUID owner, final long actionSequence,
                                      final int rollbackTicks) {
        registerAction(owner, actionSequence, rollbackTicks, 0);
    }

    public static void registerAction(final UUID owner, final long actionSequence,
                                      final int rollbackTicks, final int jitterTicks) {
        if (!active || owner == null || actionSequence <= 0L) return;
        final long now = CoreAbility.getCurrentTick();
        ACTIONS.put(new ActionKey(owner, actionSequence), new ActionClock(
                Math.max(0, Math.min(MAX_ROLLBACK_TICKS, rollbackTicks)),
                now));
        // A stable sample must also replace an older spike; otherwise one
        // delayed packet would keep every following contact at the cap.
        NETWORK_JITTER.put(owner, new NetworkJitter(
                Math.max(0, Math.min(MAX_ROLLBACK_TICKS, jitterTicks)), now));
    }

    /** Called by CoreAbility after a real instance enters the live registry. */
    public static void onAbilityStarted(final CoreAbility ability) {
        if (!active || !isTimelineManaged(ability) || ability.getPlayer() == null) return;
        final ActionClock action = ACTIONS.get(new ActionKey(
                ability.getPlayer().getUniqueId(), ability.getPredictionActionSequence()));
        final int rollbackTicks = action == null ? 0 : action.rollbackTicks;
        TIMELINE_OFFSETS.put(ability, rollbackTicks);
        if (rollbackTicks > 0) ability.alignPredictedStart(rollbackTicks * 50L);
    }

    /** Logical age used by CollisionManager to align two players' simulations. */
    public static int timelineOffset(final CoreAbility ability) {
        if (!active || ability == null) return 0;
        return TIMELINE_OFFSETS.getOrDefault(ability, 0);
    }

    /** True while an entity-impact removal is waiting for contact resolution. */
    public static boolean isSuspended(final CoreAbility ability) {
        final AbilityContacts contacts = CONTACTS.get(ability);
        return contacts != null && contacts.removalRequested;
    }

    /**
     * Holds removal only when this ability already produced a provisional PvP
     * contact. Range, duration, administrative and ordinary collision removals
     * retain their existing immediate behavior.
     */
    public static boolean deferRemoval(final CoreAbility ability) {
        if (!active || isCommitting() || ability == null) return false;
        final AbilityContacts contacts = CONTACTS.get(ability);
        if (contacts == null || contacts.pending.isEmpty()) return false;
        contacts.removalRequested = true;
        return true;
    }

    public static boolean deferDamage(final Ability ability, final Entity target,
                                      final Runnable mutation) {
        return defer(EffectKind.DAMAGE, ability, target, mutation);
    }

    public static boolean deferVelocity(final Ability ability, final Entity target,
                                        final Runnable mutation) {
        return defer(EffectKind.VELOCITY, ability, target, mutation);
    }

    public static boolean deferStatus(final Entity target, final Runnable mutation) {
        return defer(EffectKind.STATUS, AbilityExecutionContext.current(), target, mutation);
    }

    public static boolean deferAuxiliary(final Ability ability, final Entity target,
                                         final Runnable mutation) {
        return defer(EffectKind.AUXILIARY, ability, target, mutation);
    }

    private static boolean defer(final EffectKind kind, final Ability source,
                                 final Entity target, final Runnable mutation) {
        final CoreAbility executing = AbilityExecutionContext.current();
        final CoreAbility ability = executing != null ? executing
                : source instanceof CoreAbility core ? core : null;
        if (!active || isCommitting() || mutation == null || ability == null
                // Constructor/activation-time effects such as targeted lifts
                // are authoritative selections, not projectile contacts.
                || executing == null || !(target instanceof Player) || !isReactive(ability)
                || !collisionPolicy.canBeRemoved(ability)
                || ability.getPlayer() == null
                || ability.getPlayer().getUniqueId().equals(target.getUniqueId())) {
            return false;
        }

        final long now = simulationTick();
        final AbilityContacts contacts = CONTACTS.computeIfAbsent(ability,
                ignored -> new AbilityContacts());
        final long confirmedUntil = contacts.confirmedUntil.getOrDefault(target.getUniqueId(), -1L);
        if (confirmedUntil >= now) return false;

        PendingContact contact = contacts.pending.get(target.getUniqueId());
        if (contact == null) {
            // Inside ability progression this is the exact server-reconstructed
            // player frame used by the contact query, not the later live pose.
            final Vector center = target.getBoundingBox().getCenter();
            final int responseTicks = responseTicks(target, now);
            contact = new PendingContact(target, target.getWorld(), center.getX(), center.getY(),
                    center.getZ(), now,
                    now - timelineOffset(ability), now + responseTicks);
            contacts.pending.put(target.getUniqueId(), contact);
        }
        // Repeated area scans must not multiply the same provisional hit while
        // the response window is open. Preserve every mutation from the first
        // contact tick in its original call order.
        if (contact.contactTick == now) {
            contact.effects.add(new DeferredEffect(kind, mutation));
        }
        return true;
    }

    /**
     * Runs a collision already selected by CollisionManager. Removal flags are
     * the live event-modified flags, so plugin cancellation and overrides keep
     * exactly the same authority they had before rollback was introduced.
     */
    public static void resolveCollision(final Collision collision, final long collisionTimelineTick,
                                        final Runnable resolution) {
        if (collision == null || resolution == null || !active
                || !isTimelineManaged(collision.getAbilityFirst())
                || !isTimelineManaged(collision.getAbilitySecond())) {
            if (resolution != null) resolution.run();
            return;
        }

        final CoreAbility first = collision.getAbilityFirst();
        final CoreAbility second = collision.getAbilitySecond();
        if (collision.isRemovingFirst()) cancel(first, collisionTimelineTick);
        if (collision.isRemovingSecond()) cancel(second, collisionTimelineTick);
        runCommitted(resolution);
        // Custom handlers are allowed to remove an ability even when the
        // registry's default flag is false. Treat that as the same accepted
        // defensive collision, without encoding an ability-specific pair.
        if (first.isRemoved()) cancel(first, collisionTimelineTick);
        if (second.isRemoved()) cancel(second, collisionTimelineTick);
    }

    /** Commits expired contacts after CollisionManager has resolved this tick. */
    public static void tick() {
        if (!active) return;
        final long now = CoreAbility.getCurrentTick();
        final List<Map.Entry<CoreAbility, AbilityContacts>> abilities =
                new ArrayList<>(CONTACTS.entrySet());
        abilities.sort(Comparator
                .comparing((Map.Entry<CoreAbility, AbilityContacts> entry) -> owner(entry.getKey()))
                .thenComparing(entry -> entry.getKey().getClass().getName())
                .thenComparingInt(entry -> entry.getKey().getId()));

        for (final Map.Entry<CoreAbility, AbilityContacts> abilityEntry : abilities) {
            final CoreAbility ability = abilityEntry.getKey();
            final AbilityContacts contacts = abilityEntry.getValue();
            final List<PendingContact> due = contacts.pending.values().stream()
                    // Only a contact that also retired its source is a
                    // one-shot rollback candidate. Persistent fields and
                    // streams have already advanced their own hit ledgers, so
                    // holding only their external mutation would desync them.
                    .filter(contact -> !contacts.removalRequested
                            || contact.deadlineTick <= now)
                    .sorted(Comparator.comparing(contact -> contact.target.getUniqueId().toString()))
                    .toList();
            for (final PendingContact contact : due) {
                contacts.pending.remove(contact.target.getUniqueId());
                // A non-retiring contact was authoritatively observed during
                // this tick and only waited for CollisionManager ordering. The
                // historical dodge test belongs solely to one-shot contacts.
                if (!contacts.removalRequested || stillAContact(contact)) {
                    contacts.acceptedContact = true;
                    runCommitted(() -> AbilityExecutionContext.run(ability, () -> {
                        for (final DeferredEffect effect : contact.effects) effect.mutation.run();
                    }));
                    contacts.confirmedUntil.put(contact.target.getUniqueId(),
                            now + CONFIRMED_CONTACT_TICKS);
                }
            }

            contacts.confirmedUntil.entrySet().removeIf(entry -> entry.getValue() < now);
            if (contacts.removalRequested && contacts.pending.isEmpty() && !ability.isRemoved()) {
                if (contacts.acceptedContact) runCommitted(ability::remove);
                else contacts.removalRequested = false;
            }
            if (contacts.pending.isEmpty() && contacts.confirmedUntil.isEmpty()) {
                CONTACTS.remove(ability);
            }
        }

        ACTIONS.entrySet().removeIf(entry -> now - entry.getValue().registeredTick
                > ACTION_RETENTION_TICKS);
        NETWORK_JITTER.entrySet().removeIf(entry -> now - entry.getValue().observedTick
                > JITTER_RETENTION_TICKS);
        TIMELINE_OFFSETS.keySet().removeIf(CoreAbility::isRemoved);
    }

    private static boolean stillAContact(final PendingContact contact) {
        final Entity target = contact.target;
        if (target == null || !target.isValid() || target.isDead()
                || target.getWorld() != contact.world) return false;
        final Vector center = timelineCenter(target, contact.timelineContactTick);
        // Missing history cannot prove an escape. Confirming the already
        // authoritative contact is safer than consulting a later live pose.
        if (center == null) return true;
        final double dx = center.getX() - contact.centerX;
        final double dy = center.getY() - contact.centerY;
        final double dz = center.getZ() - contact.centerZ;
        return dx * dx + dy * dy + dz * dz <= DODGE_DISTANCE_SQUARED;
    }

    private static void cancel(final CoreAbility ability, final long collisionTimelineTick) {
        if (ability == null) return;
        final AbilityContacts contacts = CONTACTS.get(ability);
        if (contacts == null) return;
        final boolean cancelled = contacts.pending.values().removeIf(contact ->
                collisionTimelineTick <= contact.timelineContactTick);
        if (!cancelled) return;
        contacts.removalRequested = false;
        if (contacts.pending.isEmpty() && contacts.confirmedUntil.isEmpty()) {
            CONTACTS.remove(ability);
        }
    }

    private static int responseTicks(final Entity target, final long now) {
        final NetworkJitter jitter = target == null ? null
                : NETWORK_JITTER.get(target.getUniqueId());
        final int jitterTicks = jitter != null
                && now - jitter.observedTick <= JITTER_RETENTION_TICKS ? jitter.ticks : 0;
        final int ping = target instanceof Player player ? player.getPing() : 0;
        return CombatNetworkTiming.responseTicks(ping, jitterTicks, MAX_ROLLBACK_TICKS);
    }

    private static long simulationTick() {
        return CoreAbility.getCurrentTick()
                + (AbilityExecutionContext.current() == null ? 0L : 1L);
    }

    private static boolean isReactive(final CoreAbility ability) {
        return ability != null && HitRegistrationPolicy.forAbility(ability)
                == HitRegistrationPolicy.SERVER_CURRENT;
    }

    /** True only for Air/Fire abilities participating in a live registered pair. */
    public static boolean isTimelineManaged(final CoreAbility ability) {
        return active && isReactive(ability) && collisionPolicy.participates(ability);
    }

    private static Vector timelineCenter(final Entity target, final long timelineTick) {
        try {
            final Vector center = timelinePositions.center(target, timelineTick);
            if (center != null) return center;
        } catch (final RuntimeException ignored) {
            // A platform adapter failure must not turn a later pose into a
            // retroactive dodge.
        }
        return null;
    }

    private static String owner(final CoreAbility ability) {
        return ability == null || ability.getPlayer() == null
                ? "" : ability.getPlayer().getUniqueId().toString();
    }

    private static boolean isCommitting() {
        return COMMIT_DEPTH.get() > 0;
    }

    private static void runCommitted(final Runnable mutation) {
        final int previous = COMMIT_DEPTH.get();
        COMMIT_DEPTH.set(previous + 1);
        try {
            mutation.run();
        } finally {
            if (previous == 0) COMMIT_DEPTH.remove();
            else COMMIT_DEPTH.set(previous);
        }
    }

    private enum EffectKind {
        DAMAGE,
        VELOCITY,
        STATUS,
        AUXILIARY
    }

    @FunctionalInterface
    public interface TimelinePositionProvider {
        Vector center(Entity target, long timelineTick);
    }

    public interface ReactiveCollisionPolicy {
        ReactiveCollisionPolicy ALL = new ReactiveCollisionPolicy() {
            @Override public boolean participates(final CoreAbility ability) { return true; }
            @Override public boolean canBeRemoved(final CoreAbility ability) { return true; }
        };
        ReactiveCollisionPolicy NONE = new ReactiveCollisionPolicy() {
            @Override public boolean participates(final CoreAbility ability) { return false; }
            @Override public boolean canBeRemoved(final CoreAbility ability) { return false; }
        };

        boolean participates(CoreAbility ability);

        boolean canBeRemoved(CoreAbility ability);
    }

    private record ActionKey(UUID owner, long sequence) {
    }

    private record ActionClock(int rollbackTicks, long registeredTick) {
    }

    private record NetworkJitter(int ticks, long observedTick) {
    }

    private record DeferredEffect(EffectKind kind, Runnable mutation) {
    }

    private static final class AbilityContacts {
        private final Map<UUID, PendingContact> pending = new HashMap<>();
        private final Map<UUID, Long> confirmedUntil = new HashMap<>();
        private boolean removalRequested;
        private boolean acceptedContact;
    }

    private static final class PendingContact {
        private final Entity target;
        private final World world;
        private final double centerX;
        private final double centerY;
        private final double centerZ;
        private final long contactTick;
        private final long timelineContactTick;
        private final long deadlineTick;
        private final List<DeferredEffect> effects = new ArrayList<>();

        private PendingContact(final Entity target, final World world,
                               final double centerX, final double centerY, final double centerZ,
                               final long contactTick, final long timelineContactTick,
                               final long deadlineTick) {
            this.target = target;
            this.world = world;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.contactTick = contactTick;
            this.timelineContactTick = timelineContactTick;
            this.deadlineTick = deadlineTick;
        }
    }
}
