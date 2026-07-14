package com.projectkorra.projectkorra.util.colliders;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ParticleUtil;

import java.util.Collection;
import java.util.function.Predicate;

public interface Collider {

    Location getCenter();

    Collider expand(double size);

    boolean contains(Location point);

    boolean intersects(Collider collider);

    Location getNearestPoint(Location point);

    default Collection<Entity> getEntities() {
        return getEntities(null);
    }

    Collection<Entity> getEntities(Predicate<Entity> filter);

    Collection<Block> getBlocks(Predicate<Block> filter);

    void display(Collection<Player> players);

    Particle getDisplayParticle();

    default void spawnParticle(Collection<Player> players, Location location) {
        if (players == null || players.isEmpty()) {
            ParticleUtil.spawn(getDisplayParticle(), location, 0, 0, 99999999, 0, 99999999);
            return;
        }

        ParticleUtil.spawn(players, getDisplayParticle(), location, 0, 0, 99999999, 0, 99999999);
    }

    AABB toAABB();
}