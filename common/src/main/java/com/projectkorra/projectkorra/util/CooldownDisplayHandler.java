package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.util.ComboManager;

public class CooldownDisplayHandler implements Runnable {

    @Override
    public void run() {
        for (BendingPlayer bPlayer : BendingPlayer.getPlayers().values()) {
            if (!ComboManager.isUsingComboHelp(bPlayer.getPlayer())) {
                ActionBarStatusManager.displayPersistent(bPlayer.getPlayer());
            }
        }
    }
}
