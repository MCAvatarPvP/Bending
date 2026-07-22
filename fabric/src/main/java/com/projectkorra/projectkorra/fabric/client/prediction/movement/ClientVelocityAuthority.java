package com.projectkorra.projectkorra.fabric.client.prediction.movement;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import com.projectkorra.projectkorra.prediction.movement.ExternalVelocityFence;
import com.projectkorra.projectkorra.prediction.movement.VelocityReceiptPolicy;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.LongUnaryOperator;

/**
 * Reconciles predicted velocity writes with Paper's ownership receipts.
 *
 * <p>The class is deliberately unaware of abilities and input actions. The
 * runtime supplies their stable action/ordinal identity when recording a
 * write, while this component exclusively owns impulse ordering and the
 * external-authority fence.</p>
 */
public final class ClientVelocityAuthority {
    private final List<Mutation> mutations = new ArrayList<>();
    private final List<Receipt> receipts = new ArrayList<>();
    private final ExternalVelocityFence<PendingExternalVelocity> externalFence =
            new ExternalVelocityFence<>();
    private final Consumer<String> debug;

    public ClientVelocityAuthority(final Consumer<String> debug) {
        this.debug = debug == null ? ignored -> { } : debug;
    }

    public boolean blocksPredictedWrite(final int entityId) {
        return externalFence.blocksPredictedWrite(entityId);
    }

    public void predict(final Entity entity, final Vec3d velocity,
                        final long actionSequence, final int impulseOrdinal,
                        final String ability, final long tick) {
        if (entity == null || velocity == null || !finite(velocity)
                || !(entity.getEntityWorld() instanceof ClientWorld world)) return;
        mutations.add(new Mutation(world, entity.getId(), actionSequence, impulseOrdinal,
                ability, tick, entity.getVelocity(), velocity));
        entity.setVelocity(velocity);
        entity.velocityDirty = true;
    }

    /** @return true when vanilla must suppress the following velocity packet. */
    public boolean acceptAuthoritative(final int entityId, final Vec3d velocity,
                                       final long tick, final UUID localPlayer,
                                       final IntPredicate livePredictedWriter) {
        if (!finite(velocity)) return false;
        if (!receipts.isEmpty()) {
            int receiptIndex = -1;
            for (int index = 0; index < receipts.size(); index++) {
                if (receipts.get(index).entityId == entityId) {
                    receiptIndex = index;
                    break;
                }
            }
            if (receiptIndex < 0) {
                return stageUnowned(entityId, velocity, tick,
                        livePredictedWriter, "unpaired-receipt");
            }
            final Receipt receipt = receipts.remove(receiptIndex);
            final boolean selfOwned = localPlayer != null
                    && localPlayer.equals(receipt.abilityOwner);
            if (selfOwned) {
                externalFence.release(entityId);
                for (int index = 0; index < mutations.size(); index++) {
                    final Mutation mutation = mutations.get(index);
                    if (mutation.entityId != entityId
                            || mutation.actionSequence != receipt.actionSequence
                            || mutation.impulseOrdinal != receipt.impulseOrdinal) continue;
                    mutations.remove(index);
                    debug.accept("runtime suppressed exactly-owned authoritative velocity action="
                            + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                            + " ability=" + receipt.ability);
                    return true;
                }
                final boolean superseded = mutations.stream().anyMatch(mutation ->
                        mutation.entityId == entityId
                                && (mutation.actionSequence > receipt.actionSequence
                                || mutation.actionSequence == receipt.actionSequence
                                && mutation.impulseOrdinal > receipt.impulseOrdinal));
                if (superseded) {
                    debug.accept("runtime suppressed superseded self-owned velocity action="
                            + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                            + " ability=" + receipt.ability);
                    return true;
                }
                debug.accept("runtime allowed self-owned velocity without retained mutation action="
                        + receipt.actionSequence + " ordinal=" + receipt.impulseOrdinal
                        + " ability=" + receipt.ability);
                return false;
            }
            debug.accept("runtime allowed externally-owned authoritative velocity owner="
                    + receipt.abilityOwner + " action=" + receipt.actionSequence
                    + " ability=" + receipt.ability);
            return stageExternal(entityId, velocity, receipt.serverTick, receipt.ability,
                    tick, livePredictedWriter, "owned-external");
        }
        return stageUnowned(entityId, velocity, tick, livePredictedWriter, "no-receipt");
    }

