package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.airbending.passive.AirSaturation;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.waterbending.passive.HydroSink;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;

public class RegenHandler implements Runnable {
    public double getHealthPerTick() {
        return ConfigManager.getConfig().getDouble("Properties.Regen.HealthPerTick");
    }

    public ProjectKorra plugin;

    public RegenHandler(final ProjectKorra plugin) {
        this.plugin = plugin;
    }

    public long regenInterval(BendingPlayer bendingPlayer) {
        final long interval = ConfigManager.getConfig().getLong("Properties.Regen.Interval");

        if (bendingPlayer.hasElement(Element.AIR)) {
            CoreAbility airsat = CoreAbility.getAbility(AirSaturation.class);
            if ((airsat == null || !airsat.isEnabled())) {
                return interval;
            }

            if (!PassiveManager.hasPassive(bendingPlayer.getPlayer(), airsat)) {
                return interval;
            }

            double air = AirSaturation.getRegenFactor(bendingPlayer);
            // Should be safe
            return (long) (air * interval);
        } else if (bendingPlayer.hasElement(Element.WATER)) {
            CoreAbility hydroSink = CoreAbility.getAbility(HydroSink.class);
            if ((hydroSink == null || !hydroSink.isEnabled())) {
                return interval;
            }

            if (!PassiveManager.hasPassive(bendingPlayer.getPlayer(), hydroSink)) {
                return interval;
            }

            double air = HydroSink.getRegenFactor(bendingPlayer);
            // Should be safe
            return (long) (air * interval);
        }

        return interval;
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline() || player.isDead() || player.getHealth() >= player.getMaxHealth())
                continue;
            BendingPlayer bendingPlayer = BendingPlayer.getBendingPlayer(player);
            if (bendingPlayer == null || now - bendingPlayer.getLastHealthRegen() < regenInterval(bendingPlayer)) continue;

            bendingPlayer.setLastHealthRegen(now);
            EntityRegainHealthEvent event = new EntityRegainHealthEvent(player, getHealthPerTick(), EntityRegainHealthEvent.RegainReason.CUSTOM);
            Bukkit.getPluginManager().callEvent(event);

            if (event.isCancelled()) continue;

            double newHealth = Math.min(
                    player.getHealth() + event.getAmount(),
                    player.getMaxHealth()
            );
            player.setHealth(newHealth);
        }
    }
}
