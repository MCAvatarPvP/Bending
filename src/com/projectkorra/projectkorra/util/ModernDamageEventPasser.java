package com.projectkorra.projectkorra.util;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

public class ModernDamageEventPasser implements IDamageEventPasser {
    @Override
    public EntityDamageByEntityEvent createEvent(Player player, Entity source, double damage) {
        return new EntityDamageByEntityEvent(source, player, EntityDamageByEntityEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), damage);
    }
}