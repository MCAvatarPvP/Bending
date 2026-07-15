package com.projectkorra.projectkorra.firebending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.firebending.util.FireDamageTimer;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.CooldownSync;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class WallOfFire extends FireAbility {

    //	private int damageTick;
    private int intervalTick;
    @Attribute(Attribute.RANGE)
    @DayNightFactor
    private double range;
    @Attribute(Attribute.HEIGHT)
    @DayNightFactor
    private double height;
    @Attribute(Attribute.WIDTH)
    @DayNightFactor
    private double width;
    @Attribute(Attribute.DAMAGE)
    @DayNightFactor
    private double damage;
    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long cooldown;
    private long damageInterval;
    @Attribute(Attribute.DURATION)
    @DayNightFactor
    private long duration;
    private long time;
    private long interval;
    @Attribute(Attribute.FIRE_TICK)
    @DayNightFactor
    private double fireTicks;
    private double maxAngle;
    private Random random;
    private Location origin;
    private List<Block> blocks;
    private Map<Entity, Long> affected;
    private Map<UUID, BoundingBox> previousEntityBounds;
    private Set<UUID> predictedContacts;
    private BoundingBox wofBoundingBox;
    private Vector uLR;
    private Vector vUD;
    private Vector n;
    private double halfW;
    private double halfH;

    public WallOfFire(final Player player) {
        super(player);

        this.maxAngle = getConfig().getDouble("Abilities.Fire.WallOfFire.MaxAngle");
        this.interval = getConfig().getLong("Abilities.Fire.WallOfFire.Interval");
        this.range = getConfig().getDouble("Abilities.Fire.WallOfFire.Range");
        this.height = getConfig().getDouble("Abilities.Fire.WallOfFire.Height");
        this.width = getConfig().getDouble("Abilities.Fire.WallOfFire.Width");
        this.damage = getConfig().getDouble("Abilities.Fire.WallOfFire.Damage");
        this.cooldown = getConfig().getLong("Abilities.Fire.WallOfFire.Cooldown");
        this.damageInterval = getConfig().getLong("Abilities.Fire.WallOfFire.DamageInterval");
        this.duration = getConfig().getLong("Abilities.Fire.WallOfFire.Duration");
        this.fireTicks = getConfig().getDouble("Abilities.Fire.WallOfFire.FireTicks");

        this.random = new Random();
        this.blocks = new ArrayList<>();
        this.affected = new HashMap<>();
        this.previousEntityBounds = new HashMap<>();
        this.predictedContacts = new HashSet<>();

        if (hasAbility(player, WallOfFire.class)) {
            return;
        } else if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        this.origin = GeneralMethods.getTargetedLocation(player, this.range);

        this.time = System.currentTimeMillis();
        final Block block = this.origin.getBlock();
        if (block.isLiquid() || GeneralMethods.isSolid(block)) {
            return;
        }

        final Vector direction = player.getEyeLocation().getDirection();
        final Vector compare = direction.clone();
        compare.setY(0);
        if (compare.lengthSquared() < 1.0E-12
                || Math.abs(direction.angle(compare)) > Math.toRadians(this.maxAngle)) {
            return;
        }
        this.wofBoundingBox = new BoundingBox();

        this.recalculateAttributes();
        this.initializeBlocks();
        this.start();
    }

    private static Vector toLocal(Vector p, Location origin, Vector uLR, Vector vUD, Vector n) {
        Vector o = origin.toVector();
        Vector d = p.clone().subtract(o);
        return new Vector(d.dot(uLR), d.dot(vUD), d.dot(n));
    }

    // Conservative projection of an axis-aligned half-extent into local axes
    private static double projHalfExtent(Vector axis, double hx, double hy, double hz) {
        return Math.abs(axis.getX()) * hx + Math.abs(axis.getY()) * hy + Math.abs(axis.getZ()) * hz;
    }

    private void affect(final Entity entity) {
        if (affected.containsKey(entity)) return;
        // Remote mutation emits an exact hit claim and aborts the remainder of
        // this predicted progress call. Remember that target before the abort
        // so it cannot starve every later entity on subsequent ticks.
        if (!CooldownSync.isAuthoritative()
                && !this.predictedContacts.add(entity.getUniqueId())) return;
        final boolean noStop = entity.getVelocity().lengthSquared() > 0.3;

        if (!noStop) {
            GeneralMethods.setVelocity(this, entity, new Vector(0, 0, 0));
        }

        if (entity instanceof LivingEntity) {
            final Block block = ((LivingEntity) entity).getEyeLocation().getBlock();
            if (TempBlock.isTempBlock(block) && isIce(block)) {
                return;
            }
            DamageHandler.damageEntity(entity, this.player, this.damage, this, false, noStop);
            AirAbility.breakBreathbendingHold(entity);
        }
        entity.setFireTicks((int) (this.fireTicks * 20));
        new FireDamageTimer(entity, this.player, this);
        affected.put(entity, System.currentTimeMillis() + damageInterval);
    }

    private void damage() {
        double radius = this.height;
        if (radius < this.width) {
            radius = this.width;
        }

        radius = radius + 20;
        final List<Entity> entities = GeneralMethods.getEntitiesAroundPoint(this.origin, radius);
        final Set<UUID> observed = new HashSet<>();

        for (final Entity entity : entities) {
            if (entity == this.player) continue;
            if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation())) continue;

            final BoundingBox curBB = entity.getBoundingBox();
            final UUID entityId = entity.getUniqueId();
            final BoundingBox prevBB = this.previousEntityBounds.getOrDefault(entityId, curBB);
            observed.add(entityId);

            final Vector curC = new Vector((curBB.getMinX() + curBB.getMaxX()) * 0.5,
                    (curBB.getMinY() + curBB.getMaxY()) * 0.5,
                    (curBB.getMinZ() + curBB.getMaxZ()) * 0.5);
            final Vector prevC = new Vector((prevBB.getMinX() + prevBB.getMaxX()) * 0.5,
                    (prevBB.getMinY() + prevBB.getMaxY()) * 0.5,
                    (prevBB.getMinZ() + prevBB.getMaxZ()) * 0.5);

            final double hx = (curBB.getMaxX() - curBB.getMinX()) * 0.5;
            final double hy = (curBB.getMaxY() - curBB.getMinY()) * 0.5;
            final double hz = (curBB.getMaxZ() - curBB.getMinZ()) * 0.5;

            Vector p0 = toLocal(prevC, this.origin, uLR, vUD, n);
            Vector p1 = toLocal(curC, this.origin, uLR, vUD, n);

            double ex = projHalfExtent(uLR, hx, hy, hz);
            double ey = projHalfExtent(vUD, hx, hy, hz);
            double ez = projHalfExtent(n, hx, hy, hz);

            double halfT = 0.51;

            double minX = -halfW - ex, maxX = halfW + ex;
            double minY = -halfH - ey, maxY = halfH + ey;
            double minZ = -halfT - ez, maxZ = halfT + ez;

            final boolean intersects = WallOfFireHitGeometry.segmentIntersectsLocalAABB(
                    p0, p1, minX, maxX, minY, maxY, minZ, maxZ);
            this.previousEntityBounds.put(entityId, curBB);
            if (intersects) this.affect(entity);
        }
        this.previousEntityBounds.keySet().retainAll(observed);
    }

    private void display() {
        for (final Block block : this.blocks) {
            if (!this.isTransparent(block)) {
                dryWetBlocks(block, this, ThreadLocalRandom.current().nextInt(5) == 0);
                continue;
            }

            if (random.nextBoolean()) {
                playFirebendingParticles(block.getLocation(), 3, 0.6, 0.6, 0.6);
            }

            emitFirebendingLight(block.getLocation());
            if (this.random.nextInt(7) == 0) {
                playFirebendingSound(block.getLocation());
            }
        }
    }

    private void initializeBlocks() {
        this.recalculateAttributes();

        Vector dir = this.player.getEyeLocation().getDirection().normalize();

        Vector lr = GeneralMethods.getOrthogonalVector(dir, 0, 1).normalize();
        Vector ud = GeneralMethods.getOrthogonalVector(dir, 90, 1).normalize();

        this.n = dir.clone().normalize();

        this.uLR = lr.clone().subtract(n.clone().multiply(lr.dot(n))).normalize();
        this.vUD = ud.clone()
                .subtract(n.clone().multiply(ud.dot(n)))
                .subtract(uLR.clone().multiply(ud.dot(uLR)))
                .normalize();

        if (uLR.clone().crossProduct(vUD).dot(n) < 0) {
            this.uLR.multiply(-1);
        }

        this.halfW = this.width;
        this.halfH = this.height;

        final double w = this.width;
        final double h = this.height;

        for (double i = -w; i <= w; i++) {
            for (double j = -h; j <= h; j++) {
                Location location = this.origin.clone()
                        .add(vUD.clone().multiply(j))   // was orthoud
                        .add(uLR.clone().multiply(i));  // was ortholr
                if (GeneralMethods.isRegionProtectedFromBuild(this, location)) continue;

                Block b = location.getBlock();
                if (!this.blocks.contains(b)) this.blocks.add(b);
            }
        }
    }

    @Override
    public void progress() {
        this.time = System.currentTimeMillis();

        if (this.time > this.getStartTime() + this.duration) {
            this.remove();
            return;
        }
        if (this.time - this.getStartTime() > this.intervalTick * this.interval) {
            this.intervalTick++;
            this.display();
        }
        Iterator<Map.Entry<Entity, Long>> iter = affected.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Entity, Long> entry = iter.next();
            if (this.time > entry.getValue()) {
                iter.remove();
            }
        }
        this.damage();
