package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import java.util.Map;
import java.util.UUID;

/** Captured Neptune death height and, for parkour, the current checkpoint of each participant. */
public final class RollbackArenaFloor {
    private final int deathY;
    private final Map<UUID, Respawn> checkpoints;

    public RollbackArenaFloor(int deathY, Map<UUID, Respawn> checkpoints) {
        this.deathY = deathY;
        this.checkpoints = Map.copyOf(checkpoints);
    }

    public static final class Respawn {
        private final double x, y, z;
        private final float yaw, pitch;
        public Respawn(double x, double y, double z, float yaw, float pitch) {
            this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
        private Location location(RollbackPlayer player) {
            var position = new Location(player.getWorld(), x, y, z);
            position.setYaw(yaw);
            position.setPitch(pitch);
            return position;
        }
    }

    void validate(RollbackRound round) {
        if (!checkpoints.isEmpty() && !checkpoints.keySet().equals(round.participants()))
            throw new IllegalArgumentException("Parkour checkpoints differ from the duel roster");
    }

    void afterMovement(RollbackRound round, RollbackPlayer player) {
        var id = player.getUniqueId();
        if (player.getLocation().getY() > deathY || !round.active(id)) return;
        var checkpoint = checkpoints.get(id);
        if (checkpoint == null) {
            round.fellBelowArena(id);
            return;
        }
        if (!player.teleport(checkpoint.location(player)))
            throw new IllegalStateException("Unable to return the private participant to their parkour checkpoint");
        // Neptune's absolute Bukkit teleport supplies zero velocity on every axis.
        player.setVelocity(new com.projectkorra.projectkorra.platform.mc.util.Vector());
    }
}
