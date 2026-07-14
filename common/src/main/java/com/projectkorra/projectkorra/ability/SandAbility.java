package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class SandAbility extends EarthAbility implements SubAbility {

    public SandAbility(final Player player) {
        super(player);
    }

    @Override
    public Class<? extends Ability> getParentAbility() {
        return EarthAbility.class;
    }

    @Override
    public Element getElement() {
        return Element.SAND;
    }
}
