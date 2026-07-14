package com.projectkorra.projectkorra.platform.mc.event.entity;

import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.event.Event;

public class EntityRegainHealthEvent extends Event {
    private final LivingEntity entity;
    private double amount;
    private boolean cancelled;
    public EntityRegainHealthEvent(LivingEntity entity, double amount, RegainReason reason) {
        this.entity = entity;
        this.amount = amount;
    }

    public LivingEntity getEntity() {
        return entity;
    }

    public double getAmount() {
        return amount;
    }

    public void setAmount(double amount) {
        this.amount = amount;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public enum RegainReason {CUSTOM}
}
