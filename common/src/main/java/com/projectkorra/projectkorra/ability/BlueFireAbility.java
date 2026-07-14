package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class BlueFireAbility extends FireAbility implements SubAbility {

    public BlueFireAbility(final Player player) {
        super(player);
    }

    public static double getDamageFactor() {
        return ConfigManager.getConfig().getDouble("Properties.Fire.BlueFire.DamageFactor");
    }

    public static double getCooldownFactor() {
        return ConfigManager.getConfig().getDouble("Properties.Fire.BlueFire.CooldownFactor");
    }

    public static double getRangeFactor() {
        return ConfigManager.getConfig().getDouble("Properties.Fire.BlueFire.RangeFactor");
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return FireAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.BLUE_FIRE;
    }
}
