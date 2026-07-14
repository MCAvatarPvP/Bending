package com.jedk1.jedcore.policies.removal;

import com.projectkorra.projectkorra.configuration.PKConfigurationSection;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.function.Supplier;

public class OutOfRangeRemovalPolicy implements RemovalPolicy {
    private Supplier<Location> fromSupplier;
    private Player player;
    private double range;

    public OutOfRangeRemovalPolicy(Player player, double range, Supplier<Location> from) {
        this.player = player;
        this.range = range;
        this.fromSupplier = from;
    }

    @Override
    public boolean shouldRemove() {
        if (this.range == 0) return false;

        Location from = this.fromSupplier.get();
        return from.distanceSquared(this.player.getLocation()) >= (this.range * this.range);
    }

    @Override
    public void load(PKConfigurationSection config) {
        this.range = config.getDouble("Range");
    }

    @Override
    public String getName() {
        return "OutOfRange";
    }
}
