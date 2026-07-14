package com.projectkorra.projectkorra.platform.mc.entity;

import com.projectkorra.projectkorra.platform.mc.block.Block;

public class Arrow extends AbstractArrow {
    private Object shooter;

    public PickupStatus getPickupStatus() {
        return PickupStatus.ALLOWED;
    }

    public void setPickupStatus(PickupStatus status) {
    }

    public void setKnockbackStrength(int strength) {
    }

    public void setDamage(int damage) {
    }

    public void setBounce(boolean bounce) {
    }

    public void setCritical(boolean critical) {
    }

    public boolean isInBlock() {
        return getAttachedBlock() != null;
    }

    public boolean isInWaterOrBubbleColumn() {
        return getLocation().getBlock().isLiquid();
    }

    public Object getShooter() {
        return shooter;
    }

    public void setShooter(Object shooter) {
        this.shooter = shooter;
    }

    public Block getAttachedBlock() {
        return null;
    }
}
