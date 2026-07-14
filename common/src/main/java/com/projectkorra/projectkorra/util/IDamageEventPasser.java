package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.event.entity.EntityDamageByEntityEvent;

public interface IDamageEventPasser {

    EntityDamageByEntityEvent createEvent(Player player, Entity source, double damage);
}