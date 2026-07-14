package com.projectkorra.projectkorra.chiblocking.passive;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.StanceAbility;
import com.projectkorra.projectkorra.airbending.Suffocate;
import com.projectkorra.projectkorra.chiblocking.AcrobatStance;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.util.ChatUtil;

public class ChiPassive {
    public static boolean willChiBlock(final Player attacker, final Player player) {
        return willChiBlock(attacker, player, getChance(BendingPlayer.getBendingPlayer(player)));
    }

    public static boolean willChiBlock(final Player attacker, final Player player, double chance) {
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return false;
        }

        final StanceAbility stance = bPlayer.getStance();
        double newChance = chance;

        if (stance instanceof AcrobatStance) {
            newChance += ((AcrobatStance) stance).getChiBlockBoost();
        }

        if (Math.random() > newChance / 100.0) {
            return false;
        } else if (bPlayer.isChiBlocked()) {
            return false;
        }

        return true;
    }

    public static void blockChi(final Player player) {
        if (Suffocate.isChannelingSphere(player)) {
            Suffocate.remove(player);
        }

        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return;
        }

        bPlayer.blockChi();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, 2, 0);

        final long start = System.currentTimeMillis();
        new BukkitRunnable() {
            @Override
            public void run() {
                ChatUtil.sendActionBar(Element.CHI.getColor() + "* Chiblocked *", player);
                if (System.currentTimeMillis() >= start + getDuration(bPlayer)) {
                    bPlayer.unblockChi();
                    this.cancel();
                }
            }
        }.runTaskTimer(ProjectKorra.plugin, 0, 1);
    }

    public static double getChance(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getDouble("Abilities.Chi.Passive.BlockChi.Chance");
    }

    public static int getDuration(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getInt("Abilities.Chi.Passive.BlockChi.Duration");
    }
}
