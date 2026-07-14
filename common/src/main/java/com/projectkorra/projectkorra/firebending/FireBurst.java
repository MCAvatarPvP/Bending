package com.projectkorra.projectkorra.firebending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class FireBurst extends FireAbility {

    @Attribute("Charged")
    private boolean charged;
    private boolean launched;
    @Attribute(Attribute.DAMAGE)
    @DayNightFactor
    private double damage;
    @Attribute(Attribute.CHARGE_DURATION)
    @DayNightFactor(invert = true)
    private long chargeTime;
    @Attribute(Attribute.RANGE)
    @DayNightFactor
    private double range;
    private double collisionRadius;
    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long cooldown;
    private boolean canSwapSlots;
    private boolean allowWhenFireShield;
    private double angleTheta;
    private double anglePhi;
    private double particlesPercentage;
    private ArrayList<FireBlast> blasts;

    public FireBurst(final Player player) {
        super(player);

        this.charged = false;
        this.damage = getConfig().getDouble("Abilities.Fire.FireBurst.Damage");
        this.chargeTime = (long) getConfig().getLong("Abilities.Fire.FireBurst.ChargeTime");
        this.range = getConfig().getDouble("Abilities.Fire.FireBurst.Range");
        this.collisionRadius = getConfig().getDouble("Abilities.Fire.FireBurst.CollisionRadius");
        this.cooldown = getConfig().getLong("Abilities.Fire.FireBurst.Cooldown");
        this.angleTheta = getConfig().getDouble("Abilities.Fire.FireBurst.AngleTheta");
        this.anglePhi = getConfig().getDouble("Abilities.Fire.FireBurst.AnglePhi");
        this.particlesPercentage = getConfig().getDouble("Abilities.Fire.FireBurst.ParticlesPercentage");
        this.canSwapSlots = getConfig().getBoolean("Abilities.Fire.FireBurst.CanSwapSlots");
        this.allowWhenFireShield = getConfig().getBoolean("Abilities.Fire.FireBurst.AllowWhenFireShield");
        blasts = new ArrayList<>();

        if (!this.bPlayer.canBend(this) || hasAbility(player, FireBurst.class)) {
            return;
        }

        this.start();
    }

    public static void coneBurst(final Player player) {
        final FireBurst burst = getAbility(player, FireBurst.class);
        if (burst != null && !burst.launched) {
            burst.coneBurst();
        }
    }

    private void coneBurst() {
        if (this.charged) {
            final Location location = this.player.getEyeLocation();
            final List<Block> safeBlocks = GeneralMethods.getBlocksAroundPoint(this.player.getLocation(), 2);
            final Vector vector = location.getDirection();

            final double angle = Math.toRadians(30);
            double x, y, z;
            final double r = 1;

            for (double theta = 0; theta <= 180; theta += this.angleTheta) {
                final double dphi = this.anglePhi / Math.sin(Math.toRadians(theta));
                for (double phi = 0; phi < 360; phi += dphi) {
                    final double rphi = Math.toRadians(phi);
                    final double rtheta = Math.toRadians(theta);

                    x = r * Math.cos(rphi) * Math.sin(rtheta);
                    y = r * Math.sin(rphi) * Math.sin(rtheta);
                    z = r * Math.cos(rtheta);
                    final Vector direction = new Vector(x, z, y);

                    if (direction.angle(vector) <= angle) {
                        final FireBlast fblast = new FireBlast(location, direction.normalize(), this.player, this.damage, safeBlocks);
                        range = getConfig().getDouble("Abilities.Fire.FireBurst.LeftClickRange");
                        fblast.setRange(this.range);
                        fblast.setFireBurst(true);
                        getBlasts().add(fblast);
                    }
                }
            }
            this.bPlayer.addCooldown(this);
            launched = true;
        }
    }

    /**
     * To combat the sphere FireBurst lag we are only going to show a certain
     * percentage of FireBurst particles at a time per tick. As the bursts
     * spread out then we can show more at a time.
     */
    public void handleSmoothParticles() {
        for (int i = 0; i < getBlasts().size(); i++) {
            final FireBlast fblast = getBlasts().get(i);
            final int toggleTime = (int) (i % (100.0 / this.particlesPercentage));
            new BukkitRunnable() {
                @Override
                public void run() {
                    fblast.setShowParticles(true);
                }
            }.runTaskLater(ProjectKorra.plugin, toggleTime);
        }
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        }

        Iterator<FireBlast> iter = getBlasts().iterator();
        while (iter.hasNext()) {
            FireBlast blast = iter.next();
            if (blast.isRemoved()) iter.remove();
        }

        if (launched && getBlasts().isEmpty()) {
            remove();
            return;
        }

        String name = bPlayer.getBoundAbilityName();
        if (!launched && !(name.equalsIgnoreCase(getName()) || canSwapSlots && name.equalsIgnoreCase("FireJet"))) {
            remove();
            return;
        }

        if (!launched && !allowWhenFireShield && CoreAbility.hasAbility(player, FireShield.class)) {
            remove();
            return;
        }

        if (System.currentTimeMillis() > this.getStartTime() + this.chargeTime && !this.charged) {
            this.charged = true;
        }

        if (!launched && !this.player.isSneaking()) {
            if (this.charged) {
                this.sphereBurst();
            } else {
                this.remove();
            }
        } else if (!launched && this.charged) {
            final Location location = this.player.getEyeLocation();
            location.add(location.getDirection());
            playFirebendingParticles(location, 1, .01, .01, .01);
            emitFirebendingLight(location);
        }
    }

    private void sphereBurst() {
        if (this.charged) {
            final Location location = this.player.getEyeLocation();
            final List<Block> safeblocks = GeneralMethods.getBlocksAroundPoint(this.player.getLocation(), 2);
            double x, y, z;
            final double r = 1;

            for (double theta = 0; theta <= 180; theta += this.angleTheta) {
                final double dphi = this.anglePhi / Math.sin(Math.toRadians(theta));
                for (double phi = 0; phi < 360; phi += dphi) {
                    final double rphi = Math.toRadians(phi);
                    final double rtheta = Math.toRadians(theta);

                    x = r * Math.cos(rphi) * Math.sin(rtheta);
                    y = r * Math.sin(rphi) * Math.sin(rtheta);
                    z = r * Math.cos(rtheta);

                    final Vector direction = new Vector(x, z, y);
                    final FireBlast fblast = new FireBlast(location, direction.normalize(), this.player, this.damage, safeblocks);

                    fblast.setRange(this.range);
                    fblast.setShowParticles(false);
                    fblast.setFireBurst(true);
                    getBlasts().add(fblast);
                }
            }
            this.bPlayer.addCooldown(this);
        }
        this.handleSmoothParticles();
        launched = true;
    }

    @Override
    public void handleCollision(Collision collision) {
        if (collision.isRemovingFirst()) {
            ParticleEffect.BLOCK_CRACK.display(collision.getLocationFirst(), 10, 1, 1, 1, 0.1, getFireType().createBlockData());

            double distance = -1;
            FireBlast closest = null;
            for (FireBlast blast : getBlasts()) {
                double dis = closest == null ? 0 : blast.getActualLocation().distance(closest.getActualLocation());
                if (distance == -1 || dis < distance) {
                    distance = dis;
                    closest = blast;
                }
            }

            closest.remove();
        }
    }

    @Override
    public String getName() {
        return "FireBurst";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
    }

    @Override
    public List<Location> getLocations() {
        ArrayList<Location> locations = new ArrayList<>();
        for (FireBlast blast : getBlasts()) {
            locations.add(blast.getActualLocation());
        }

        return locations;
    }

    @Override
    public double getCollisionRadius() {
        return collisionRadius;
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
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public boolean isCharged() {
        return this.charged;
    }

    public void setCharged(final boolean charged) {
        this.charged = charged;
    }

    public double getDamage() {
        return this.damage;
    }

    public void setDamage(final int damage) {
        this.damage = damage;
    }

    public long getChargeTime() {
        return this.chargeTime;
    }

    public void setChargeTime(final long chargeTime) {
        this.chargeTime = chargeTime;
    }

    public double getRange() {
        return this.range;
    }

    public void setRange(final long range) {
        this.range = range;
    }

    public double getAngleTheta() {
        return this.angleTheta;
    }

    public void setAngleTheta(final double angleTheta) {
        this.angleTheta = angleTheta;
    }

    public double getAnglePhi() {
        return this.anglePhi;
    }

    public void setAnglePhi(final double anglePhi) {
        this.anglePhi = anglePhi;
    }

    public double getParticlesPercentage() {
        return this.particlesPercentage;
    }

    public void setParticlesPercentage(final double particlesPercentage) {
        this.particlesPercentage = particlesPercentage;
    }

    public List<FireBlast> getBlasts() {
        return blasts;
    }
}
