package com.projectkorra.projectkorra.listener;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.EventHandler;
import com.projectkorra.projectkorra.platform.mc.event.EventPriority;
import com.projectkorra.projectkorra.platform.mc.event.Listener;

public class AirScooterHitListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAbilityDamage(final AbilityDamageEntityEvent event) {
        if (event.getDamage() > 0.0 && event.getEntity() instanceof Player player) {
            checkScooter(player, event.getDamage());
        }
    }

    void checkScooter(Player player, final double damage) {
        AirScooter scooter = CoreAbility.getAbility(player, AirScooter.class);
        if (scooter != null)
            scooter.onDamage(damage);
    }
}
