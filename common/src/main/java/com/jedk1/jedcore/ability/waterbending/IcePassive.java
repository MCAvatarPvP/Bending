package com.jedk1.jedcore.ability.waterbending;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.IceAbility;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.HashMap;
import java.util.Map;

public class IcePassive {

    @SuppressWarnings("deprecation")
    public static void handleSkating() {
        Map<World, Pair<Boolean, Integer>> resultCache = new HashMap<>();

        for (Player player : ProjectKorra.plugin.getServer().getOnlinePlayers()) {
            Pair<Boolean, Integer> result = resultCache.get(player.getWorld());
            if (result == null) {
                BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
                boolean enabled = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Water.Ice.Passive.Skate.Enabled");
                int speedFactor = JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Water.Ice.Passive.Skate.SpeedFactor");

                result = new Pair<>(enabled, speedFactor);
                resultCache.put(player.getWorld(), result);
            }

            boolean enabled = result.first;
            int speedFactor = result.second;

            if (!enabled) continue;

            BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
            if (bPlayer != null && bPlayer.canIcebend() && bPlayer.isElementToggled(Element.WATER) && bPlayer.hasElement(Element.WATER) && !JCMethods.isDisabledWorld(player.getWorld())) {
                if (player.isSprinting() && IceAbility.isIce(player.getLocation().getBlock().getRelative(BlockFace.DOWN)) && GeneralMethods.isOnGround(player)) {
                    ParticleEffect.SNOW_SHOVEL.display(player.getLocation().clone().add(0, 0.2, 0), 15, Math.random() / 2, Math.random() / 2, Math.random() / 2, 0);
                    player.removePotionEffect(PotionEffectType.SPEED);
                    player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, speedFactor));
                }
            }
        }
    }

    private static class Pair<T, U> {
        T first;
        U second;

        Pair(T first, U second) {
            this.first = first;
            this.second = second;
        }
    }
}
