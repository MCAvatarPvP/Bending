package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.Location;
import java.util.IdentityHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Per-capture portable value views; native world/player identity is handled by roster bindings. */
public final class PaperRollbackGraphViews implements Supplier<Function<Object, RollbackStateTransfer.Replacement>> {
    @Override public Function<Object, RollbackStateTransfer.Replacement> get() {
        var locations = new IdentityHashMap<org.bukkit.Location, Location>();
        return value -> {
            var nativeLocation = BukkitMC.nativeLocationView(value);
            if (nativeLocation == null) return null;
            var portable = locations.computeIfAbsent(nativeLocation, source -> {
                var copy = new Location(BukkitMC.world(source.getWorld()), source.getX(), source.getY(), source.getZ());
                copy.setYaw(source.getYaw()); copy.setPitch(source.getPitch());
                return copy;
            });
            return RollbackStateTransfer.Replacement.fromProjection(portable);
        };
    }
}
