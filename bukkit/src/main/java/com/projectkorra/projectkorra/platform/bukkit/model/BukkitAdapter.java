package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.platform.model.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Native Bukkit <-> ProjectKorra wrapper adapter.
 */
public final class BukkitAdapter implements PKAdapter {
    static PKVec3 fromVector(final Vector vector) {
        return new PKVec3(vector.getX(), vector.getY(), vector.getZ());
    }

    static Vector toVector(final PKVec3 vector) {
        return new Vector(vector.x(), vector.y(), vector.z());
    }

    @Override
    public PKPlayer player(final Object nativePlayer) {
        return new BukkitPlayer((Player) nativePlayer);
    }

    @Override
    public PKEntity entity(final Object nativeEntity) {
        if (nativeEntity instanceof Player player) {
            return player(player);
        }
        if (nativeEntity instanceof LivingEntity living) {
            return livingEntity(living);
        }
        return new BukkitEntity((Entity) nativeEntity);
    }

    @Override
    public PKLivingEntity livingEntity(final Object nativeLivingEntity) {
        if (nativeLivingEntity instanceof Player player) {
            return player(player);
        }
        return new BukkitLivingEntity((LivingEntity) nativeLivingEntity);
    }

    @Override
    public PKWorld world(final Object nativeWorld) {
        return new BukkitWorld((World) nativeWorld);
    }

    @Override
    public PKBlock block(final Object nativeBlock) {
        return new BukkitBlock((Block) nativeBlock);
    }

    @Override
    public PKLocation location(final Object nativeLocation) {
        return new BukkitLocation((Location) nativeLocation);
    }

    @Override
    public Object unwrap(final Object maybeWrapped) {
        return maybeWrapped instanceof PKHandle handle ? handle.handle() : maybeWrapped;
    }
}