    public void recordOwner(final Entity localPlayer,
                            final PredictionPayloads.VelocityOwner owner,
                            final long receivedTick,
                            final LongUnaryOperator correlateAction) {
        if (owner == null) return;
        final int targetEntityId = localPlayer != null
                && localPlayer.getUuid().equals(owner.target()) ? localPlayer.getId() : -1;
        recordOwner(localPlayer, owner.serverTick(), owner.actionSequence(), owner.impulseOrdinal(),
                owner.abilityOwner(), targetEntityId, owner.ability(), receivedTick,
                correlateAction);
    }

    public void recordOwner(final Entity localPlayer,
                            final PredictionPayloads.VelocityOwnerV2 owner,
                            final long receivedTick,
                            final LongUnaryOperator correlateAction) {
        if (owner == null) return;
        recordOwner(localPlayer, owner.serverTick(), owner.actionSequence(), owner.impulseOrdinal(),
                owner.abilityOwner(), owner.targetEntityId(), owner.ability(), receivedTick,
                correlateAction);
    }

    public void afterLocalProgress(final ClientWorld world, final long tick,
                                   final IntPredicate livePredictedWriter) {
        if (world == null) return;
        for (Integer entityId : externalFence.entityIds()) {
            final boolean wasCommitted = externalFence.isCommitted(entityId);
            final boolean retained = externalFence.isRetained(entityId);
            final Optional<PendingExternalVelocity> pending =
                    externalFence.afterLocalProgress(entityId, tick);
            if (pending.isPresent()) {
                final PendingExternalVelocity external = pending.get();
                final Entity entity = world.getEntityById(entityId);
                if (entity != null && finite(external.velocity)) {
                    entity.setVelocity(external.velocity);
                    entity.velocityDirty = true;
                    debug.accept("runtime committed externally-owned velocity after local progress entity="
                            + entityId + " serverTick=" + external.serverTick
                            + " ability=" + external.ability);
                }
            }
            if (wasCommitted && retained && !livePredictedWriter.test(entityId)) {
                externalFence.release(entityId);
                debug.accept("runtime released external velocity fence after its local writer ended entity="
                        + entityId);
            }
        }
    }

    public boolean hasMutation(final int entityId, final Long actionSequence,
                               final String ability) {
        for (Mutation mutation : mutations) {
            if (mutation.entityId != entityId) continue;
            if (actionSequence != null && actionSequence > 0L
                    && mutation.actionSequence == actionSequence) return true;
            if (ability != null && ability.equalsIgnoreCase(mutation.ability)) return true;
        }
        return false;
    }

    public boolean tracks(final int entityId, final int localEntityId) {
        if (entityId < 0) return false;
        return localEntityId == entityId
                || mutations.stream().anyMatch(mutation -> mutation.entityId == entityId)
                || receipts.stream().anyMatch(receipt -> receipt.entityId == entityId)
                || externalFence.blocksPredictedWrite(entityId);
    }

    public void rollbackAction(final long actionSequence) {
        for (int index = mutations.size() - 1; index >= 0; index--) {
            final Mutation mutation = mutations.get(index);
            if (mutation.actionSequence != actionSequence) continue;
            final Entity entity = mutation.world.getEntityById(mutation.entityId);
            if (entity != null && close(entity.getVelocity(), mutation.predicted, 0.08)) {
                entity.setVelocity(mutation.before);
            }
            mutations.remove(index);
        }
    }

    public void expire(final long tick, final int actionRetentionTicks,
                       final int receiptRetentionTicks) {
        mutations.removeIf(mutation -> tick - mutation.tick > actionRetentionTicks);
        receipts.removeIf(receipt -> tick - receipt.receivedTick > receiptRetentionTicks);
    }

    public int mutationCount() {
        return mutations.size();
    }

    public void clear() {
        mutations.clear();
        receipts.clear();
        externalFence.clear();
    }

