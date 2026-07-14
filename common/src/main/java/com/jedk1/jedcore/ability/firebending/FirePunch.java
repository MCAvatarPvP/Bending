package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.FireTick;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.BlueFireAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.ParticleUtil;

public class FirePunch extends FireAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.FIRE_TICK)
    private int fireTicks;
    private boolean activationOnPunch;
    private LivingEntity target;

    private Location location;

    public FirePunch(Player player, LivingEntity target) {
        super(player);

        setFields();
        this.target = target;

        start();
    }

    public static void spawnCircleParticles(Location location, ParticleEffect effect, double angleY, double angleX, double step, double startRadius) {
        for (double i = 0; i < 180; i += step) {
            double x, z;
            x = startRadius * Math.cos(Math.toRadians(i));
            z = startRadius * Math.sin(Math.toRadians(i));

            Vector v = new Vector(x, 0, z);
            v.rotateAroundX(Math.toRadians(angleX));
            v.rotateAroundY(Math.toRadians(angleY * -1));
            Vector vel = v.clone().normalize();
            if (vel.getY() == 0) vel.setY(0.00000001);

            Location first = location.clone().add(v);
            Location second = location.clone().subtract(v);
            ParticleUtil.spawn(effect.getParticle(), first, 0, vel.getX(), vel.getY(), vel.getZ(), 0.08, null);
            ParticleUtil.spawn(effect.getParticle(), second, 0, -vel.getX(), -vel.getY(), -vel.getZ(), 0.08, null);
            ParticleEffect.SMOKE_NORMAL.display(first, 1, 0.01, 0.01, 0.01, 0.01);
            ParticleEffect.SMOKE_NORMAL.display(second, 1, 0.01, 0.01, 0.01, 0.01);
        }
    }

    public static void playEffect(Player player) {
        Location location = GeneralMethods.getRightSide(player.getLocation(), 0.55)
                .add(0, 1.2, 0)
                .add(player.getLocation().getDirection().multiply(0.8));
        FireAbility.playFirebendingParticles(BendingPlayer.getBendingPlayer(player), location, 3, 0, 0, 0, 0);
        ParticleEffect.SMOKE_NORMAL.display(location, 1);
    }

    private void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.FirePunch.Cooldown");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.FirePunch.Damage");
        fireTicks = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Fire.FirePunch.FireTicks");
        activationOnPunch = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FirePunch.ActivationOnPunch");

        applyModifiers();
    }

    private void applyModifiers() {
        if (bPlayer.canUseSubElement(Element.BLUE_FIRE)) {
            cooldown *= BlueFireAbility.getCooldownFactor();
            damage *= BlueFireAbility.getDamageFactor();
        }

        if (isDay(player.getWorld())) {
            cooldown -= ((long) getDayFactor(cooldown) - cooldown);
            damage = getDayFactor(damage);
        }
    }

    @Override
    public void progress() {
        if (!player.isOnline() || player.isDead() || !bPlayer.canBend(this)) {
            remove();
            return;
        }

        playEffect(player);
        if (activationOnPunch && target != null) punch(target);
    }

    public void punch(LivingEntity target) {
        remove();
        DamageHandler.damageEntity(target, damage, this);
        Location impactLocation = getImpactLocation(target);
        playHitImpact(target, impactLocation);
        checkKillFinisher(target, impactLocation.clone());
        FireTick.set(target, fireTicks / 50);
        if (cooldown > fireTicks) {
            new FireDamageTimer(target, player, this);
        }
        bPlayer.addCooldown(this);
    }

    private Location getImpactLocation(LivingEntity target) {
        Location base = target.getLocation().clone();
        double attackerEyeY = player.getEyeLocation().getY();
        double targetEyeY = target.getEyeLocation().getY();

        double yOffset;
        if (attackerEyeY >= targetEyeY - 0.15) {
            yOffset = 1.45;
        } else if (attackerEyeY <= base.getY() + 0.7) {
            yOffset = 0.45; // lower body
        } else {
            yOffset = 0.95;
        }
        return base.add(0, yOffset, 0);
    }

    private void playHitImpact(final LivingEntity target, final Location center) {
        Vector vector = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector());
        final Location facing = center.clone().setDirection(vector);
        final ParticleEffect ringParticle = bPlayer.canUseSubElement(Element.BLUE_FIRE) ? ParticleEffect.SOUL_FIRE_FLAME : ParticleEffect.FLAME;
        final double verticalOffset = center.getY() - target.getEyeLocation().getY();
        playFirebendingSound(center);

        double baseAngleX = -120;
        if (verticalOffset > 0.2) {
            baseAngleX = -106;
        } else if (verticalOffset < -0.35) {
            baseAngleX = -136;
        }
        double angleX = baseAngleX + (Math.random() * 14);
        spawnCircleParticles(facing, ringParticle, facing.getYaw(), angleX, 12, 0.1);
    }

    private void checkKillFinisher(final LivingEntity target, final Location hitLocation) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (target.isDead() || !target.isValid() || target.getHealth() <= 0) {
                    playKillFinisher(hitLocation);
                }
            }
        }.runTaskLater(JedCore.plugin, 1);
    }

    private void playKillFinisher(final Location center) {
        final ParticleEffect finisherFlame = bPlayer.canUseSubElement(Element.BLUE_FIRE) ? ParticleEffect.SOUL_FIRE_FLAME : ParticleEffect.FLAME;
        ParticleEffect.EXPLOSION_HUGE.display(center, 1, 0, 0, 0, 0);
        ParticleEffect.SMOKE_LARGE.display(center, 24, 0.4, 0.3, 0.4, 0.08);
        ParticleEffect.FIREWORKS_SPARK.display(center, 30, 0.5, 0.35, 0.5, 0.09);
        playFirebendingSound(center);
        center.getWorld().playSound(center, Sound.ENTITY_BLAZE_SHOOT, 1.4F, 0.6F);
        center.getWorld().playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.8F);

        new BukkitRunnable() {
            private int tick = 0;

            @Override
            public void run() {
                if (tick >= 14) {
                    cancel();
                    return;
                }

                double radius = 0.5 + (tick * 0.19);
                int points = 16 + tick * 2;
                double y = (tick * 0.07) - 0.12;
                for (int i = 0; i < points; i++) {
                    double angle = (Math.PI * 2 * i) / points + (tick * 0.22);
                    Location ringPoint = center.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
                    FireAbility.playFirebendingParticles(bPlayer, ringPoint, 2, 0, 0, 0, 0.02);
                    ParticleEffect.SMOKE_LARGE.display(ringPoint, 1, 0.03, 0.03, 0.03, 0.02);
                    if (tick % 2 == 0) {
                        ParticleEffect.FIREWORKS_SPARK.display(ringPoint, 1, 0.02, 0.02, 0.02, 0.02);
                    }
                }

                finisherFlame.display(center.clone().add(0, y + 0.2, 0), 3, 0.12, 0.12, 0.12, 0.01);
                if (tick == 5 || tick == 10) {
                    center.getWorld().playSound(center, Sound.ENTITY_BLAZE_SHOOT, 0.9F, 0.7F + (tick * 0.03F));
                }
                tick++;
            }
        }.runTaskTimer(JedCore.plugin, 1, 1);
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    public void setCooldown(long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public String getName() {
        return "FirePunch";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Fire.FirePunch.Description");
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public int getFireTicks() {
        return fireTicks;
    }

    public void setFireTicks(int fireTicks) {
        this.fireTicks = fireTicks;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FirePunch.Enabled");
    }
}
