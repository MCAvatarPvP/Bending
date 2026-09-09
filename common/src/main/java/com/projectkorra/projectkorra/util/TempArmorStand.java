package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;

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
        this.stand.setMetadata("temparmorstand", new FixedMetadataValue(ProjectKorra.plugin, 0));
        tempStands.add(this);
    }

    /**
     * Removes all instances of TempArmorStands and the associated ArmorStands
     */
    public static void removeAll() {
        for (final TempArmorStand temp : List.copyOf(tempStands)) temp.remove();
    }

    public static void remove(final ArmorStand stand) {
        for (final TempArmorStand temp : List.copyOf(tempStands)) {
            if (temp.stand.equals(stand)) {
                temp.remove();
                return;
            }
        }
        if (stand != null) stand.remove();
    }

    public void remove() {
        tempStands.remove(this);
        this.stand.removeMetadata("temparmorstand", ProjectKorra.plugin);
        this.stand.remove();
    }

    /** Also releases stands removed externally, including chunk unloads. */
    public static void manage() {
        for (final TempArmorStand temp : List.copyOf(tempStands)) {
            if (!temp.stand.isValid() || temp.stand.isDead()) temp.remove();
        }
    }

    public static Set<TempArmorStand> getTempStands() {
        return tempStands;
    }

    public ArmorStand getArmorStand() {
        return this.stand;
    }
}
