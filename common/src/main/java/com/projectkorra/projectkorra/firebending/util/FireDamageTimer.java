package com.projectkorra.projectkorra.firebending.util;

import com.projectkorra.projectkorra.prediction.rollback.RollbackParticipantAbilityIndices;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.firebending.HeatControl;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.DamageHandler;

import java.util.Map;

public class FireDamageTimer {

    private static final int MAX_TICKS = 90;
    private static final double DAMAGE = 1;
    private static final long BUFFER = 30;

    /**
     * Deprecated. Use FireDamageTimer#FireDamageTimer(final Entity entity, Final Player source, Ability abil)
     * instead.
     *
     * @param entity affected entity.
     * @param source player who used the fire move.
     */
    @Deprecated
    public FireDamageTimer(final Entity entity, final Player source) {
        this(entity, source, null, false);
    }

    public FireDamageTimer(final Entity entity, final Player source, Ability abil) {
        this(entity, source, abil, false);
    }

    public FireDamageTimer(final Entity entity, final Player source, Ability abil, final boolean affectSelf) {
        if (entity.getEntityId() == source.getEntityId() && !affectSelf) {
            return;
        }

        var indices = RollbackParticipantAbilityIndices.current();
        indices.flames.put(entity, source);
        var causes = indices.flameAbilities;
        if (abil == null) causes.remove(entity);
        else causes.put(entity, abil);
    }

    public static boolean isEnflamed(final Entity entity) {
        var indices = RollbackParticipantAbilityIndices.current();
        if (!indices.flames.containsKey(entity)) return false;
        Long previous = indices.flameTimes.get(entity);
        long now = RollbackClock.millis();
        if (previous != null && now < previous + BUFFER) return false;
        indices.flameTimes.put(entity, now);
        return true;
    }

    public static void dealFlameDamage(final Entity entity, final double damage) {
        var indices = RollbackParticipantAbilityIndices.current();
        if (indices.flames.containsKey(entity) && entity instanceof LivingEntity) {
            if (entity instanceof Player) {
                if (!HeatControl.canBurn((Player) entity)) {
                    return;
                }
            }
            final LivingEntity Lentity = (LivingEntity) entity;
            final Player source = indices.flames.get(entity);

            // damages the entity.
            Ability ability = indices.flameAbilities.get(entity);
            if (ability == null) {
                DamageHandler.damageEntity(Lentity, source, damage, CoreAbility.getAbilitiesByElement(Element.FIRE).get(0), false, true);
            } else {
                DamageHandler.damageEntity(Lentity, source, damage, ability, false, true);
            }

            if (entity.getFireTicks() > MAX_TICKS) {
                entity.setFireTicks(MAX_TICKS);
            }
        }
    }

    public static void dealFlameDamage(final Entity entity) {
        dealFlameDamage(entity, DAMAGE);
    }

    public static void handleFlames() {
        var indices = RollbackParticipantAbilityIndices.current();
        for (final Entity entity : indices.flames.keySet()) {
            if (entity.getFireTicks() <= 0) {
                indices.flames.remove(entity);
                indices.flameTimes.remove(entity);
                indices.flameAbilities.remove(entity);
            }
        }
    }

    /**
     * Util so that players can find benders who were hurt/killed by bending
     * fire as opened to regular firetick.
     *
     * @return Map from Entity to Player, entity on fire to player who set them
     * alight with firebending.
     */
    public static Map<Entity, Player> getInstances() {
        return RollbackParticipantAbilityIndices.current().flames;
    }

    /** Drops outgoing-world entity references without applying damage. */
    public static void discardAllTracking() {
        var indices = RollbackParticipantAbilityIndices.current();
        indices.flames.clear();
        indices.flameTimes.clear();
        indices.flameAbilities.clear();
    }
}
