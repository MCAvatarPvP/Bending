package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.mc.damage.DamageSource;
import com.projectkorra.projectkorra.platform.mc.damage.DamageType;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;

public class ModernDamageEventPasser implements IDamageEventPasser {
    @Override
    public EntityDamageByEntityEvent createEvent(Player player, Entity source, double damage) {
        return new EntityDamageByEntityEvent(source, player, EntityDamageByEntityEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), damage);
    }
}