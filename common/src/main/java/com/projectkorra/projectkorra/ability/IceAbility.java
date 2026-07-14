package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class IceAbility extends WaterAbility implements SubAbility {

    public IceAbility(final Player player) {
        super(player);
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return WaterAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.ICE;
    }
}
