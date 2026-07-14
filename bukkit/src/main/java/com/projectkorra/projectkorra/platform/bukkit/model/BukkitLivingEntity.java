package com.projectkorra.projectkorra.platform.bukkit.model;

import com.projectkorra.projectkorra.platform.model.PKEntity;
import com.projectkorra.projectkorra.platform.model.PKLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

public class BukkitLivingEntity extends BukkitEntity implements PKLivingEntity {
    protected final LivingEntity living;

    public BukkitLivingEntity(final LivingEntity living) {
        super(living);
        this.living = living;
    }

    @Override
    public double health() {
        return this.living.getHealth();
    }

    @Override
    public void damage(final double amount, final PKEntity source) {
        this.living.damage(amount, source == null ? null : source.handle(Entity.class));
    }
}
