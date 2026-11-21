package com.projectkorra.projectkorra.chiblocking.passive;

import com.projectkorra.projectkorra.prediction.rollback.RollbackTimeShift;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackTaskBindings;
import com.projectkorra.projectkorra.ability.ChiAbility;

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

        if (ChiAbility.passiveChiBlockRoll(attacker, player) > newChance / 100.0) {
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

        new BlockTimer(player, bPlayer).runTaskTimer(ProjectKorra.plugin, 0, 1);
    }

    private static final class BlockTimer extends BukkitRunnable implements RollbackTaskBindings.TimedCallback {
        private final Player player;
        private final BendingPlayer bender;
        private long start = RollbackClock.millis();

        private BlockTimer(Player player, BendingPlayer bender) {
            this.player = player;
            this.bender = bender;
        }

        @Override public void rebase(RollbackTimeShift time) { start = time.time(start); }

        @Override public void run() {
            ChatUtil.sendActionBar(Element.CHI.getColor() + "* Chiblocked *", player);
            if (RollbackClock.millis() >= start + getDuration(bender)) {
                bender.unblockChi();
                cancel();
            }
        }
    }

    public static double getChance(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getDouble("Abilities.Chi.Passive.BlockChi.Chance");
    }

    public static int getDuration(BendingPlayer bPlayer) {
        return ConfigManager.getConfig(bPlayer).getInt("Abilities.Chi.Passive.BlockChi.Duration");
    }
}
