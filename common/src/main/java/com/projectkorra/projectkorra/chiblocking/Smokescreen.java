package com.projectkorra.projectkorra.chiblocking;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Snowball;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleUtil;

import java.util.Map;
import com.projectkorra.projectkorra.prediction.rollback.RollbackParticipantAbilityIndices;

public class Smokescreen extends ChiAbility {

    private Snowball projectile;

    public Snowball getProjectile() { return projectile; }

    @Attribute(Attribute.DURATION)
    private int duration;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RADIUS)
    private double radius;
    private double speed;

    public Smokescreen(final Player player) {
        super(player);
        if (!this.bPlayer.canBend(this)) {
            return;
        }
        this.cooldown = getConfig().getLong("Abilities.Chi.Smokescreen.Cooldown");
        this.duration = getConfig().getInt("Abilities.Chi.Smokescreen.Duration");
        this.radius = getConfig().getDouble("Abilities.Chi.Smokescreen.Radius");
        this.speed = getConfig().getDouble("Abilities.Chi.Smokescreen.Speed", 1.2);
        this.start();
        this.player.getLocation().getWorld().playSound(this.player.getLocation(), Sound.BLOCK_LILY_PAD_PLACE, 1, 1.5f);
    }

    public static void playEffect(final Location loc) {
        int z = -2;
        int x = -2;
        final int y = 0;

        for (int i = 0; i < 125; i++) {
            final Location newLoc = new Location(loc.getWorld(), loc.getX() + x, loc.getY() + y, loc.getZ() + z);
            // All gusts at this point have the same spread and speed in one packet.
            ParticleUtil.spawn(Particle.SMALL_GUST, newLoc, 32, 0.5, 0.5, 0.5);
            if (z == 2) {
                z = -2;
            }
            if (x == 2) {
                x = -2;
                z++;
            }
            x++;
        }
    }

    public static void removeFromHashMap(final Entity entity) {
        if (!(entity instanceof Player player)) return;
        var indices = RollbackParticipantAbilityIndices.current();
        String name = player.getName();
        Long started = indices.smokeTimes.get(name);
        var smoke = indices.smokeEffects.get(name);
        if (started != null && (smoke == null || RollbackClock.millis() - started >= smoke.duration * 1_000L)) {
            indices.smokeTimes.remove(name);
            indices.smokeEffects.remove(name);
            indices.smokeNames.remove(name);
        }
    }

    public static Map<Integer, Smokescreen> getSnowballs() {
        return RollbackParticipantAbilityIndices.current().smokeShots;
    }

    public static Map<String, Long> getBlindedTimes() {
        return RollbackParticipantAbilityIndices.current().smokeTimes;
    }

    public static Map<String, Smokescreen> getBlindedToAbility() {
        return RollbackParticipantAbilityIndices.current().smokeEffects;
    }

    @Override
    public void progress() {
        projectile = fireCleanSnowball(player, speed);
        getSnowballs().put(projectile.getEntityId(), this);
        this.bPlayer.addCooldown(this);
        this.remove();
    }

    public void applyBlindness(final Entity entity) {
        if (entity instanceof Player) {
            if (Commands.invincible.contains(((Player) entity).getName())) {
                return;
            } else if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) {
                return;
            }
            final Player p = (Player) entity;
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, this.duration * 20, 2));
            var indices = RollbackParticipantAbilityIndices.current();
            indices.smokeTimes.put(p.getName(), RollbackClock.millis());
            indices.smokeEffects.put(p.getName(), this);
            indices.smokeNames.put(p.getName(), p.getUniqueId());
        }
    }

    public Snowball fireCleanSnowball(Player p, double speed) {
        Vector dir = p.getEyeLocation().getDirection().normalize();

        Location spawn = p.getEyeLocation().add(dir.clone().multiply(0.16));

        Snowball sb = p.getWorld().spawn(spawn, Snowball.class, s -> {
            s.setShooter(p);
        });

        Vector desired = dir.multiply(speed);

        sb.setVelocity(desired);

        return sb;
    }

    @Override
    public String getName() {
        return "Smokescreen";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public int getDuration() {
        return this.duration;
    }

    public void setDuration(final int duration) {
        this.duration = duration;
    }

    public double getRadius() {
        return this.radius;
    }

    public void setRadius(final double radius) {
        this.radius = radius;
    }
}
