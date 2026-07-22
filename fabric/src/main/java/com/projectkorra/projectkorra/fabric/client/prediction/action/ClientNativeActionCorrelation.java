package com.projectkorra.projectkorra.fabric.client.prediction.action;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.InputKind;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.NativeAction;
import com.projectkorra.projectkorra.prediction.action.CapturedInputPose;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Correlates Paper's native-input sequence with the independently numbered local input stream.
 * Raw Paper ordinals are never accepted as local identities.
 */
public final class ClientNativeActionCorrelation {
    private final Map<Long, Long> aliases = new LinkedHashMap<>();

    /**
     * Finds and records the semantic local counterpart of a Paper native action.
     *
     * @return the local sequence, or {@code 0} when no identical unmatched input exists
     */
    public long correlate(final NativeAction receipt, final Collection<Candidate> candidates) {
        if (receipt == null || candidates == null) return 0L;

        if (receipt.clientActionSequence() > 0L) {
            final long exactSequence = receipt.clientActionSequence();
            final Candidate exact = candidates.stream()
                    .filter(candidate -> candidate.sequence == exactSequence)
                    .filter(candidate -> matches(candidate, receipt))
                    .findFirst()
                    .orElse(null);
            if (exact == null) return 0L;

            // Repair any earlier fallback association before installing the
            // authenticated tag echoed by Paper. Normally this is the first
            // receipt; the cleanup also makes reconnect/mixed-version races
            // fail closed instead of leaving a non-bijective alias map.
            aliases.entrySet().removeIf(alias ->
                    alias.getKey() != receipt.actionSequence()
                            && alias.getValue() == exactSequence);
            aliases.put(receipt.actionSequence(), exactSequence);
            return exactSequence;
        }

        final Long existing = aliases.get(receipt.actionSequence());
        if (existing != null) {
            return candidates.stream()
                    .filter(candidate -> candidate.sequence == existing)
                    .filter(candidate -> matches(candidate, receipt))
                    .mapToLong(Candidate::sequence)
                    .findFirst()
                    .orElse(0L);
        }

        final Set<Long> paired = new HashSet<>(aliases.values());
        long localFloor = 0L;
        for (Map.Entry<Long, Long> alias : aliases.entrySet()) {
            if (alias.getKey() < receipt.actionSequence()) {
                localFloor = Math.max(localFloor, alias.getValue());
            }
        }

        Candidate closest = null;
        double closestScore = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            if (candidate.sequence <= localFloor || paired.contains(candidate.sequence)
                    || !matches(candidate, receipt)) continue;
            final double score = posePairingScore(candidate, receipt);
            if (score < closestScore) {
                closest = candidate;
                closestScore = score;
            }
        }
        if (closest == null) return 0L;
        aliases.put(receipt.actionSequence(), closest.sequence);
        return closest.sequence;
    }

    public long localSequence(final long paperSequence) {
        return mappedActionSequence(aliases, paperSequence);
    }

    public long acknowledgedLocalSequence(final long paperSequence) {
        return mappedAcknowledgedSequence(aliases, paperSequence);
    }

    public long paperSequence(final long localSequence) {
        if (localSequence <= 0L) return 0L;
        for (Map.Entry<Long, Long> alias : aliases.entrySet()) {
            if (alias.getValue() == localSequence) return alias.getKey();
        }
        return 0L;
    }

    public void clear() {
        aliases.clear();
    }

    public static long mappedActionSequence(final Map<Long, Long> aliases,
                                            final long paperSequence) {
        return aliases != null && paperSequence > 0L
                ? aliases.getOrDefault(paperSequence, 0L) : 0L;
    }

    public static long mappedAcknowledgedSequence(final Map<Long, Long> aliases,
                                                  final long paperSequence) {
        if (aliases == null || paperSequence <= 0L) return 0L;
        long acknowledged = 0L;
        for (Map.Entry<Long, Long> alias : aliases.entrySet()) {
            if (alias.getKey() <= paperSequence) {
                acknowledged = Math.max(acknowledged, alias.getValue());
            }
        }
        return acknowledged;
    }

    private static boolean matches(final Candidate local, final NativeAction paper) {
        return local != null && paper != null
                && local.kind == paper.kind()
                && local.selectedSlot == paper.selectedSlot()
                && local.ability.equalsIgnoreCase(paper.ability());
    }

    private static double posePairingScore(final Candidate local, final NativeAction paper) {
        final double dx = local.originX - paper.originX();
        final double dy = local.originY - paper.originY();
        final double dz = local.originZ - paper.originZ();
        final double yaw = CapturedInputPose.signedAngleDelta(local.yaw, paper.yaw());
        final double pitch = local.pitch - paper.pitch();
        return dx * dx + dy * dy + dz * dz + yaw * yaw + pitch * pitch;
    }

    public record Candidate(long sequence, InputKind kind, int selectedSlot, String ability,
                            double originX, double originY, double originZ,
                            float yaw, float pitch) {
        public Candidate {
            ability = ability == null ? "" : ability;
        }
    }
}
