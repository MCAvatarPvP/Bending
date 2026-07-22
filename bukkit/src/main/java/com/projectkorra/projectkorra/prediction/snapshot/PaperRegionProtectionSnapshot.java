package com.projectkorra.projectkorra.prediction.snapshot;

import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class PaperRegionProtectionSnapshot {
    private static final int SPATIAL_RADIUS = 72;

    private PaperRegionProtectionSnapshot() {
    }

    public static RegionProtectionAuthority.Snapshot build(final Player player,
                                                      final Collection<String> abilityNames) {
        final RegionProtectionAuthority.Snapshot point = currentPoint(player, abilityNames);
        final RegionProtectionAuthority.Snapshot spatial = spatial(player, point.abilities());
        final List<RegionProtectionAuthority.Box> boxes = new ArrayList<>(point.boxes());
        boxes.addAll(spatial.boxes());
        return new RegionProtectionAuthority.Snapshot(point.world(), point.abilities(), boxes);
    }

    public static RegionProtectionAuthority.Snapshot currentPoint(final Player player,
                                                             final Collection<String> abilityNames) {
        if (player == null) return RegionProtectionAuthority.Snapshot.empty();
        final List<String> abilities = RegionProtectionAuthority.normalizedAbilities(abilityNames);
        final String world = player.getWorld().getKey().toString();
        final com.projectkorra.projectkorra.platform.mc.entity.Player commonPlayer = BukkitMC.player(player);
        return RegionProtectionAuthority.currentPoint(commonPlayer, world, abilities);
    }

    public static RegionProtectionAuthority.Snapshot spatial(final Player player,
                                                        final Collection<String> abilityNames) {
        if (player == null) return RegionProtectionAuthority.Snapshot.empty();
        final List<String> abilities = RegionProtectionAuthority.normalizedAbilities(abilityNames);
        final String world = player.getWorld().getKey().toString();
        final List<RegionProtectionAuthority.Box> boxes = BukkitRegionProtectionBootstrap.predictionBoxes(
                BukkitMC.player(player), abilities, SPATIAL_RADIUS);
        return new RegionProtectionAuthority.Snapshot(world, abilities, boxes);
    }

    public static List<String> relevantAbilities(final Player player, final Collection<String> bound) {
        final List<String> result = new ArrayList<>();
        if (bound != null) result.addAll(bound);
        if (player != null) {
            for (CoreAbility ability : CoreAbility.getAbilitiesByInstances()) {
                if (ability == null || ability.isRemoved() || ability.getPlayer() == null
                        || !player.getUniqueId().equals(ability.getPlayer().getUniqueId())) continue;
                result.add(ability.getName());
            }
        }
        return result;
    }
}
