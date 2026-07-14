package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.attribute.AttributeInstance;
import com.projectkorra.projectkorra.platform.mc.inventory.EntityEquipment;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;

import java.util.Collection;
import java.util.List;

public class LivingEntity extends Entity implements Damageable {
    public Location getEyeLocation() {
        return getLocation();
    }

    public double getEyeHeight() {
        return getHeight() * 0.85;
    }

    public double getHeight() {
        return 1.8;
    }

    public double getHealth() {
        return 0;
    }

    public void setHealth(double h) {
    }

    public double getMaxHealth() {
        return 20;
    }

    public void damage(double damage) {
    }

    public void damage(double damage, Entity source) {
    }

    public boolean hasPotionEffect(PotionEffectType type) {
        return false;
    }

    public void addPotionEffect(PotionEffect effect) {
    }

    public void addPotionEffects(Collection<PotionEffect> effects) {
        if (effects != null) effects.forEach(this::addPotionEffect);
    }

    public boolean addPotionEffect(PotionEffect effect, boolean force) {
        addPotionEffect(effect);
        return true;
    }

    public PotionEffect getPotionEffect(PotionEffectType type) {
        return null;
    }

    public void removePotionEffect(PotionEffectType type) {
    }

    public int getNoDamageTicks() {
        return 0;
    }

    public void setNoDamageTicks(int ticks) {
    }

    public int getMaximumNoDamageTicks() {
        return 20;
    }

    public double getLastDamage() {
        return 0;
    }

    public AttributeInstance getAttribute(Attribute attribute) {
        return new AttributeInstance(getMaxHealth());
    }

    public Collection<PotionEffect> getActivePotionEffects() {
        return List.of();
    }

    public EntityEquipment getEquipment() {
        return new EntityEquipment();
    }

    public void setAI(boolean value) {
    }

    public int getRemainingAir() {
        return 300;
    }

    public void setRemainingAir(int ticks) {
    }
}
