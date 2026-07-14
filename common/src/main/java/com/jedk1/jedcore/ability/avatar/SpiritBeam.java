package com.jedk1.jedcore.ability.avatar;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;

public class SpiritBeam extends AvatarAbility implements AddonAbility {

    private Location location;
    private Vector direction;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private double range;
    private boolean avatarOnly;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private boolean damagesBlocks;
    private long regen;
    @Attribute(Attribute.RADIUS)
    private double radius;

    public SpiritBeam(Player player) {
        super(player);
        if (bPlayer.isOnCooldown(this)) {
            return;
        }

        setFields();

        if (avatarOnly && !bPlayer.isAvatarState()) {
            return;
        }

        start();
    }

    public void setFields() {
        duration = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Avatar.SpiritBeam.Duration");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Avatar.SpiritBeam.Cooldown");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.SpiritBeam.Damage");
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Avatar.SpiritBeam.Range");
        avatarOnly = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Avatar.SpiritBeam.AvatarStateOnly");
        damagesBlocks = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Avatar.SpiritBeam.BlockDamage.Enabled");
        regen = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Avatar.SpiritBeam.BlockDamage.Regen");
        radius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.SpiritBeam.BlockDamage.Radius");
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        if (System.currentTimeMillis() > getStartTime() + duration) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        if (!player.isSneaking()) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        if (avatarOnly && !bPlayer.isAvatarState()) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }
        createBeam();
    }

    private void createBeam() {
        location = player.getLocation().add(0, 1.2, 0);
        direction = location.getDirection();
        for (double i = 0; i < range; i += 0.5) {
            location = location.add(direction.multiply(0.5).normalize());

            if (RegionProtection.isRegionProtected(player, location, this)) {
                return;
            }

            ParticleEffect.SPELL_WITCH.display(location, 1, 0f, 0f, 0f, 0f);
            ParticleEffect.SPELL_WITCH.display(location, 1, (float) Math.random() / 3, (float) Math.random() / 3, (float) Math.random() / 3, 0f);
            ParticleEffect.BLOCK_CRACK.display(location, 1, (float) Math.random() / 3, (float) Math.random() / 3, (float) Math.random() / 3, 0.1F, Material.NETHER_PORTAL.createBlockData());
            ParticleEffect.BLOCK_CRACK.display(location, 1, direction.getX(), direction.getY(), direction.getZ(), 0.1F, Material.NETHER_PORTAL.createBlockData());

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 2)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand)) {
                    entity.setFireTicks(100);
                    DamageHandler.damageEntity(entity, damage, this);
                }
            }

            if (location.getBlock().getType().isSolid()) {
                location.getWorld().createExplosion(location, 0F);
                if (damagesBlocks) {
                    //new TempExplosion(player, location.getBlock(), "SpiritBeam", radius, regen, damage, false);
                    for (Location loc : GeneralMethods.getCircle(location, (int) radius, 0, false, true, 0)) {
                        if (JCMethods.isUnbreakable(loc.getBlock())) continue;
                        new RegenTempBlock(loc.getBlock(), Material.AIR, Material.AIR.createBlockData(), regen, false);
                    }
                }
                return;
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
        return "SpiritBeam";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Avatar.SpiritBeam.Description");
    }

    public Vector getDirection() {
        return direction;
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public boolean isAvatarOnly() {
        return avatarOnly;
    }

    public void setAvatarOnly(boolean avatarOnly) {
        this.avatarOnly = avatarOnly;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public boolean damagesBlocks() {
        return damagesBlocks;
    }

    public void setDamagesBlocks(boolean blockdamage) {
        this.damagesBlocks = blockdamage;
    }

    public long getRegen() {
        return regen;
    }

    public void setRegen(long regen) {
        this.regen = regen;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = radius;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Avatar.SpiritBeam.Enabled");
    }
}
