package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;

import java.util.HashSet;
import java.util.Set;
import java.util.List;

/**
 * Object to represent an ArmorStand that is not used for normal functionality
 *
 * @author Simplicitee
 *
 */
public class TempArmorStand {

    private static Set<TempArmorStand> tempStands = new HashSet<>();

    private ArmorStand stand;

    public TempArmorStand(final Location loc) {
        this.stand = loc.getWorld().spawn(loc, ArmorStand.class);
        restoreMetadata();
        getTempStands().add(this);
    }

    /**
     * Removes all instances of TempArmorStands and the associated ArmorStands
     */
    public static void removeAll() {
        for (final TempArmorStand temp : List.copyOf(getTempStands())) temp.remove();
    }

    public static void remove(final ArmorStand stand) {
        for (final TempArmorStand temp : List.copyOf(getTempStands())) {
            if (temp.stand.equals(stand)) {
                temp.remove();
                return;
            }
        }
        if (stand != null) stand.remove();
    }

    public void remove() {
        getTempStands().remove(this);
        this.stand.removeMetadata("temparmorstand", ProjectKorra.plugin);
        this.stand.remove();
    }

    /** Also releases stands removed externally, including chunk unloads. */
    public static void manage() {
        for (final TempArmorStand temp : List.copyOf(getTempStands())) {
            if (!temp.stand.isValid() || temp.stand.isDead()) temp.remove();
        }
    }

    public static Set<TempArmorStand> getTempStands() {
        return com.projectkorra.projectkorra.prediction.rollback.RollbackAbilityGlobals.stands(tempStands);
    }

    public void restoreMetadata() {
        com.projectkorra.projectkorra.prediction.rollback.RollbackAbilityGlobals.ensureActorMetadata(this.stand, "temparmorstand", ProjectKorra.plugin, 0);
    }

    public ArmorStand getArmorStand() {
        return this.stand;
    }
}
