package com.jedk1.jedcore.ability.avatar.elementsphere;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;

public class ESWater extends AvatarAbility implements AddonAbility {

    private Location location;
    private Vector direction;
    private double travelled;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.SPEED)
    private int speed;

    public ESWater(Player player) {
        super(player);
        if (!hasAbility(player, ElementSphere.class)) {
            return;
        }
        ElementSphere currES = getAbility(player, ElementSphere.class);
        if (currES.getWaterUses() == 0) {
            return;
        }
        if (bPlayer.isOnCooldown("ESWater")) {
            return;
        }
        if (RegionProtection.isRegionProtected(this, player.getTargetBlock(getTransparentMaterialSet(), (int) range).getLocation())) {
            return;
        }
        setFields();
        location = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().multiply(1));
        start();
        if (!isRemoved()) {
            bPlayer.addCooldown("ESWater", getCooldown());
            currES.setWaterUses(currES.getWaterUses() - 1);
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Avatar.ElementSphere.Water.Cooldown");
        range = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.ElementSphere.Water.Range");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.ElementSphere.Water.Damage");
        speed = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Avatar.ElementSphere.Water.Speed");
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (travelled >= range) {
            remove();
            return;
        }
        advanceAttack();
    }

    private void advanceAttack() {
        for (int i = 0; i < speed; i++) {
            travelled++;
            if (travelled >= range)
                return;

            if (!player.isDead())
                direction = GeneralMethods.getDirection(player.getLocation(), GeneralMethods.getTargetedLocation(player, range, Material.WATER)).normalize();
            location = location.add(direction.clone().multiply(1));
            if (RegionProtection.isRegionProtected(this, location)) {
                travelled = range;
                return;
            }
            if (GeneralMethods.isSolid(location.getBlock()) || !isTransparent(location.getBlock())) {
                travelled = range;
                return;
            }

            WaterAbility.playWaterbendingSound(location);
            new RegenTempBlock(location.getBlock(), Material.WATER, Material.WATER.createBlockData(bd -> ((Levelled) bd).setLevel(0)), 100L);

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 2.5)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand) && !RegionProtection.isRegionProtected(this, entity.getLocation()) && !((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                    DamageHandler.damageEntity(entity, damage, this);
                    travelled = range;
                }
            }
        }
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public String getName() {
        return "ElementSphereWater";
    }

    @Override
    public boolean isHiddenAbility() {
        return true;
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
        return null;
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

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Avatar.ElementSphere.Enabled");
    }
}