    private void recordOwner(final Entity localPlayer, final long serverTick,
                             final long paperActionSequence, final int impulseOrdinal,
                             final UUID abilityOwner, final int targetEntityId,
                             final String ability, final long receivedTick,
                             final LongUnaryOperator correlateAction) {
        if (localPlayer == null) return;
        final boolean locallyOwned = localPlayer.getUuid().equals(abilityOwner);
        if (!VelocityReceiptPolicy.accepts(
                locallyOwned, paperActionSequence, impulseOrdinal, targetEntityId)) return;
        final long localActionSequence = locallyOwned && correlateAction != null
                ? correlateAction.applyAsLong(paperActionSequence) : paperActionSequence;
        if (locallyOwned && localActionSequence <= 0L) {
            debug.accept("runtime ignored uncorrelated self velocity paperAction="
                    + paperActionSequence + " ordinal=" + impulseOrdinal
                    + " ability=" + ability);
            return;
        }
        int replacement = -1;
        for (int index = 0; index < receipts.size(); index++) {
            final Receipt receipt = receipts.get(index);
            if (receipt.serverTick == serverTick && receipt.entityId == targetEntityId) {
                replacement = index;
                break;
            }
        }
        final Receipt receipt = new Receipt(serverTick, localActionSequence, targetEntityId,
                impulseOrdinal, abilityOwner, ability, receivedTick);
        if (replacement >= 0) receipts.set(replacement, receipt);
        else receipts.add(receipt);
        debug.accept("runtime received velocity owner paperAction=" + paperActionSequence
                + " localAction=" + localActionSequence + " ordinal=" + impulseOrdinal
                + " ability=" + ability);
    }

    private boolean stageUnowned(final int entityId, final Vec3d velocity, final long tick,
                                 final IntPredicate livePredictedWriter, final String reason) {
        final boolean staged = stageExternal(entityId, velocity, tick, "<unowned>", tick,
                livePredictedWriter, reason);
        if (!staged) {
            debug.accept("runtime allowed unowned authoritative velocity packet="
                    + vector(velocity) + " reason=" + reason
                    + " pendingReceipts=" + receipts.size());
        }
        return staged;
    }

    private boolean stageExternal(final int entityId, final Vec3d velocity,
                                  final long serverTick, final String ability,
                                  final long tick, final IntPredicate livePredictedWriter,
                                  final String reason) {
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null || player.getId() != entityId) return false;
        final boolean liveWriter = livePredictedWriter != null
                && livePredictedWriter.test(entityId);
        externalFence.receive(entityId,
                new PendingExternalVelocity(velocity, serverTick, ability, tick), liveWriter);
        debug.accept("runtime staged authoritative external/unowned velocity after local progress entity="
                + entityId + " serverTick=" + serverTick + " ability=" + ability
                + " reason=" + reason + " liveWriter=" + liveWriter
                + " velocity=" + vector(velocity));
        return true;
    }

    private static boolean finite(final Vec3d vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean close(final Vec3d first, final Vec3d second,
                                 final double tolerance) {
        return first != null && second != null
                && first.squaredDistanceTo(second) <= tolerance * tolerance;
    }

    private static String vector(final Vec3d velocity) {
        if (velocity == null) return "<null>";
        return String.format(Locale.ROOT, "(%.4f, %.4f, %.4f)",
                velocity.x, velocity.y, velocity.z);
    }

    private static final class Mutation {
        private final ClientWorld world;
        private final int entityId;
        private final long actionSequence;
        private final int impulseOrdinal;
        private final String ability;
        private final long tick;
        private final Vec3d before;
        private final Vec3d predicted;

        private Mutation(final ClientWorld world, final int entityId,
                         final long actionSequence, final int impulseOrdinal,
                         final String ability, final long tick,
                         final Vec3d before, final Vec3d predicted) {
            this.world = world;
            this.entityId = entityId;
            this.actionSequence = actionSequence;
            this.impulseOrdinal = impulseOrdinal;
            this.ability = ability == null ? "" : ability;
            this.tick = tick;
            this.before = before;
            this.predicted = predicted;
        }
    }

    private record Receipt(long serverTick, long actionSequence, int entityId,
                           int impulseOrdinal, UUID abilityOwner, String ability,
                           long receivedTick) { }

    private record PendingExternalVelocity(Vec3d velocity, long serverTick,
                                           String ability, long receivedTick) { }
}
