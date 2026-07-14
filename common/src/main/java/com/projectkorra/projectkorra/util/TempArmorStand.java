package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;

import java.util.HashSet;
import java.util.Set;

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
        this.stand.setMetadata("temparmorstand", new FixedMetadataValue(ProjectKorra.plugin, 0));
        tempStands.add(this);
    }

    /**
     * Removes all instances of TempArmorStands and the associated ArmorStands
     */
    public static void removeAll() {
        for (final TempArmorStand temp : tempStands) {
            temp.getArmorStand().remove();
        }
        tempStands.clear();
    }

    public static Set<TempArmorStand> getTempStands() {
        return tempStands;
    }

    public ArmorStand getArmorStand() {
        return this.stand;
    }
}
