package com.projectkorra.projectkorra.platform.mc.event.entity;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;

import java.util.EnumMap;

public class EntityDamageEvent extends Event implements Cancellable {
    private final EnumMap<DamageModifier, Double> modifiers = new EnumMap<>(DamageModifier.class);
    private Entity entity;
    private double damage;
    private DamageCause cause = DamageCause.CUSTOM;
    private boolean cancelled;

    public EntityDamageEvent() {
    }

    public EntityDamageEvent(Entity entity, DamageCause cause, double damage) {
        this.entity = entity;
        setCause(cause);
        setDamage(damage);
    }

    public Entity getEntity() {
        return entity;
    }

    protected void setEntity(Entity entity) {
        this.entity = entity;
    }

    public DamageCause getCause() {
        return cause;
    }

    protected void setCause(DamageCause cause) {
        this.cause = cause == null ? DamageCause.CUSTOM : cause;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public double getDamage(DamageModifier modifier) {
        return modifiers.getOrDefault(modifier, modifier == DamageModifier.BASE ? damage : 0D);
    }

    public void setDamage(DamageModifier modifier, double damage) {
        modifiers.put(modifier, damage);
        if (modifier == DamageModifier.BASE) this.damage = damage;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public enum DamageModifier {BASE, ARMOR, MAGIC}

    public static class DamageCause {
        public static final DamageCause CUSTOM = new DamageCause();
        public static final DamageCause ENTITY_ATTACK = new DamageCause();
        public static final DamageCause ENTITY_SWEEP_ATTACK = new DamageCause();
        public static final DamageCause FIRE = new DamageCause();
        public static final DamageCause FIRE_TICK = new DamageCause();
        public static final DamageCause LAVA = new DamageCause();
        public static final DamageCause FALL = new DamageCause();
        public static final DamageCause SUFFOCATION = new DamageCause();
        public static final DamageCause FLY_INTO_WALL = new DamageCause();
    }
}
