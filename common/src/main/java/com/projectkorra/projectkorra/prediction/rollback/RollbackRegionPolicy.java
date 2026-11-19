package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import java.util.*;

/** Portable, per-player protection masks over the captured duel arena. */
public final class RollbackRegionPolicy {
    private final Map<UUID, int[]> players;
    private static final int WIDTH = 7;
    public RollbackRegionPolicy(Map<UUID, List<RegionProtectionAuthority.Box>> boxes) {
        players = new TreeMap<>();
        boxes.forEach((id, cells) -> {
            int[] values = new int[Math.multiplyExact(cells.size(), WIDTH)];
            int i = 0;
            for (var box : cells) {
                values[i++] = Math.toIntExact(box.abilityMask());
                values[i++] = box.minX(); values[i++] = box.minY(); values[i++] = box.minZ();
                values[i++] = box.maxX(); values[i++] = box.maxY(); values[i++] = box.maxZ();
            }
            players.put(id, values);
        });
    }

    public RegionProtectionHook bind(RollbackRosterViews roster) {
        if (!roster.players().keySet().equals(players.keySet())) throw new IllegalArgumentException("Region policy roster mismatch");
        var bounds = roster.world().terrain().bounds();
        var copied = new TreeMap<UUID, int[]>();
        players.forEach((id, values) -> {
            if (values == null || values.length % WIDTH != 0 || values.length > 16_384 * WIDTH)
                throw new IllegalArgumentException("Arena region policy exceeds budget");
            for (int i = 0; i < values.length; i += WIDTH) {
                if (values[i] < 1 || values[i] > 511 || values[i+1] > values[i+4] || values[i+2] > values[i+5] || values[i+3] > values[i+6]
                        || !bounds.contains(new Position(values[i+1], values[i+2], values[i+3]))
                        || !bounds.contains(new Position(values[i+4], values[i+5], values[i+6])))
                    throw new IllegalArgumentException("Invalid captured region box");
            }
            copied.put(id, values.clone());
        });
        return new Bound(roster, bounds, copied);
    }
    private record Bound(RollbackRosterViews roster, Bounds bounds, Map<UUID, int[]> rows)
            implements RegionProtectionHook, RollbackStateCell<Void> {
        @Override @SuppressWarnings("WrapperReferenceEquality")
        public boolean isRegionProtected(com.projectkorra.projectkorra.platform.mc.entity.Player player,
                com.projectkorra.projectkorra.platform.mc.Location at, com.projectkorra.projectkorra.ability.CoreAbility ability) {
            if (roster.players().get(player.getUniqueId()) != player || at.getWorld() != roster.world())
                throw new IllegalArgumentException("Foreign arena protection query");
            int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
            if (!bounds.contains(new Position(x, y, z))) return true;
            int mask = ability == null ? 1 : 1 << (1 + (ability.isHarmlessAbility() ? 1 : 0)
                    + (ability.isIgniteAbility() ? 2 : 0) + (ability.isExplosiveAbility() ? 4 : 0));
            var cells = rows.get(player.getUniqueId());
            for (int i = 0; i < cells.length; i += WIDTH)
                if ((cells[i] & mask) != 0 && x >= cells[i+1] && y >= cells[i+2] && z >= cells[i+3]
                        && x <= cells[i+4] && y <= cells[i+5] && z <= cells[i+6]) return true;
            return false;
        }
        @Override public Void captureRollbackState() { return null; }
        @Override public void restoreRollbackState(Void state) { }
    }
}
