package com.jedk1.jedcore.ability.waterbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;

public class DrainBlast extends WaterAbility implements AddonAbility {

    @Attribute(Attribute.RANGE)
    private final double blastRange; // 20
    @Attribute(Attribute.DAMAGE)
    private final double blastDamage; // 1.5
    @Attribute(Attribute.SPEED)
    private final double blastSpeed; // 2
    private Location location;
    private Vector direction;
    private double travelled;

    public DrainBlast(Player player, double range, double damage, double speed, int holdrange) {
        super(player);
        this.blastRange = range;
        this.blastDamage = damage;
        this.blastSpeed = speed;
        location = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().multiply(holdrange));
        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (travelled >= blastRange) {
            remove();
            return;
        }
        advanceAttack();
    }

    private void advanceAttack() {
        for (int i = 0; i < blastSpeed; i++) {
            travelled++;
            if (travelled >= blastRange)
                return;

            if (!player.isDead())
                direction = GeneralMethods.getDirection(player.getLocation(), GeneralMethods.getTargetedLocation(player, blastRange, Material.WATER)).normalize();
            location = location.add(direction.clone().multiply(1));
            if (GeneralMethods.isSolid(location.getBlock()) || !isTransparent(location.getBlock())) {
                travelled = blastRange;
                return;
            }

            playWaterbendingSound(location);
            new RegenTempBlock(location.getBlock(), Material.WATER, Material.WATER.createBlockData(bd -> ((Levelled) bd).setLevel(0)), 100L);

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 2.5)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand)) {
                    DamageHandler.damageEntity(entity, blastDamage, this);
                    travelled = blastRange;
                }
            }
        }
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public String getName() {
        return "Drain";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Water.Drain.Description");
    }

    public Vector getDirection() {
        return direction;
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public double getDistanceTravelled() {
        return travelled;
    }

    public void setDistanceTravelled(double travelled) {
        this.travelled = travelled;
    }

    public double getBlastRange() {
        return blastRange;
    }

    public double getBlastDamage() {
        return blastDamage;
    }

    public double getBlastSpeed() {
        return blastSpeed;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.Drain.Enabled");
    }
}
