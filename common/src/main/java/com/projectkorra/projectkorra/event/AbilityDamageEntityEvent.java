package com.projectkorra.projectkorra.event;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.Cancellable;
import com.projectkorra.projectkorra.platform.mc.event.Event;
import com.projectkorra.projectkorra.platform.mc.event.HandlerList;

/**
 * Called when an ability damages an {@link Entity}
 *
 * @author kingbirdy
 *
 */
public class AbilityDamageEntityEvent extends Event implements Cancellable {

    private static final HandlerList handlers = new HandlerList();
    private final Entity entity;
    private final Player source;
    private final Ability ability;
    private final boolean ignoreArmor;
    private boolean cancelled = false;
    private double damage;

    /**
     * Create a new AbilityDamageEntityEvent
     *
     * @param entity  The entity that was damaged
     * @param ability The damaging ability
     * @param damage  The amount of damage done
     */
    public AbilityDamageEntityEvent(final Entity entity, final Ability ability, final double damage, final boolean ignoreArmor) {
        this(entity, ability.getPlayer(), ability, damage, ignoreArmor);
    }

    /**
     * Create a new AbilityDamageEntityEvent
     *
     * @param entity  The entity that was damaged
     * @param source  The source of the damage
     * @param ability The damaging ability
     * @param damage  The amount of damage done
     */
    public AbilityDamageEntityEvent(final Entity entity, final Player source, final Ability ability, final double damage, final boolean ignoreArmor) {
        this.entity = entity;
        this.source = source;
        this.ability = ability;
        this.damage = damage;
        this.ignoreArmor = ignoreArmor;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }

    /**
     * Returns the damage dealt to the entity
     *
     * @return the amount of damage done
     */
    public double getDamage() {
        return this.damage;
    }

    /**
     * Sets the damage dealt to the entity
     *
     * @param damage the amount of damage done
     */
    public void setDamage(final double damage) {
        this.damage = damage;
    }

    /**
     * Gets the entity that was damaged
     *
     * @return the damaged entity
     */
    public Entity getEntity() {
        return this.entity;
    }

    /**
     * Gets the ability used
     *
     * @return ability used
     */
    public Ability getAbility() {
        return this.ability;
    }

    /**
     * Returns whether the ability ignores armor
     *
     * @return true if the ability ignores armor
     */
    public boolean doesIgnoreArmor() {
        return this.ignoreArmor;
    }

    /**
     * Gets the player that used the ability
     *
     * @return player that used ability
     */
    public Player getSource() {
        return this.source;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }
}
