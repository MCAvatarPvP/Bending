package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.airbending.AirGlider;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.event.AbilityVelocityAffectEntityEvent;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.platform.mc.event.Listener;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageEvent;

/** Ends an active AirGlider when its rider takes a real external hit. */
public final class AirGliderHitListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageEvent event) {
        if (event.getDamage() > 0.0 && event.getEntity() instanceof Player player) {
            removeGlider(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityDamage(final AbilityDamageEntityEvent event) {
        if (event.getDamage() > 0.0 && event.getEntity() instanceof Player player) {
            removeGlider(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityVelocity(final AbilityVelocityAffectEntityEvent event) {
        if (!(event.getAffected() instanceof Player player)) return;
        final Ability source = event.getAbility();
        // AirBlast self-propulsion is an intended part of AirGlider movement.
        if (source == null || source.getPlayer() == null
                || source.getPlayer().getUniqueId().equals(player.getUniqueId())) return;
        removeGlider(player);
    }

    private static void removeGlider(final Player player) {
        final AirGlider glider = CoreAbility.getAbility(player, AirGlider.class);
        if (glider != null && !glider.isRemoved()) glider.remove();
    }
}
