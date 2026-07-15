package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.AirShieldReflector;
import com.jedk1.jedcore.util.FireTick;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.airbending.AirShield;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.colliders.Sphere;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class FireShots extends FireAbility implements AddonAbility {

    private final List<FireShot> shots = new ArrayList<>();
    public int amount;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute("MaxShots")
    private int startAmount;
    @Attribute(Attribute.FIRE_TICK)
    private int fireticks;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("CollisionRadius")
    private double collisionRadius;

    public FireShots(Player player) {
        super(player);

        if (!bPlayer.canBend(this) || hasAbility(player, FireShots.class)) {
            return;
        }

        setFields();

        amount = startAmount;
        start();
    }

    public static void fireShot(Player player) {
        FireShots fs = getAbility(player, FireShots.class);
        if (fs != null) {
            fs.fireShot();
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.FireShots.Cooldown");
        startAmount = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Fire.FireShots.FireBalls");
        fireticks = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Fire.FireShots.FireDuration");
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Fire.FireShots.Range");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.FireShots.Damage");
        collisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.FireShots.CollisionRadius");

        applyModifiers();
    }

    private void applyModifiers() {
        if (bPlayer.canUseSubElement(SubElement.BLUE_FIRE)) {
            cooldown *= BlueFireAbility.getCooldownFactor();
            range *= BlueFireAbility.getRangeFactor();
            damage *= BlueFireAbility.getDamageFactor();
        }

        if (isDay(player.getWorld())) {
            cooldown -= ((long) getDayFactor(cooldown) - cooldown);
            range = (int) getDayFactor(range);
            damage = getDayFactor(damage);
        }
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            amount = 0;
            if (!bPlayer.isOnCooldown(this)) {
                bPlayer.addCooldown(this);
            }
        }

        shots.removeIf(shot -> !shot.progress());

        if (amount <= 0 && shots.isEmpty()) {
            remove();
            return;
        }

        if (amount > 0) {
            displayFireBalls();
        }
    }

    public void fireShot() {
        if (amount >= 1) {
            if (--amount <= 0) {
                bPlayer.addCooldown(this);
            }
            shots.add(new FireShot(this, player, getRightHandPos(), range, fireticks, damage));
        }
    }

    public Location getRightHandPos() {
        return GeneralMethods.getRightSide(player.getLocation(), .55).add(0, 1.2, 0);
    }

    private void displayFireBalls() {
        playFirebendingParticles(getRightHandPos().toVector().add(player.getEyeLocation().getDirection().clone().multiply(.8D)).toLocation(player.getWorld()), 3, 0, 0, 0);
        ParticleEffect.SMOKE_NORMAL.display(getRightHandPos().toVector().add(player.getEyeLocation().getDirection().clone().multiply(.8D)).toLocation(player.getWorld()), 3, 0, 0, 0, 0.01);
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

    @Override
    public List<Location> getLocations() {
        List<Location> list = shots.stream().map(shot -> shot.location).collect(Collectors.toList());
        list.add(getRightHandPos());
        return list;
    }

    @Override
    public void handleCollision(Collision collision) {
        if (collision.isRemovingFirst()) {
            Optional<FireShot> collidedShot = shots.stream().filter(shot -> shot.location.equals(collision.getLocationFirst())).findAny();

            collidedShot.ifPresent(shots::remove);
        } else {
            CoreAbility second = collision.getAbilitySecond();
            if (second instanceof AirShield) {
                boolean reflect = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FireShots.Collisions.AirShield.Reflect", true);

                if (reflect) {
                    Optional<FireShot> collidedShot = shots.stream().filter(shot -> shot.location.equals(collision.getLocationFirst())).findAny();

                    if (collidedShot.isPresent()) {
                        FireShot fireShot = collidedShot.get();
                        AirShield shield = (AirShield) second;

                        fireShot.direction = player.getLocation().getDirection().clone();
                        AirShieldReflector.reflect(shield, fireShot.location, fireShot.direction);
                    }
                }
            }
        }
    }

    @Override
    public String getName() {
        return "FireShots";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Fire.FireShots.Description");
    }

    public List<FireShot> getShots() {
        return shots;
    }

    public int getStartAmount() {
        return startAmount;
    }

    public void setStartAmount(int startAmount) {
        this.startAmount = startAmount;
    }

    public int getFireticks() {
        return fireticks;
    }

    public void setFireticks(int fireticks) {
        this.fireticks = fireticks;
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    @Override
    public double getCollisionRadius() {
        return collisionRadius;
    }

    public void setCollisionRadius(double collisionRadius) {
        this.collisionRadius = collisionRadius;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.FireShots.Enabled");
    }

    public class FireShot {

        private final Ability ability;
        private final Player player;
        private final int range;
        private final int fireTicks;
        private final double damage;
        private Location origin, location;
        private Vector direction = null;
        private double forwardSpeed, sideSpeed;

        public FireShot(Ability ability, Player player, Location location, int range, int fireTicks, double damage) {
            this.ability = ability;
            this.player = player;
            this.origin = location;
            this.location = origin.clone();
            this.range = range;
            this.fireTicks = fireTicks;
            this.damage = damage;
            this.forwardSpeed = JedCoreConfig.getConfig(bPlayer).getDouble("Abilities.Fire.FireShots.ForwardSpeed");
            this.sideSpeed = JedCoreConfig.getConfig(bPlayer).getDouble("Abilities.Fire.FireShots.SideSpeed");
        }

        public boolean progress() {
            final boolean authoritative = CooldownSync.isAuthoritative();
            if (player.isDead() || !player.isOnline()) {
                return false;
            }
            if (origin.distance(location) >= range) {
                return false;
            }
            for (int i = 0; i < 2; i++) {
                if (origin.distance(location) >= range)
                    return false;

                Vector dir = direction;
                if (dir == null) {
                    dir = this.player.getLocation().getDirection().multiply(forwardSpeed);
                }

                Location playerLoc = player.getLocation();
                if (playerLoc.getWorld() != location.getWorld()) {
                    return false;
                }
                double distance = playerLoc.distance(location);
                Location targetLoc = playerLoc.clone().add(playerLoc.getDirection().normalize().multiply(distance));
                Vector sideDir = targetLoc.toVector().subtract(location.toVector()).normalize();
                sideDir.multiply(Math.min(targetLoc.distance(location), sideSpeed));

                location = location.add(dir);
                location = location.add(sideDir);

                if (GeneralMethods.isSolid(location.getBlock()) || (isWater(location.getBlock()) && !FireAbility.canPassThroughWater(location.getBlock()))) {
                    return false;
                }

                playFirebendingParticles(location, 5, 0.0, 0.0, 0.0, 0.02);
                ParticleEffect.SMOKE_NORMAL.display(location, 2, 0.0, 0.0, 0.0, 0.01);

                Sphere collider = new Sphere(location, collisionRadius);

                boolean hit = CollisionDetector.checkEntityCollisions(player, collider, (entity) -> {
                    if (!authoritative) return true;
                    DamageHandler.damageEntity(entity, damage, ability);
                    FireTick.set(entity, Math.round(fireTicks / 50F));
                    new FireDamageTimer(entity, player, FireShots.this);
                    return true;
                });

                if (hit && authoritative) {
                    return false;
                }
            }
            return true;
        }

        public Ability getAbility() {
            return ability;
        }

        public Player getPlayer() {
            return player;
        }

        public Location getLocation() {
            return location;
        }

        public void setLocation(Location location) {
            this.location = location;
        }

        public int getRange() {
            return range;
        }

        public int getFireTicks() {
            return fireTicks;
        }

        public double getDamage() {
            return damage;
        }

        public Vector getDirection() {
            return direction;
        }

        public void setDirection(Vector direction) {
            this.direction = direction;
        }
    }
}
