package com.projectkorra.projectkorra.fabric.client.prediction.state;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerAbilitiesS2CPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

/** Reconciles prediction-owned player abilities and experience-bar writes. */
public final class ClientPlayerStateAuthority {
    private final List<AbilityMutation> abilityMutations = new ArrayList<>();
    private final List<AbilityReceipt> abilityReceipts = new ArrayList<>();
    private final List<ExperienceMutation> experienceMutations = new ArrayList<>();
    private final Consumer<String> debug;

    public ClientPlayerStateAuthority(final Consumer<String> debug) {
        this.debug = debug == null ? ignored -> { } : debug;
    }

    public void predictAbilityState(final long tick, final long actionSequence,
                                    final int mutationOrdinal) {
        if (actionSequence <= 0L || mutationOrdinal <= 0) return;
        abilityMutations.add(new AbilityMutation(tick, actionSequence, mutationOrdinal));
    }

    public void recordAbilityOwner(final Entity localPlayer,
                                   final PredictionPayloads.AbilityStateOwner owner,
                                   final long tick,
                                   final LongUnaryOperator correlateAction) {
        if (localPlayer == null || owner == null || owner.actionSequence() <= 0L
                || owner.mutationOrdinal() <= 0
                || !localPlayer.getUuid().equals(owner.target())) return;
        final boolean locallyOwned = localPlayer.getUuid().equals(owner.abilityOwner());
        final long correlated = locallyOwned && correlateAction != null
                ? correlateAction.applyAsLong(owner.actionSequence()) : owner.actionSequence();
        if (locallyOwned && correlated <= 0L) {
            debug.accept("runtime ignored uncorrelated self ability-state paperAction="
                    + owner.actionSequence() + " ordinal=" + owner.mutationOrdinal()
                    + " ability=" + owner.ability());
            return;
        }
        final AbilityReceipt receipt = new AbilityReceipt(owner.serverTick(), correlated,
                owner.mutationOrdinal(), owner.abilityOwner(), owner.target(), owner.ability(),
                owner.flying(), owner.allowFlight(), owner.flySpeed(), tick);
        int replacement = -1;
        for (int index = 0; index < abilityReceipts.size(); index++) {
            final AbilityReceipt candidate = abilityReceipts.get(index);
            if (candidate.serverTick == owner.serverTick()
                    && candidate.target.equals(owner.target())) {
                replacement = index;
                break;
            }
        }
        if (replacement >= 0) abilityReceipts.set(replacement, receipt);
        else abilityReceipts.add(receipt);
    }

    public void predictExperience(final long tick, final float barProgress,
                                  final int experience, final int level) {
        experienceMutations.add(new ExperienceMutation(tick, barProgress, experience, level));
    }

    public boolean suppressAbilityPacket(final PlayerAbilitiesS2CPacket packet,
                                         final long tick, final boolean hasFlightLease) {
        if (packet == null) return false;
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        int receiptIndex = -1;
        for (int index = 0; index < abilityReceipts.size(); index++) {
            final AbilityReceipt candidate = abilityReceipts.get(index);
            if (player.getUuid().equals(candidate.target)
                    && matches(candidate, packet)) {
                receiptIndex = index;
                break;
            }
        }
        if (receiptIndex >= 0) {
            final AbilityReceipt receipt = abilityReceipts.remove(receiptIndex);
            if (!player.getUuid().equals(receipt.abilityOwner)) {
                debug.accept("runtime allowed externally-owned abilities packet owner="
                        + receipt.abilityOwner + " ability=" + receipt.ability);
                return false;
            }
            abilityMutations.removeIf(mutation ->
                    mutation.actionSequence == receipt.actionSequence
                            && mutation.mutationOrdinal == receipt.mutationOrdinal);
            debug.accept("runtime suppressed self-owned abilities packet action="
                    + receipt.actionSequence + " ordinal=" + receipt.mutationOrdinal
                    + " ability=" + receipt.ability);
            return true;
        }
        if ((hasFlightLease || hasRecentAbilityMutation(tick, 160L))
                && (player.getAbilities().flying != packet.isFlying()
                || player.getAbilities().allowFlying != packet.allowFlying())) {
            final PlayerAbilities abilities = player.getAbilities();
            abilities.invulnerable = packet.isInvulnerable();
            abilities.creativeMode = packet.isCreativeMode();
            abilities.setFlySpeed(packet.getFlySpeed());
            abilities.setWalkSpeed(packet.getWalkSpeed());
            debug.accept("runtime preserved locally-leased flight across unowned server correction flying="
                    + abilities.flying + " allowFlying=" + abilities.allowFlying);
            return true;
        }
        return false;
    }

    public boolean suppressExperiencePacket(final ExperienceBarUpdateS2CPacket packet,
                                            final long tick) {
        if (packet == null) return false;
        final ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return false;
        if (packet.getExperience() != player.totalExperience
                || packet.getExperienceLevel() != player.experienceLevel) {
            experienceMutations.clear();
            return false;
        }
        for (int index = experienceMutations.size() - 1; index >= 0; index--) {
            if (tick - experienceMutations.get(index).tick <= 4L) return true;
        }
        return false;
    }

    public void expire(final long tick, final long actionRetentionTicks) {
        abilityMutations.removeIf(mutation -> tick - mutation.tick > actionRetentionTicks);
        abilityReceipts.removeIf(receipt -> tick - receipt.receivedTick > 4L);
        experienceMutations.removeIf(mutation -> tick - mutation.tick > actionRetentionTicks);
    }

    public void clear() {
        abilityMutations.clear();
        abilityReceipts.clear();
        experienceMutations.clear();
    }

    private boolean hasRecentAbilityMutation(final long tick, final long retentionTicks) {
        for (int index = abilityMutations.size() - 1; index >= 0; index--) {
            if (tick - abilityMutations.get(index).tick <= retentionTicks) return true;
        }
        return false;
    }

    private static boolean matches(final AbilityReceipt receipt,
                                   final PlayerAbilitiesS2CPacket packet) {
        final float commonFlySpeed = packet.getFlySpeed() * 2.0F;
        return receipt.flying == packet.isFlying()
                && receipt.allowFlight == packet.allowFlying()
                && Float.compare(receipt.flySpeed, commonFlySpeed) == 0;
    }

    private record AbilityMutation(long tick, long actionSequence, int mutationOrdinal) { }
    private record AbilityReceipt(long serverTick, long actionSequence, int mutationOrdinal,
                                  UUID abilityOwner, UUID target, String ability,
                                  boolean flying, boolean allowFlight, float flySpeed,
                                  long receivedTick) { }
    private record ExperienceMutation(long tick, float barProgress,
                                      int experience, int level) { }
}
