package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.Collection;

public class ParticleUtil {

    public static void spawn(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ) {
        spawn(location.getWorld().getPlayers(), particle, location, count, offsetX, offsetY, offsetZ, 1.0);
    }

    public static void spawn(Collection<Player> receivers, Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ) {
        spawn(receivers, particle, location, count, offsetX, offsetY, offsetZ, 1.0);
    }

    public static void spawn(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        spawn(location.getWorld().getPlayers(), particle, location, count, offsetX, offsetY, offsetZ, extra, null);
    }

    public static void spawn(Collection<Player> receivers, Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        spawn(receivers, particle, location, count, offsetX, offsetY, offsetZ, extra, null);
    }

    public static <T> void spawn(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        spawn(location.getWorld().getPlayers(), particle, location, count, offsetX, offsetY, offsetZ, extra, data);
    }

    public static <T> void spawn(Collection<Player> receivers, Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
        for (Player player : receivers) {
            if (!player.getWorld().equals(location.getWorld())) continue;
            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
            if (bPlayer != null && !isInViewDistance(player, location, bPlayer.getViewDistanceSqrt())) continue;

            Particle playerParticle = particle;
            T playerData = data;
            if (particle == Particle.EFFECT) {
                if (bPlayer != null && bPlayer.isBedrock()) {
                    playerData = null;
                    playerParticle = Particle.CLOUD;
                }
            }

            player.spawnParticle(playerParticle, location, count, offsetX, offsetY, offsetZ, extra, playerData, true);
        }
    }

    private static boolean isInViewDistance(Player player, Location location, double viewDistanceSqrt) {
        return player.getLocation().distanceSquared(location) < viewDistanceSqrt;
    }
}
