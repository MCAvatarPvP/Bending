package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.policies.removal.*;
import com.jedk1.jedcore.util.FireTick;
import com.jedk1.jedcore.util.MaterialUtil;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.Element.SubElement;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.prediction.EntityHitboxProvider;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.colliders.Sphere;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class Combustion extends CombustionAbility implements AddonAbility, EntityHitboxProvider {

    private State state;
    private Location location;
    private List<Location> entityHitLocations = List.of();
    private double entityHitRadius;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private CompositeRemovalPolicy removalPolicy;

    public Combustion(Player player) {
        super(player);

        if (!isEnabled()) return;

        if (this.player == null || !bPlayer.canBend(this) || !bPlayer.canCombustionbend() || hasAbility(player, Combustion.class)) {
            return;
        }

        setFields();

        start();
    }

    public static void combust(Player player) {
        if (hasAbility(player, Combustion.class)) {
            Combustion c = getAbility(player, Combustion.class);

            c.selfCombust();
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.Combustion.Cooldown");

        this.location = null;
        this.state = new ChargeState();

        this.removalPolicy = new CompositeRemovalPolicy(this,
                new CannotBendRemovalPolicy(this.bPlayer, this, true, true),
                new IsOfflineRemovalPolicy(this.player),
                new IsDeadRemovalPolicy(this.player),
                new SwappedSlotsRemovalPolicy<>(bPlayer, Combustion.class)
        );

        this.removalPolicy.load(JedCoreConfig.getConfig(this.bPlayer), "Abilities.Fire.Combustion");
    }

    @Override
    public void progress() {
        if (this.removalPolicy.shouldRemove()) {
            remove();
            return;
        }

        state.update();
    }

    public void selfCombust() {
        if (this.state instanceof TravelState) {
            this.state = new CombustState(location);
        }
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        // Only enable the collision while traveling.
        if (state instanceof TravelState) {
            return location;
        }

        return null;
    }

    @Override
    public double getCollisionRadius() {
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.Combustion.AbilityCollisionRadius");
    }

    @Override
    public List<Location> getEntityHitLocations() {
        return entityHitLocations.stream().map(Location::clone).toList();
    }

    @Override
    public double getEntityHitRadius() {
        return entityHitRadius;
    }

    @Override
    public String getName() {
        return "Combustion";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Fire.Combustion.Description");
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.Combustion.Enabled");
    }

    @Override
    public void handleCollision(final Collision collision) {
        super.handleCollision(collision);

        if (collision.isRemovingFirst()) {
            state = new CombustState(collision.getLocationFirst());
        }
    }

    private interface State {
        void update();
    }

    private interface ExplosionMethod {
        // Returns how many blocks were destroyed.
        int explode(Location location, double size, double damage, int fireTick);
    }

    // This is the initial state of Combustion.
    // It's used to render the particle ring that happens during charging.
    // This state transitions to TravelState if the player stops sneaking after charging is done.
    // This state transitions to CombustState if the player takes damage while charging.
    private class ChargeState implements State {
        private final long startTime;
        private final long warmup;
        private final double playerStartHealth;
        private final boolean instantExplodeIfHit;
        private int currPoint;

        public ChargeState() {
            this.startTime = System.currentTimeMillis();
            this.playerStartHealth = player.getHealth();

            this.instantExplodeIfHit = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.Combustion.InstantExplodeIfHit");
            this.warmup = JedCoreConfig.getConfig(bPlayer).getLong("Abilities.Fire.Combustion.Warmup");
        }

        @Override
        public void update() {
            long time = System.currentTimeMillis();

            boolean charged = time >= this.startTime + warmup;

            if (player.isSneaking()) {
                if (!bPlayer.canBendIgnoreBinds(Combustion.this)) {
                    remove();
                    return;
                }

                playParticleRing(60, 1.75F, 2);

                if (instantExplodeIfHit && player.getHealth() < playerStartHealth) {
                    // Remove and combust at player's location
                    state = new CombustState(player.getLocation(), true);
                    return;
                }

                if (charged) {
                    ParticleEffect.SMOKE_LARGE.display(player.getLocation(), 1, Math.random(), Math.random(), Math.random(), 0.1);
                }
            } else {
                if (charged) {
                    state = new TravelState();
                } else {
                    remove();
                }
            }
        }

        private void playParticleRing(int points, float size, int speed) {
            for (int i = 0; i < speed; ++i) {
                currPoint += 360 / points;

                if (currPoint > 360) {
                    currPoint = 0;
                }

                double angle = currPoint * 3.141592653589793D / 180.0D;
                double x = size * Math.cos(angle);
                double z = size * Math.sin(angle);

                Location loc = player.getLocation().add(x, 1.0D, z);
                playFirebendingParticles(loc, 3, 0.0, 0.0, 0.0);
                ParticleEffect.SMOKE_NORMAL.display(loc, 4, 0.0, 0.0, 0.0, 0.01);
            }
        }
    }

    // This state is used after the player releases a charged Combustion.
    // It's used for moving and rendering the projectile.
    // This state transitions to CombustState when it collides with terrain or an entity.
    private class TravelState implements State {
        private final int range;
        private final boolean explodeOnDeath;
        private final double entityCollisionRadius;
        private Vector direction;
        private int ticks;

        public TravelState() {
            removalPolicy.removePolicyType(SwappedSlotsRemovalPolicy.class);

            Location origin = player.getEyeLocation().clone();
            origin.setY(origin.getY() - 0.8D);
            location = origin.clone();

            range = JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Fire.Combustion.Range");
            explodeOnDeath = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.Combustion.ExplodeOnDeath");
            entityCollisionRadius = JedCoreConfig.getConfig(bPlayer).getDouble("Abilities.Fire.Combustion.EntityCollisionRadius");

            if (explodeOnDeath) {
                removalPolicy.removePolicyType(CannotBendRemovalPolicy.class);
                removalPolicy.removePolicyType(IsDeadRemovalPolicy.class);
            }
        }

        @Override
        public void update() {
            if (explodeOnDeath && player.isDead()) {
                state = new CombustState(location);
                return;
            }

            // Manually handle the region protection check because the CannotBendRemovalPolicy no longer checks it
            // when explodeOnDeath is true. This stops players from firing Combustion and then walking into a
            // protected area.
            if (explodeOnDeath) {
                if (RegionProtection.isRegionProtected(Combustion.this, player.getLocation())) {
                    remove();
                    return;
                }
            }

            direction = player.getEyeLocation().getDirection().normalize();
            ++ticks;

            if (ticks <= range) {
                travel();
            }

            if (ticks >= range) {
                bPlayer.addCooldown(Combustion.this);
                remove();
            }
        }

        private void travel() {
            final boolean authoritative = CooldownSync.isAuthoritative();
            int r = (int) Math.sqrt(range);

            for (int i = 0; i < r; ++i) {
                render();

                Sphere collider = new Sphere(location, entityCollisionRadius);

                boolean hit = CollisionDetector.checkEntityCollisions(player, collider, (entity) -> {
                    if (!authoritative) return true;
                    location = entity.getLocation();
                    state = new CombustState(location);
                    return true;
                });

                if (hit && authoritative) {
                    return;
                }

                if (!MaterialUtil.isTransparent(location.getBlock()) || (isWater(location.getBlock()) && !FireAbility.canPassThroughWater(location.getBlock()))) {
                    state = new CombustState(location);
                    return;
                }

				/*if (AirAbility.isWithinAirShield(location) || FireAbility.isWithinFireShield(location)) {
					state = new CombustState(location);
					return;
				}*/

                location = location.add(direction.clone().multiply(0.2D));
            }
        }

        private void render() {
            if (bPlayer.canUseSubElement(SubElement.BLUE_FIRE)) {
                ParticleEffect.SOUL_FIRE_FLAME.display(location, 1, 0.0, 0.0, 0.0, 0.03);
            } else {
                ParticleEffect.FLAME.display(location, 1, 0.0, 0.0, 0.0, 0.03);
            }
            ParticleEffect.SMOKE_LARGE.display(location, 1, 0.0, 0.0, 0.0F, 0.06);
            ParticleEffect.FIREWORKS_SPARK.display(location, 1, 0.0, 0.0, 0.0F, 0.06);

            location.getWorld().playSound(location, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0F, 0.01F);
        }
    }

    // This state is used for doing the explosion.
    // ChargeState can transition to this state if the player takes damage while charging.
    // TravelState can transition to this state if the projectile collides with terrain, entity, or collidable ability.
    private class CombustState implements State {
        private final long startTime;
        private final long regenTime;
        private boolean waitForRegen;

        public CombustState(Location location) {
            this(location, false);
        }

        public CombustState(Location location, boolean misfire) {
            removalPolicy.removePolicyType(SwappedSlotsRemovalPolicy.class);
            // This stops players from moving into a protected area to bypass the regen wait time.
            removalPolicy.removePolicyType(CannotBendRemovalPolicy.class);

            this.startTime = System.currentTimeMillis();
            this.regenTime = JedCoreConfig.getConfig(bPlayer).getLong("Abilities.Fire.Combustion.RegenTime");
            this.waitForRegen = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.Combustion.WaitForRegen");

            double damage = JedCoreConfig.getConfig(bPlayer).getDouble("Abilities.Fire.Combustion.Damage");
            int fireTick = JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Fire.Combustion.FireTick");
            int power = JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Fire.Combustion.Power");
            boolean damageBlocks = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.Combustion.DamageBlocks");
            boolean regenBlocks = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Fire.Combustion.RegenBlocks");

            ExplosionMethod explosionMethod;
            if (regenBlocks) {
                explosionMethod = new RegenExplosionMethod(damageBlocks, regenTime);
            } else {
                explosionMethod = new PermanentExplosionMethod(damageBlocks);
            }

            // Don't wait for regen if the blocks aren't even being destroyed.
            if (!damageBlocks) {
                waitForRegen = false;
            }

            double modifier = 0;
            if (misfire) {
                modifier = JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Fire.Combustion.MisfireModifier");
            }

            int destroyedCount = explosionMethod.explode(location, power + modifier, damage + modifier, fireTick);

            // Don't wait for regen if nothing was actually destroyed.
            if (destroyedCount <= 0) {
                waitForRegen = false;
            }

            AirAbility.removeAirSpouts(location, power, player);
            WaterAbility.removeWaterSpouts(location, power, player);

            bPlayer.addCooldown(Combustion.this);
        }

        @Override
        public void update() {
            if (!waitForRegen || System.currentTimeMillis() >= (this.startTime + this.regenTime)) {
                remove();
            }
        }
    }

    private abstract class AbstractExplosionMethod implements ExplosionMethod {
        private final boolean destroy;
        private final Random rand = PredictionDeterminism.random(
                Combustion.this.player == null ? null : Combustion.this.player.getUniqueId(),
                Combustion.class.getName() + ":explosion-blocks",
                Combustion.this.getPredictionDeterministicSeed());
        protected List<Material> blocks = Arrays.asList(
                Material.AIR, Material.VOID_AIR, Material.CAVE_AIR, Material.BEDROCK, Material.CHEST, Material.TRAPPED_CHEST, Material.OBSIDIAN,
                Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_PORTAL_FRAME, Material.FIRE,
                Material.WATER, Material.LAVA, Material.DROPPER, Material.FURNACE,
                Material.DISPENSER, Material.HOPPER, Material.BEACON, Material.BARRIER, Material.SPAWNER
        );

        public AbstractExplosionMethod(boolean destroy) {
            this.destroy = destroy;
        }

        public int explode(Location location, double size, double damage, int fireTick) {
            render(location);

            if (!destroy) {
                return 0;
            }

            location.getWorld().createExplosion(location, 0.0F);
            int destroyCount = destroyBlocks(location, (int) size);
            damageEntities(location, size, damage, fireTick);

            return destroyCount;
        }

        private void render(Location location) {
            if (bPlayer.canUseSubElement(SubElement.BLUE_FIRE)) {
                ParticleEffect.SOUL_FIRE_FLAME.display(location, 20, Math.random(), Math.random(), Math.random(), 0.5);
            } else {
                ParticleEffect.FLAME.display(location, 20, Math.random(), Math.random(), Math.random(), 0.5);
            }
            ParticleEffect.SMOKE_LARGE.display(location, 20, Math.random(), Math.random(), Math.random(), 0.5);
            ParticleEffect.FIREWORKS_SPARK.display(location, 20, Math.random(), Math.random(), Math.random(), 0.5);
            ParticleEffect.SMOKE_LARGE.display(location, 20, Math.random(), Math.random(), Math.random());
            ParticleEffect.EXPLOSION_HUGE.display(location, 20, Math.random(), Math.random(), Math.random(), 0.5);

            location.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        }

        private int destroyBlocks(Location location, int size) {
            int count = 0;

            for (Location l : GeneralMethods.getCircle(location, size, size, false, true, 0)) {
                if (!RegionProtection.isRegionProtected(Combustion.this, l)) {
                    if (destroyBlock(l)) {
                        ++count;
                    }
                }
            }

            return count;
        }

        private void damageEntities(Location location, double size, double damage, int fireTick) {
            entityHitLocations = List.of(location.clone());
            entityHitRadius = Math.max(0D, size);
            for (Entity e : GeneralMethods.getEntitiesAroundPoint(location, size)) {
                if (e instanceof LivingEntity) {
                    if (!RegionProtection.isRegionProtected(Combustion.this, e.getLocation())) {
                        DamageHandler.damageEntity(e, damage, Combustion.this);
                        FireTick.set(e, fireTick);
                    }
                }
            }
        }

        protected void placeRandomFire(Location location) {
            int chance = rand.nextInt(3);

            if ((!(location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()).getType().isSolid())) || (chance != 0))
                return;

            location.getBlock().setType(Material.FIRE);
        }

        protected void placeRandomBlock(Location location) {
            int chance = rand.nextInt(3);

            if (!(location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()).getType().isSolid()))
                return;

            Material block = location.getWorld().getBlockAt(location.getBlockX(), location.getBlockY() - 1, location.getBlockZ()).getType();

            if (chance == 0)
                location.getBlock().setType(block);
        }

        // Returns how many blocks were destroyed.
        public abstract boolean destroyBlock(Location location);
    }

    private class RegenExplosionMethod extends AbstractExplosionMethod {
        private final long regenTime;

        public RegenExplosionMethod(boolean destroy, long regenTime) {
            super(destroy);
            this.regenTime = regenTime;
        }

        @Override
        public boolean destroyBlock(Location l) {
            Block block = l.getBlock();

            if (TempBlock.isTempBlock(block)) {
                TempBlock.revertBlock(block, Material.AIR);
            }

            if (!MaterialUtil.isTransparent(block) && !blocks.contains(block.getType()) && !MaterialUtil.isSign(block)) {
                new RegenTempBlock(block, Material.AIR, Material.AIR.createBlockData(), regenTime, false);
                placeRandomBlock(l);
                placeRandomFire(l);

                return true;
            }

            return false;
        }
    }

    private class PermanentExplosionMethod extends AbstractExplosionMethod {
        public PermanentExplosionMethod(boolean destroy) {
            super(destroy);
        }

        @Override
        public boolean destroyBlock(Location l) {
            Block block = l.getBlock();

            if (!MaterialUtil.isTransparent(block) && !blocks.contains(block.getType()) && !MaterialUtil.isSign(block)) {
                Block newBlock = l.getWorld().getBlockAt(l);
                newBlock.setType(Material.AIR);
                placeRandomBlock(l);
                placeRandomFire(l);

                return true;
            }

            return false;
        }
    }
}
