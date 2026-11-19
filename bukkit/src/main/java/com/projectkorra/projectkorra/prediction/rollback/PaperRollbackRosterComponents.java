package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Stages intrinsic native roster components before writes. Live handoff also moves each
 * tracked body and establishes the client position acknowledgement while the roster is gated.
 */
public final class PaperRollbackRosterComponents {
    private PaperRollbackRosterComponents() { }

    public static Prepared prepare(Map<UUID, ServerPlayer> targets, RollbackRosterData outgoing, long initialNanos) {
        return prepare(targets, outgoing, initialNanos, Map.of());
    }

    static Prepared prepare(Map<UUID, ServerPlayer> targets, RollbackRosterData outgoing, long initialNanos,
            Map<UUID, net.minecraft.world.entity.Entity> history) {
        boundary();
        var roster = Collections.unmodifiableMap(new TreeMap<>(targets));
        if (!roster.keySet().equals(outgoing.players().keySet()))
            throw new IllegalArgumentException("Native component roster changed");
        var guards = new ArrayList<Runnable>();
        var moves = new ArrayList<Runnable>();
        var writes = new ArrayList<Runnable>();
        var world = roster.values().iterator().next().level();
        for (var entry : roster.entrySet()) {
            var player = Objects.requireNonNull(entry.getValue());
            var seed = outgoing.players().get(entry.getKey());
            var identity = seed.identity();
            if (!entry.getKey().equals(player.getUUID()) || identity.entityId() != player.getId() || player.level() != world)
                throw new IllegalArgumentException("Native component target identity/world changed");
            var connection = player.connection;
            var wrapper = player.getBukkitEntity();
            guards.add(() -> {
                if (!identity.id().equals(player.getUUID()) || identity.entityId() != player.getId()
                        || player.level() != world || player.connection != connection || player.getBukkitEntity() != wrapper)
                    throw new IllegalStateException("Native component target changed before commit");
            });
            writes.add(PaperRollbackPlayerSeed.prepareValues(player, seed.values()));
            var pose = pose(seed.values());
            moves.add(() -> {
                if (player.isRemoved() || connection == null) throw new IllegalStateException("Cannot resume a removed or disconnected player");
                // Move before assigning the captured position fields so Paper updates its entity section index.
                // The server handshake also keeps ordinary movement from accepting the abandoned predicted pose.
                connection.internalTeleport(pose, Set.of());
                if (!player.position().equals(pose.position())) throw new IllegalStateException("Paper declined rollback relocation");
                connection.resetPosition();
            });
            writes.add(PaperRollbackPlayerVitals.prepare(player, seed.vitals()));
            writes.add(PaperRollbackPlayerItems.prepare(player, seed.items()));
            writes.add(PaperRollbackPlayerContextData.prepare(player, seed.context(), initialNanos));
            writes.add(new PaperRollbackCombatSeed(seed.combat()).prepare(player, roster, history));
        }
        return new Prepared(guards, moves, writes);
    }

    private static PositionMoveRotation pose(RollbackPlayerValues values) {
        var fields = values.fields();
        var position = (Vec3) PaperRollbackNativeValues.decode(Vec3.class, fields.get("entity.position"));
        var block = (BlockPos) PaperRollbackNativeValues.decode(BlockPos.class, fields.get("entity.blockPosition"));
        var chunk = (ChunkPos) PaperRollbackNativeValues.decode(ChunkPos.class, fields.get("entity.chunkPosition"));
        if (!BlockPos.containing(position).equals(block) || !new ChunkPos(block).equals(chunk))
            throw new IllegalArgumentException("Inconsistent player position and section in outgoing roster");
        var velocity = (Vec3) PaperRollbackNativeValues.decode(Vec3.class, fields.get("entity.deltaMovement"));
        float yaw = (float) PaperRollbackNativeValues.decode(float.class, fields.get("entity.yRot"));
        float pitch = (float) PaperRollbackNativeValues.decode(float.class, fields.get("entity.xRot"));
        return new PositionMoveRotation(position, velocity, yaw, pitch);
    }

    public static final class Prepared {
        private final Thread owner = Thread.currentThread();
        private final List<Runnable> guards, moves, writes;
        private int moved, completed;
        private boolean committing;
        private Prepared(List<Runnable> guards, List<Runnable> moves, List<Runnable> writes) {
            this.guards = List.copyOf(guards); this.moves = List.copyOf(moves); this.writes = List.copyOf(writes);
        }
        public void validate() {
            boundary();
            if (Thread.currentThread() != owner) throw new IllegalStateException("Native component commit crossed threads");
            guards.forEach(Runnable::run);
        }
        /** Retry after failure only while the loader still holds the entire native roster. */
        public void commit() { commit(false); }

        /** Called while both native tick paths are still gated, after the stop was sent to the clients. */
        void commitLive() { commit(true); }

        private void commit(boolean live) {
            validate();
            if (live && completed != 0 && moved != moves.size())
                throw new IllegalStateException("Cannot relocate after assigning intrinsic position fields");
            if (committing) throw new IllegalStateException("Recursive native component commit");
            committing = true;
            try {
                while (live && moved < moves.size()) {
                    moves.get(moved).run();
                    moved++;
                }
                while (completed < writes.size()) {
                    writes.get(completed).run();
                    completed++;
                }
            } finally { committing = false; }
        }
    }
    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Native component staging/commit requires the live tick boundary");
    }
}
