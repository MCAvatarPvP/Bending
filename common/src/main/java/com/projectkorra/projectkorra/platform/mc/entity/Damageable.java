package com.projectkorra.projectkorra.platform.mc.entity;

public interface Damageable {
    void damage(double damage);

    void damage(double damage, Entity source);

    double getHealth();

    double getMaxHealth();
}
