package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.prediction.hit.PredictedContactSync;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public abstract class ChiAbility extends ChiCombatAbility {

    public ChiAbility(final Player player) {
        super(player);
    }

    /** A predicted attack reports contact. Only the simulation that owns the defender can commit a stun. */
    protected final boolean canParalyze(Entity target) {
        if (!(target instanceof LivingEntity) || PredictedContactSync.mark(this, target)) return false;
        if (!(target instanceof Player defender)) return true;
        BendingPlayer profile = BendingPlayer.getBendingPlayer(defender);
        return profile != null && !Commands.invincible.contains(defender.getName()) && profile.canBeChiblocked();
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
        return Element.CHI;
    }

    public boolean isModern() {
        return false;
    }
}
