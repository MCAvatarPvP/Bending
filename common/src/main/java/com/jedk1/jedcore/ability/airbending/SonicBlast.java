package com.jedk1.jedcore.ability.airbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.Sphere;

public class SonicBlast extends AirAbility implements AddonAbility {

    private Location location;
    private Vector direction;
    private boolean isCharged;
    private int travelled;

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute("CollisionRadius")
    private double entityCollisionRadius;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute("WarmUp")
    private long warmup;
    private int nauseaDur;
    private int blindDur;
    private boolean chargeSwapping;

    public SonicBlast(Player player) {
        super(player);

        if (hasAbility(player, SonicBlast.class) || bPlayer.isOnCooldown(this)) {
            return;
        }

        setFields();
        start();
    }

    public void setFields() {
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.SonicBlast.Damage");
        range = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.SonicBlast.Range");
        entityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.SonicBlast.EntityCollisionRadius");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Air.SonicBlast.Cooldown");
        warmup = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Air.SonicBlast.ChargeTime");
        chargeSwapping = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Air.SonicBlast.ChargeSwapping");
        nauseaDur = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Air.SonicBlast.Effects.NauseaDuration");
        blindDur = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Air.SonicBlast.Effects.BlindnessDuration");
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        CoreAbility boundAbility = bPlayer.getBoundAbility();

        if (!this.chargeSwapping && this.travelled == 0 && !(boundAbility instanceof SonicBlast)) {
            remove();
            return;
        }

        if (player.isSneaking() && travelled == 0) {
            direction = player.getEyeLocation().getDirection().normalize();

            if (isCharged) {
                playAirbendingParticles(player.getLocation().add(0, 1, 0), 5, (float) Math.random(), (float) Math.random(), (float) Math.random());
            } else if (System.currentTimeMillis() > getStartTime() + warmup) {
                isCharged = true;
            }
        } else {
            if (isCharged) {
                if (!bPlayer.isOnCooldown(this)) {
                    bPlayer.addCooldown(this);
                }

                if (travelled < range && isLocationSafe()) {
                    advanceLocation();
                } else {
                    remove();
                }
            } else {
                remove();
            }
        }
    }

    private boolean isLocationSafe() {
        if (location == null) {
            Location origin = player.getEyeLocation().clone();
            location = origin.clone();
        }

        return isTransparent(location.getBlock());
    }

    private void advanceLocation() {
        travelled++;

        if (location == null) {
            Location origin = player.getEyeLocation().clone();
            location = origin.clone();
        }

        for (int i = 0; i < 5; i++) {
            for (int angle = 0; angle < 360; angle += 20) {
                Location temp = location.clone();
                Vector dir = GeneralMethods.getOrthogonalVector(direction.clone(), angle, 1);
                temp.add(dir);
                playAirbendingParticles(temp, 1, 0, 0, 0);
            }

            boolean hit = CollisionDetector.checkEntityCollisions(player, new Sphere(location, entityCollisionRadius), (entity) -> {
                DamageHandler.damageEntity(entity, damage, this);
                LivingEntity lE = (LivingEntity) entity;
                lE.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, nauseaDur / 50, 1));
                lE.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindDur / 50, 1));
                return true;
            });

            if (hit) {
                remove();
                return;
            }

            location = location.add(direction.clone().multiply(0.2));
        }

        location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1, 0);
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public double getCollisionRadius() {
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.SonicBlast.AbilityCollisionRadius");
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public String getName() {
        return "SonicBlast";
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Air.SonicBlast.Description");
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Air.SonicBlast.Enabled");
    }
}
