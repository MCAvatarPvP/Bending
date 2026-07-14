package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class HealingAbility extends WaterAbility implements SubAbility {

    public HealingAbility(final Player player) {
        super(player);
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return WaterAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.HEALING;
    }
}
