package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

/**
 * Cancellable event called when an ability would push or alter the velocity of
 * an entity.
 * <p>
 * the entity can be changed, vector can be modified, and the ability that
 * caused the change can be accessed.
 *
 * @author dNiym
 *
 */

public class AbilityVelocityAffectEntityEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    Entity affected;
    Vector velocity;
    Ability ability;
    boolean cancelled = false;

    public AbilityVelocityAffectEntityEvent(Ability ability, Entity entity, Vector vector) {
        this.affected = entity;
        this.ability = ability;
        this.velocity = vector;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public Entity getAffected() {
        return affected;
    }

    public void setAffected(Entity affected) {
        this.affected = affected;
    }

    public Vector getVelocity() {
        return velocity;
    }

    public void setVelocity(Vector velocity) {
        this.velocity = velocity;
    }

    public Ability getAbility() {
        return ability;
    }
}
