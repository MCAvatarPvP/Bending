package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Owns the identity boundary between locally spawned prediction entities and
 * their later Paper entity packets.
 *
 * <p>Ordinary predicted entities are aliased to Paper's numeric entity id.
 * Displays and TempFallingBlocks stay fully client simulated, so their Paper
 * ids are retained only as hidden lifecycle tombstones. TempFallingBlocks use
 * action + spawn ordinal matching; proximity is never sufficient.</p>
 */
public final class ClientEntityReconciliation {
    private final Map<Integer, Entity> authoritativeAliases = new HashMap<>();
    private final Set<Integer> tempFallingAliases = new HashSet<>();
    private final Set<Integer> hiddenTempFallingEntities = new HashSet<>();
    private final Map<Integer, Entity> hiddenPredictedDisplays = new HashMap<>();
    private final Map<Entity, PredictedSpawn> predictedSpawns = new IdentityHashMap<>();
    private final Map<TempFallingBlockKey, PredictedTempFallingBlock> predictedTempFallingBlocks =
            new HashMap<>();
    private final Map<TempFallingBlockKey, ServerTempFallingPrepare> serverTempFallingPrepares =
            new LinkedHashMap<>();
    private final Map<Integer, TempFallingBlockKey> preparedFallingEntityIds = new HashMap<>();
    private final Map<Integer, Long> observedFallingBlockSpawns = new HashMap<>();
    private final Function<String, BlockState> blockStateDecoder;

    public ClientEntityReconciliation(final Function<String, BlockState> blockStateDecoder) {
        this.blockStateDecoder = blockStateDecoder;
    }

    public void trackSpawn(final long actionSequence, final Entity entity) {
        if (actionSequence <= 0L || entity == null) return;
        predictedSpawns.put(entity, new PredictedSpawn(actionSequence, entity.getEntityPos()));
    }

    public boolean isPredictedOwned(final Entity entity) {
        return entity != null && predictedSpawns.containsKey(entity);
    }

    public void trackTempFallingBlock(final long actionSequence, final int spawnOrdinal,
                                      final Entity entity, final String ability) {
        if (actionSequence <= 0L || spawnOrdinal <= 0 || entity == null) return;
        predictedTempFallingBlocks.put(new TempFallingBlockKey(actionSequence, spawnOrdinal),
                new PredictedTempFallingBlock(entity, ability == null ? "" : ability));
    }

    public void notePrepare(final Entity localPlayer,
                            final PredictionPayloads.TempFallingBlockPrepare prepare,
                            final long localActionSequence, final long tick) {
        if (localPlayer == null || prepare == null || localActionSequence <= 0L
                || prepare.actionSequence() <= 0L
                || !localPlayer.getUuid().equals(prepare.abilityOwner())) return;
        serverTempFallingPrepares.put(
                new TempFallingBlockKey(localActionSequence, prepare.spawnOrdinal()),
                new ServerTempFallingPrepare(prepare, tick));
    }

    public void noteReceipt(final Entity localPlayer,
                            final PredictionPayloads.TempFallingBlockReceipt receipt,
                            final long localActionSequence, final ClientWorld world) {
        if (localPlayer == null || receipt == null || receipt.actionSequence() <= 0L
                || !localPlayer.getUuid().equals(receipt.abilityOwner())) return;

        if (localActionSequence <= 0L) {
            hiddenTempFallingEntities.add(receipt.serverEntityId());
            return;
        }
        final TempFallingBlockKey key = new TempFallingBlockKey(
                localActionSequence, receipt.spawnOrdinal());
        final PredictedTempFallingBlock pending = predictedTempFallingBlocks.get(key);
        final TempFallingBlockKey preparedKey = preparedFallingEntityIds.get(receipt.serverEntityId());
        if (preparedKey != null && !preparedKey.equals(key)) return;
        predictedTempFallingBlocks.remove(key);
        serverTempFallingPrepares.remove(key);
        preparedFallingEntityIds.remove(receipt.serverEntityId());
        final Entity predicted = pending != null
                && pending.ability.equalsIgnoreCase(receipt.ability()) ? pending.entity : null;

        final boolean vanillaSpawnSeen = observedFallingBlockSpawns.remove(receipt.serverEntityId()) != null;
        final Entity authority = world == null ? null : world.getEntityById(receipt.serverEntityId());
        if ((vanillaSpawnSeen || authority != null && authority != predicted)
                && authority != null && authority != predicted && !authority.isRemoved()) {
            // Packet order must not choose the rendered entity. Even when the
            // delayed vanilla spawn wins the race, retire that duplicate and
            // keep the continuously simulated local falling block.
            authority.discard();
        }

        // Movement/velocity packets intentionally never steer a client-owned
        // TempFallingBlock. Its alias exists only to consume Paper lifecycle.
        if (predicted != null) {
            hiddenTempFallingEntities.remove(receipt.serverEntityId());
            authoritativeAliases.put(receipt.serverEntityId(), predicted);
            tempFallingAliases.add(receipt.serverEntityId());
        } else {
            // Paper confirms an owner-only visual, but the local ordinal may
            // already have coalesced or retired. Hide the unmatched entity.
            hiddenTempFallingEntities.add(receipt.serverEntityId());
        }
    }

