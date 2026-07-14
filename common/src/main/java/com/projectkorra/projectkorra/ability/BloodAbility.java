package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class BloodAbility extends WaterAbility implements SubAbility {

    public BloodAbility(final Player player) {
        super(player);
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return WaterAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.BLOOD;
    }
}