//		if (this.time - this.getStartTime() > this.damageTick * this.damageInterval) {
//			this.damageTick++;
//			this.damage();
//		}
    }

    @Override
    public void remove() {
        super.remove();
        this.bPlayer.addCooldown(this);
    }

    @Override
    public String getName() {
        return "WallOfFire";
    }

    @Override
    public Location getLocation() {
        return this.origin;
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

    @Override
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();
        for (final Block block : this.blocks) {
            locations.add(block.getLocation());
        }
        return locations;
    }

    //	public int getDamageTick() {
//		return this.damageTick;
//	}
//
//	public void setDamageTick(final int damageTick) {
//		this.damageTick = damageTick;
//	}
//
    public int getIntervalTick() {
        return this.intervalTick;
    }

    public void setIntervalTick(final int intervalTick) {
        this.intervalTick = intervalTick;
    }

    public double getRange() {
        return this.range;
    }

    public void setRange(final int range) {
        this.range = range;
    }

    public double getHeight() {
        return this.height;
    }

    public void setHeight(final int height) {
        this.height = height;
    }

    public double getWidth() {
        return this.width;
    }

    public void setWidth(final int width) {
        this.width = width;
    }

    public double getDamage() {
        return this.damage;
    }

    public void setDamage(final int damage) {
        this.damage = damage;
    }

    public long getDamageInterval() {
        return this.damageInterval;
    }

    public void setDamageInterval(final long damageInterval) {
        this.damageInterval = damageInterval;
    }

    public long getDuration() {
        return this.duration;
    }

    public void setDuration(final long duration) {
        this.duration = duration;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
    }

    public long getInterval() {
        return this.interval;
    }

    public void setInterval(final long interval) {
        this.interval = interval;
    }

    public double getFireTicks() {
        return this.fireTicks;
    }

    public void setFireTicks(final double fireTicks) {
        this.fireTicks = fireTicks;
    }

    public double getMaxAngle() {
        return this.maxAngle;
    }

    public void setMaxAngle(final double maxAngle) {
        this.maxAngle = maxAngle;
    }

    public Location getOrigin() {
        return this.origin;
    }

    public void setOrigin(final Location origin) {
        this.origin = origin;
    }

    public List<Block> getBlocks() {
        return this.blocks;
    }

}