    public boolean reconcileSpawn(final EntitySpawnS2CPacket packet,
                                  final ClientWorld world, final long tick) {
        if (packet == null || world == null) return false;
        if (authoritativeAliases.containsKey(packet.getEntityId())
                || hiddenTempFallingEntities.contains(packet.getEntityId())) return true;

        // A falling block is consumed only by an owner-only prepare with the
        // exact world, block state and encoded spawn position. This prevents a
        // nearby remote player's vanilla falling block from being stolen.
        if (packet.getEntityType() == EntityType.FALLING_BLOCK) {
            final Vec3d spawn = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
            final BlockState spawnedState = Block.getStateFromRawId(packet.getEntityData());
            for (Map.Entry<TempFallingBlockKey, ServerTempFallingPrepare> entry
                    : serverTempFallingPrepares.entrySet()) {
                if (preparedFallingEntityIds.containsValue(entry.getKey())) continue;
                final PredictionPayloads.TempFallingBlockPrepare prepare = entry.getValue().prepare;
                if (!blockStateDecoder.apply(prepare.material()).equals(spawnedState)) continue;
                if (!matchesWorld(world.getRegistryKey().getValue().toString(), prepare.world())) continue;
                final Vec3d expected = new Vec3d(prepare.x(), prepare.y(), prepare.z());
                if (!close(spawn, expected, 1.0E-7)) continue;
                preparedFallingEntityIds.put(packet.getEntityId(), entry.getKey());
                observedFallingBlockSpawns.put(packet.getEntityId(), tick);
                hiddenTempFallingEntities.add(packet.getEntityId());
                return true;
            }
            observedFallingBlockSpawns.put(packet.getEntityId(), tick);
            return false;
        }

        final Vec3d serverPosition = new Vec3d(packet.getX(), packet.getY(), packet.getZ());
        Entity best = null;
        double bestDistance = 32.0 * 32.0;
        for (Map.Entry<Entity, PredictedSpawn> entry : predictedSpawns.entrySet()) {
            final Entity candidate = entry.getKey();
            if (candidate == null || candidate.getType() != packet.getEntityType()
                    || authoritativeAliases.containsValue(candidate)
                    || hiddenPredictedDisplays.containsValue(candidate)) continue;
            // Match creation position, not the position reached while waiting
            // for Paper. Removed entities remain tombstones for delayed spawns.
            final double distance = entry.getValue().origin.squaredDistanceTo(serverPosition);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best == null) return false;
        if (best instanceof DisplayEntity) {
            // Displays stay common-client simulated. Never expose their Paper
            // ids through a world lookup or let tracker packets steer them.
            hiddenPredictedDisplays.put(packet.getEntityId(), best);
            return true;
        }
        // Keep the local UUID index intact; packet mixins translate Paper's
        // numeric id through this alias instead.
        authoritativeAliases.put(packet.getEntityId(), best);
        return true;
    }

    public boolean suppressAuthoritativeData(final int serverEntityId) {
        return authoritativeAliases.containsKey(serverEntityId)
                || hiddenTempFallingEntities.contains(serverEntityId)
                || hiddenPredictedDisplays.containsKey(serverEntityId);
    }

