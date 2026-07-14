package com.projectkorra.projectkorra.platform.mc.event.entity;

import com.projectkorra.projectkorra.platform.mc.damage.DamageSource;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;

public class EntityDamageByEntityEvent extends EntityDamageEvent {
    private Entity damager;

    public EntityDamageByEntityEvent() {
    }

    public EntityDamageByEntityEvent(Entity damager, Entity target, DamageCause cause, DamageSource source, double damage) {
        this.damager = damager;
        setEntity(target);
        setCause(cause);
        setDamage(damage);
    }

    public Entity getDamager() {
        return damager;
    }
}
