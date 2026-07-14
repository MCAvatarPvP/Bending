package com.jedk1.jedcore.ability.chiblocking;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.chiblocking.passive.ChiPassive;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

public class Backstab extends ChiAbility implements AddonAbility {

    public Backstab(Player player) {
        super(player);
    }

    public static boolean punch(Player player, LivingEntity target) {
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        CoreAbility ability = CoreAbility.getAbility("Backstab");

        if (bPlayer == null || !bPlayer.canBend(ability)) {
            return false;
        }
        double activationAngle = Math.toRadians(JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Chi.Backstab.MaxActivationAngle", 90));

        Vector targetDirection = target.getLocation().getDirection().setY(0).normalize();
        Vector toTarget = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0).normalize();

        double angle = toTarget.angle(targetDirection);

        if (angle <= activationAngle && target.getLocation().distanceSquared(player.getLocation()) <= 5 * 5) {
            bPlayer.addCooldown(ability);

            if (target instanceof Player) {
                ChiPassive.blockChi((Player) target);
            }

            return true;
        }

        return false;
    }

    public static double getDamage(BendingPlayer bPlayer) {
        return JedCoreConfig.getConfig(bPlayer).getDouble("Abilities.Chi.Backstab.Damage");
    }

    @Override
    public void progress() {
    }

    @Override
    public long getCooldown() {
        return JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Chi.Backstab.Cooldown");
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "Backstab";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Chi.Backstab.Description");
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Chi.Backstab.Enabled");
    }
}