    public Entity aliasedEntity(final int serverEntityId) {
        // TempFallingBlocks consume lifecycle but reject movement lookup.
        return tempFallingAliases.contains(serverEntityId)
                ? null : authoritativeAliases.get(serverEntityId);
    }

    public boolean hasAlias(final int serverEntityId) {
        return suppressAuthoritativeData(serverEntityId);
    }

    public boolean removeHidden(final int serverEntityId) {
        final boolean display = hiddenPredictedDisplays.remove(serverEntityId) != null;
        return hiddenTempFallingEntities.remove(serverEntityId) || display;
    }

    public boolean removeAlias(final int serverEntityId) {
        final boolean hidden = removeHidden(serverEntityId);
        final Entity entity = authoritativeAliases.remove(serverEntityId);
        if (entity == null) return hidden;
        final boolean clientOwnedFallingBlock = tempFallingAliases.remove(serverEntityId);
        if (!clientOwnedFallingBlock && !entity.isRemoved()) entity.discard();
        return true;
    }

    public void expire(final long tick, final int actionRetentionTicks) {
        observedFallingBlockSpawns.entrySet().removeIf(
                entry -> tick - entry.getValue() > actionRetentionTicks);
        serverTempFallingPrepares.entrySet().removeIf(
                entry -> tick - entry.getValue().receivedTick > 8L);
        preparedFallingEntityIds.entrySet().removeIf(
                entry -> !serverTempFallingPrepares.containsKey(entry.getValue()));
    }

    /** Removes bookkeeping for an action whose normal retention window ended. */
    public void retireAction(final long actionSequence) {
        predictedSpawns.entrySet().removeIf(entry -> entry.getValue().actionSequence == actionSequence);
        predictedTempFallingBlocks.keySet().removeIf(key -> key.actionSequence == actionSequence);
        serverTempFallingPrepares.keySet().removeIf(key -> key.actionSequence == actionSequence);
        preparedFallingEntityIds.entrySet().removeIf(
                entry -> entry.getValue().actionSequence == actionSequence);
    }

    /** Removes all partial entity state created by a locally failed input. */
    public void rollbackAction(final long actionSequence, final Collection<Entity> spawned) {
        final Set<Entity> actionEntities = spawned == null
                ? Set.of() : java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (spawned != null) actionEntities.addAll(spawned);
        tempFallingAliases.removeIf(id -> actionEntities.contains(authoritativeAliases.get(id)));
        authoritativeAliases.entrySet().removeIf(entry -> actionEntities.contains(entry.getValue()));
        predictedSpawns.entrySet().removeIf(entry -> entry.getValue().actionSequence == actionSequence);
        predictedTempFallingBlocks.keySet().removeIf(key -> key.actionSequence == actionSequence);
        serverTempFallingPrepares.keySet().removeIf(key -> key.actionSequence == actionSequence);
        preparedFallingEntityIds.entrySet().removeIf(
                entry -> entry.getValue().actionSequence == actionSequence);
    }

    public void clear() {
        authoritativeAliases.clear();
        tempFallingAliases.clear();
        hiddenTempFallingEntities.clear();
        hiddenPredictedDisplays.clear();
        predictedSpawns.clear();
        predictedTempFallingBlocks.clear();
        serverTempFallingPrepares.clear();
        preparedFallingEntityIds.clear();
        observedFallingBlockSpawns.clear();
    }

    private static boolean matchesWorld(final String clientWorld, final String serverWorld) {
        if (serverWorld == null || serverWorld.isBlank()) return false;
        if (clientWorld.equals(serverWorld)) return true;
        return serverWorld.indexOf(':') < 0
                && ("minecraft:overworld".equals(clientWorld) || "overworld".equals(clientWorld));
    }

    private static boolean close(final Vec3d first, final Vec3d second, final double tolerance) {
        return first.squaredDistanceTo(second) <= tolerance * tolerance;
    }

    private record PredictedSpawn(long actionSequence, Vec3d origin) { }
    private record TempFallingBlockKey(long actionSequence, int spawnOrdinal) { }
    private record PredictedTempFallingBlock(Entity entity, String ability) { }
    private record ServerTempFallingPrepare(PredictionPayloads.TempFallingBlockPrepare prepare,
                                            long receivedTick) { }
}
