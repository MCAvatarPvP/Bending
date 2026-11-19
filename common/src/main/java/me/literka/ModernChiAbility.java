package me.literka;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.ChiCombatAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class ModernChiAbility extends ChiCombatAbility {
    public ModernChiAbility(final Player player) {
        super(player);
    }

    @Override
    public boolean isIgniteAbility() {
        return false;
    }

    @Override
    public boolean isExplosiveAbility() {
        return false;
    }

    @Override
    public Element getElement() {
        return ChiRework.MODERN_CHI;
    }
}
