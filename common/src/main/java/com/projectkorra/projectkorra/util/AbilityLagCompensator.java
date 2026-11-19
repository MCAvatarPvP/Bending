package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Collider;

import java.util.*;

public class AbilityLagCompensator {

    // Two seconds of rewind, including the current frame. No full-history scans per tick.
    static final int MAX_REWIND_TICKS = 40;
    private final Set<Player> players;
    private final Snapshot[] snapshots;
    private final OnUpdate onUpdate;
    private long currentTick;

    public AbilityLagCompensator(OnUpdate onUpdate) {
        this.players = new HashSet<>();
        this.snapshots = new Snapshot[MAX_REWIND_TICKS + 1];
        this.onUpdate = onUpdate;
        this.currentTick = 0;
    }

    public void update() {
        Snapshot currentSnapshot = snapshots[index(currentTick)];
        final Iterator<Player> iterator = players.iterator();
        while (iterator.hasNext()) {
            final Player player = iterator.next();
            if (!player.isOnline() || BendingPlayer.getBendingPlayer(player) == null) {
                iterator.remove();
                continue;
            }

            Snapshot snapshot = getCompensatedSnapshot(player.getPing());

            if (snapshot == null || currentSnapshot == null) {
                continue;
            }

            AABB playerAABB = new AABB(player.getWorld(), player.getCombatBoundingBox());
            boolean intersectsCurr = playerAABB.intersects(currentSnapshot.getCollider());
            boolean intersectsPrev = playerAABB.intersects(snapshot.getCollider());
            if (!intersectsCurr && !intersectsPrev) {
                iterator.remove();
                continue;
            }

            Snapshot correct = intersectsPrev ? snapshot : currentSnapshot;

            onUpdate.update(player, correct);
        }

        currentTick++;
        snapshots[index(currentTick)] = null;
    }

    Snapshot getCompensatedSnapshot(int ping) {
        final int rewind = Math.min(MAX_REWIND_TICKS, Math.max(0, ping / 50));
        return snapshots[index(Math.max(0, currentTick - rewind))];
    }

    private int index(long tick) {
        return (int) (tick % snapshots.length);
    }

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void addSnapshot(Collider collider) {
        snapshots[index(currentTick)] = new Snapshot(collider.getCenter(), collider);
    }

    public void addSnapshot(Location location, double radius) {
        snapshots[index(currentTick)] = new Snapshot(location, new AABB(location, radius));
    }

    @FunctionalInterface
    public interface OnUpdate {
        void update(Player player, Snapshot snapshot);
    }

    public static class Snapshot {

        private final Location location;
        private final Collider collider;

        public Snapshot(Location location, Collider collider) {
            this.location = location;
            this.collider = collider;
        }

        public Location getLocation() {
            return location;
        }

        public Collider getCollider() {
            return collider;
        }
    }
}