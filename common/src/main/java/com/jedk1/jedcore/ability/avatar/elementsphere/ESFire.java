package com.jedk1.jedcore.ability.avatar.elementsphere;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.BlueFireAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.firebending.BlazeArc;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;

public class ESFire extends AvatarAbility implements AddonAbility {

    private Location location;
    private Vector direction;
    private double travelled;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.FIRE_TICK)
    private long burnTime;
    @Attribute(Attribute.SPEED)
    private int speed;
    private boolean controllable;

    public ESFire(Player player) {
        super(player);
        if (!hasAbility(player, ElementSphere.class)) {
            return;
        }
        ElementSphere currES = getAbility(player, ElementSphere.class);
        if (currES.getFireUses() == 0) {
            return;
        }
        if (bPlayer.isOnCooldown("ESFire")) {
            return;
        }
        setFields();
        start();
        if (!isRemoved()) {
            bPlayer.addCooldown("ESFire", getCooldown());
            currES.setFireUses(currES.getFireUses() - 1);
            location = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().multiply(1));
            direction = location.getDirection().clone();
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Avatar.ElementSphere.Fire.Cooldown");
        range = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.ElementSphere.Fire.Range");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.ElementSphere.Fire.Damage");
        burnTime = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Avatar.ElementSphere.Fire.BurnDuration");
        speed = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Avatar.ElementSphere.Fire.Speed");
        controllable = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Avatar.ElementSphere.Fire.Controllable");

        applyModifiers();
    }

    private void applyModifiers() {
        if (bPlayer.canUseSubElement(SubElement.BLUE_FIRE)) {
            cooldown *= BlueFireAbility.getCooldownFactor();
            range *= BlueFireAbility.getRangeFactor();
            damage *= BlueFireAbility.getDamageFactor();
        }
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

            if (!player.isDead() && controllable)
                direction = GeneralMethods.getDirection(player.getLocation(), GeneralMethods.getTargetedLocation(player, range, Material.WATER)).normalize();

            location = location.add(direction.clone().multiply(1));
            if (RegionProtection.isRegionProtected(this, location)) {
                travelled = range;
                return;
            }
            if (GeneralMethods.isSolid(location.getBlock()) || (isWater(location.getBlock()) && !FireAbility.canPassThroughWater(location.getBlock()))) {
                travelled = range;
                return;
            }

            ParticleEffect flame = bPlayer.hasSubElement(Element.BLUE_FIRE) ? ParticleEffect.SOUL_FIRE_FLAME : ParticleEffect.FLAME;
            flame.display(location, 5, Math.random(), Math.random(), Math.random(), 0.02);
            ParticleEffect.SMOKE_LARGE.display(location, 2, Math.random(), Math.random(), Math.random(), 0.01);
            FireAbility.playFirebendingSound(location);

            placeFire();

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 2.5)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand) && !RegionProtection.isRegionProtected(this, entity.getLocation()) && !((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                    DamageHandler.damageEntity(entity, damage, this);
                    entity.setFireTicks(Math.round(burnTime / 50F));
                    travelled = range;
                }
            }
        }
    }

    private void placeFire() {
        if (GeneralMethods.isSolid(location.getBlock().getRelative(BlockFace.DOWN))) {
            location.getBlock().setType(Material.FIRE);
            new BlazeArc(player, location, direction, 2);
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
        return "ElementSphereFire";
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

    public long getBurnTime() {
        return burnTime;
    }

    public void setBurnTime(long burnTime) {
        this.burnTime = burnTime;
    }

    public int getSpeed() {
        return speed;
    }

    public void setSpeed(int speed) {
        this.speed = speed;
    }

    public boolean isControllable() {
        return controllable;
    }

    public void setControllable(boolean controllable) {
        this.controllable = controllable;
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
