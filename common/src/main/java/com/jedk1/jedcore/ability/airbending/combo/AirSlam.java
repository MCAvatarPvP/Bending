package com.jedk1.jedcore.ability.airbending.combo;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.ThrownEntityTracker;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.object.HorizontalVelocityTracker;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;

import java.util.ArrayList;

public class AirSlam extends AirAbility implements AddonAbility, ComboAbility {
    private static final long MAX_LIFT_WAIT = 500L;
    private static final long STEERING_DURATION = 300L;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long failedAttemptCooldown;
    @Attribute(Attribute.KNOCKBACK)
    private double power;
    @Attribute(Attribute.RANGE)
    private int range;

    private LivingEntity target;
    private boolean launched;
    private long launchTime;

    public AirSlam(Player player) {
        super(player);

        if (!bPlayer.canBendIgnoreBinds(this)) {
            return;
        }

        setFields();

        Entity target = GeneralMethods.getTargetedEntity(player, range, new ArrayList<>());
        if (!(target instanceof LivingEntity)
                || RegionProtection.isRegionProtected(this, target.getLocation())
                || ((target instanceof Player) && Commands.invincible.contains(target.getName()))) {
            showFailedAttempt();
            bPlayer.addCooldown(this, failedAttemptCooldown);
            return;
        }
        this.target = (LivingEntity) target;

        start();
        if (!isRemoved()) {
            if (target instanceof Player) {
                final AirScooter airScooter = getAbility((Player) target, AirScooter.class);
                if (airScooter != null) {
                    airScooter.remove();
                }
            }
            bPlayer.addCooldown(this);
            GeneralMethods.setVelocity(this, target, new Vector(0, 2, 0));
            showLiftParticles(target.getLocation());
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Air.AirCombo.AirSlam.Cooldown");
        failedAttemptCooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Air.AirCombo.AirSlam.FailedAttemptCooldown");
        power = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Air.AirCombo.AirSlam.Power");
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Air.AirCombo.AirSlam.Range");
    }

    @Override
    public void progress() {
        if (player == null || player.isDead() || !player.isOnline()) {
            remove();
            return;
        }
        if (target == null || target.isDead() || !target.isValid()) {
            remove();
            return;
        }

        long now = System.currentTimeMillis();
        if (!launched && (!GeneralMethods.isOnGround(target) || now >= getStartTime() + MAX_LIFT_WAIT)) {
            launched = true;
            launchTime = now;
            Vector dir = getSteeredVelocity();
            GeneralMethods.setVelocity(this, target, dir);
            new ThrownEntityTracker(this, target, player, 0L);
            target.setFallDistance(0);
            showSlamParticles(target.getLocation(), dir);
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BREEZE_WIND_BURST, 1F, 0.9F);
        }
        if (launched && now < launchTime + STEERING_DURATION) {
            GeneralMethods.setVelocity(this, target, getSteeredVelocity());
            target.setFallDistance(0);
        }
        if (launched && now >= launchTime + STEERING_DURATION) {
            new HorizontalVelocityTracker(target, player, 0L, this);
            remove();
            return;
        }
        playAirbendingParticles(target.getLocation().clone().add(0, 0.8, 0), 3, 0.25, 0.35, 0.25, 0.01);
    }

    private Vector getSteeredVelocity() {
        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() == 0) {
            direction = new Vector(0, 0, 1);
        }
        return direction.normalize().multiply(power).setY(0.05);
    }

    private void showFailedAttempt() {
        Location origin = player.getEyeLocation();
        Vector direction = origin.getDirection().normalize();
        for (double distance = 0.75; distance <= Math.min(range, 4); distance += 0.5) {
            playAirbendingParticles(origin.clone().add(direction.clone().multiply(distance)), 1, 0.04, 0.04, 0.04, 0);
        }
        Location end = origin.clone().add(direction.multiply(Math.min(range, 4)));
        playAirbendingParticles(end, 8, 0.2, 0.2, 0.2, 0.02);
    }

    private void showLiftParticles(Location location) {
        for (int ring = 0; ring < 3; ring++) {
            for (int point = 0; point < 12; point++) {
                double angle = Math.PI * 2 * point / 12 + ring * 0.35;
                Location particle = location.clone().add(Math.cos(angle) * 0.75, 0.25 + ring * 0.55, Math.sin(angle) * 0.75);
                playAirbendingParticles(particle, 1, 0, 0.05, 0, 0.01);
            }
        }
    }

    private void showSlamParticles(Location location, Vector direction) {
        Location center = location.clone().add(0, 1, 0);
        center.getWorld().spawnParticle(Particle.GUST, center, 3, 0.35, 0.35, 0.35, 0);
        for (double distance = 0; distance <= 1.5; distance += 0.3) {
            playAirbendingParticles(center.clone().add(direction.clone().normalize().multiply(distance)), 3, 0.18, 0.18, 0.18, 0.03);
        }
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return target != null ? target.getLocation() : null;
    }

    @Override
    public String getName() {
        return "AirSlam";
    }

    @Override
    public boolean isHiddenAbility() {
        return false;
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
    public Object createNewComboInstance(Player player) {
        return new AirSlam(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        return ComboUtil.generateCombinationFromList(this, JedCoreConfig.getConfig(bPlayer).getStringList("Abilities.Air.AirCombo.AirSlam.Combination"));
    }

    @Override
    public String getInstructions() {
        return JedCoreConfig.getConfig(bPlayer).getString("Abilities.Air.AirCombo.AirSlam.Instructions");
    }

    @Override
    public String getDescription() {
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Air.AirCombo.AirSlam.Description");
    }

    @Override
    public String getAuthor() {
        return JedCore.dev;
    }

    @Override
    public String getVersion() {
        return JedCore.version;
    }

    public double getPower() {
        return power;
    }

    public void setPower(double power) {
        this.power = power;
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public LivingEntity getTarget() {
        return target;
    }

    public void setTarget(LivingEntity target) {
        this.target = target;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Air.AirCombo.AirSlam.Enabled");
    }
}
