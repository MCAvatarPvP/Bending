package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Collider;

import java.util.*;

public class AbilityLagCompensator {

    private final Set<Player> players;
    private final Map<Integer, Snapshot> snapshots;
    private final OnUpdate onUpdate;
    private int currentTick;

    public AbilityLagCompensator(OnUpdate onUpdate) {
        this.players = new HashSet<>();
        this.snapshots = new HashMap<>();
        this.onUpdate = onUpdate;
        this.currentTick = 0;
    }

    public void update() {
        Snapshot currentSnapshot = snapshots.get(currentTick);
        final Iterator<Player> iterator = players.iterator();
        while (iterator.hasNext()) {
            final Player player = iterator.next();
            final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);

            Snapshot snapshot = getCompensatedSnapshot(bPlayer);

            if (snapshot == null) {
                continue;
            }

            AABB playerAABB = new AABB(player.getWorld(), player.getBoundingBox());
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
    }

    private Snapshot getCompensatedSnapshot(BendingPlayer bPlayer) {
        int snapshotIndex = Math.max(0, currentTick - bPlayer.getPlayer().getPing() / 50);

        return snapshots.get(snapshotIndex);
    }

    public void addPlayer(Player player) {
        players.add(player);
    }

    public void addSnapshot(Collider collider) {
        snapshots.put(currentTick, new Snapshot(collider.getCenter(), collider));
    }

    public void addSnapshot(Location location, double radius) {
        snapshots.put(currentTick, new Snapshot(location, new AABB(location, radius)));
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