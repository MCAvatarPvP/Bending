package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.FireTick;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.BlueFireAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.firebending.FireJet;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleEffect;

public class FireSki extends FireAbility implements AddonAbility {

    private Location location;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.SPEED)
    private double speed;
    private boolean ignite;
    @Attribute(Attribute.FIRE_TICK)
    private int fireTicks;
    private double requiredHeight;
    private boolean flyInDirection;
    private boolean cancelOnJet;

    public FireSki(Player player) {
        super(player);
        if (!isEnabled()) {
            return;
        }

        setFields();

        if (hasAbility(player, FireSki.class)) {
            FireSki fs = getAbility(player, FireSki.class);
            fs.remove();
            return;
        }

        if (!bPlayer.canBend(getAbility("FireJet"))) {
            return;
        }

        FireJet jet = CoreAbility.getAbility(player, FireJet.class);
        if (jet != null && jet.isStarted() && cancelOnJet) {
            // JetSki replaces the active jet. Leaving both instances alive causes
            // two velocity writers and makes the client/server disagree over which
            // movement should win.
            jet.remove();
        }

        if (CollisionDetector.isOnGround(player) || CollisionDetector.distanceAboveGround(player) < requiredHeight) {
            return;
        }

        this.flightHandler.createInstance(player, this.getName());

        location = player.getLocation();
        player.setAllowFlight(true);
        player.setFlying(true);

        bPlayer.addCooldown(getAbility("FireJet"), getCooldown());
        start();
    }

    public static boolean isPunchActivated(BendingPlayer bPlayer) {
        return JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.FireSki.PunchActivated");
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.FireSki.Cooldown");
        duration = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.FireSki.Duration");
        speed = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.FireSki.Speed");
        ignite = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FireSki.IgniteEntities");
        fireTicks = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Fire.FireSki.FireTicks");
        requiredHeight = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.FireSki.RequiredHeight");
        flyInDirection = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FireSki.FlyInDirection");
        cancelOnJet = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FireSki.CancelOnJet", true);

        applyModifiers();
    }

    private void applyModifiers() {
        if (bPlayer.canUseSubElement(SubElement.BLUE_FIRE)) {
            cooldown *= BlueFireAbility.getCooldownFactor();
        }

        if (isDay(player.getWorld())) {
            cooldown -= ((long) getDayFactor(cooldown) - cooldown);
        }
    }

    private void allowFlight() {
        player.setAllowFlight(true);
        player.setFlying(true);
    }

    private void removeFlight() {
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreCooldowns(getAbility("FireJet"))) {
            remove();
            return;
        }
        if (!collision()) {
            movePlayer();
            if (System.currentTimeMillis() > getStartTime() + duration || (isWater(player.getLocation().getBlock()) && !FireAbility.canPassThroughWater(player.getLocation().getBlock()))) {
                remove();
            }
        } else {
            remove();
        }
    }

    private void movePlayer() {
        location = player.getEyeLocation();
        location.setPitch(0);
        Vector dV = location.getDirection().normalize();
        Vector travel;

        if (getPlayerDistance() > 1.8) {
            removeFlight();
            travel = new Vector(dV.getX() * speed, -0.09, dV.getZ() * speed);
        } else if (getPlayerDistance() < 1.7) {
            allowFlight();
            travel = new Vector(dV.getX() * speed, 0.2, dV.getZ() * speed);
        } else {
            travel = new Vector(dV.getX() * speed, 0, dV.getZ() * speed);
        }

        if (flyInDirection) travel.setY(dV.getY() * speed);

        playFirebendingSound(player.getLocation());
        createBeam();

        if (ignite) {
            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(player.getLocation().clone().add(0, -1, 0), 2.0)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId()) {
                    FireTick.set(entity, this.fireTicks);
                }
            }
        }

        GeneralMethods.setVelocity(this, player, travel);
        player.setFallDistance(0);
    }

    private double getPlayerDistance() {
        Location l = player.getLocation().clone();
        while (l.getBlockY() > l.getWorld().getMinHeight() && !GeneralMethods.isSolid(l.getBlock())) {
            l.add(0, -0.1, 0);
        }
        return player.getLocation().getY() - l.getY();
    }

    private void createBeam() {
        Location right = player.getEyeLocation().add(getRightHeadDirection(player).multiply(1.75));
        right.setPitch(-15);
        Location right1 = right.subtract(right.getDirection().multiply(4)).add(0, -1.5, 0);

        Location left = player.getEyeLocation().add(getLeftHeadDirection(player).multiply(1.75));
        left.setPitch(-15);
        Location left1 = left.subtract(left.getDirection().multiply(4)).add(0, -1.5, 0);

        double size = 0;

        for (Location l : JCMethods.getLinePoints(player.getEyeLocation().add(0, -0.5, 0).add(getRightHeadDirection(player).multiply(0.2)), right1, 6)) {
            size += 0.05;
            playFirebendingParticles(l, 4, (Math.random() * size + 0.01), (Math.random() * size + 0.01), (Math.random() * size + 0.01));
            ParticleEffect.SMOKE_NORMAL.display(l, 1, (Math.random() * size + 0.01), (Math.random() * size + 0.01), (Math.random() * size + 0.01), 0.08);
        }

        size = 0;
        for (Location l : JCMethods.getLinePoints(player.getEyeLocation().add(0, -0.5, 0).add(getLeftHeadDirection(player).multiply(0.2)), left1, 6)) {
            size += 0.05;
            playFirebendingParticles(l, 4, (Math.random() * size + 0.01), (Math.random() * size + 0.01), (Math.random() * size + 0.01));
            ParticleEffect.SMOKE_NORMAL.display(l, 1, (Math.random() * size + 0.01), (Math.random() * size + 0.01), (Math.random() * size + 0.01), 0.08);
        }
    }

    public Vector getRightHeadDirection(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        return new Vector(-direction.getZ(), 0.0, direction.getX()).normalize();
    }

    public Vector getLeftHeadDirection(Player player) {
        Vector direction = player.getLocation().getDirection().normalize();
        return new Vector(direction.getZ(), 0.0, -direction.getX()).normalize();
    }

    private boolean collision() {
        Location l = player.getEyeLocation();
        l.setPitch(0);
        Vector dV = l.getDirection().normalize();
        l.add(new Vector(dV.getX() * 0.8, 0, dV.getZ() * 0.8));

        if (l.getBlock().getType().isSolid()) {
            return true;
        }
        if (l.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
            return true;
        }
        return l.clone().add(0, -2, 0).getBlock().getType().isSolid();
    }

    @Override
    public void remove() {
        removeFlight();

        this.flightHandler.removeInstance(player, this.getName());

        super.remove();
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
        return null;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public String getName() {
        return "FireSki";
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

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public double getSpeed() {
        return speed;
    }

    public void setSpeed(double speed) {
        this.speed = speed;
    }

    public boolean isIgnite() {
        return ignite;
    }

    public void setIgnite(boolean ignite) {
        this.ignite = ignite;
    }

    public int getFireTicks() {
        return fireTicks;
    }

    public void setFireTicks(int fireTicks) {
        this.fireTicks = fireTicks;
    }

    public double getRequiredHeight() {
        return requiredHeight;
    }

    public void setRequiredHeight(double requiredHeight) {
        this.requiredHeight = requiredHeight;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FireSki.Enabled");
    }
}
