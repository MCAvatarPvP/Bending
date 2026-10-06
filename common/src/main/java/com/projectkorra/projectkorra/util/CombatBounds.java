package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Predicate;

/** Adds expanded bending targets that native entity queries cannot see. */
public final class CombatBounds {
    private CombatBounds() { }

    public static Collection<Entity> includeGliders(World world, BoundingBox box,
            Predicate<Entity> filter, Collection<Entity> nativeResults) {
        var result = new LinkedHashMap<UUID, Entity>();
        for (Entity entity : nativeResults) result.put(entity.getUniqueId(), entity);
        for (AirGlider glider : CoreAbility.getAbilities(AirGlider.class)) {
            Player rider = glider.getPlayer();
            if (glider.isRemoved() || glider.getState() != AirGlider.State.GLIDING
                    || rider == null || !rider.isOnline() || rider.isDead()
                    || !world.equals(rider.getWorld()) || result.containsKey(rider.getUniqueId())) continue;
            if (glider.getCombatBoundingBox().overlaps(box) && (filter == null || filter.test(rider))) {
                result.put(rider.getUniqueId(), rider);
            }
        }
        return result.values();
    }
}
