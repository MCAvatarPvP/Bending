package com.projectkorra.projectkorra.ability.activation;

import com.jedk1.jedcore.ability.firebending.FirePunch;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

public final class AddonSlotActivation {
    private AddonSlotActivation() {
    }

    public static void handleSlotChange(Player player, int oneBasedSlot) {
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return;
        }

        if (JedCoreConfig.config == null) {
            return;
        }

        String bound = bPlayer.getAbilities().get(oneBasedSlot);
        FirePunch prepared = CoreAbility.getAbility(player, FirePunch.class);
        if (bound == null || !bound.equalsIgnoreCase("FirePunch")) {
            if (prepared != null) prepared.remove();
            return;
        }

        boolean activationOnPunch = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.FirePunch.ActivationOnPunch");
        if (!activationOnPunch || prepared != null) {
            return;
        }

        if (bPlayer.canBendIgnoreBinds(CoreAbility.getAbility(FirePunch.class))) {
            new FirePunch(player, null);
        }
    }
}
