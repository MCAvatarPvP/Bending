package hackathonpack.air;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import hackathonpack.UtilityMethods;

public class Boid {
    private final Player player;
    private final AirSpray airSpray;
    private final Location location;
    private final double lineOfSightRange = 2;
    private final double alignmentPower;
    private final double seperationPower;
    private final double cohesionPower;
    private final double playerDirectionPower;
    private final double knockbackPower;
    private final double damage;
    private double speed = 1;
    private double pushPower = 0.01;
    private double range;
    private int ticksLived;
    private Vector direction;

    public Boid(final Player player, final AirSpray airSpray, final Location loc, final Vector dir, final double damage, final double range) {
        this.player = player;
        this.airSpray = airSpray;
        this.location = loc;
        this.direction = dir.clone();
        this.damage = damage;
        this.range = range;
        this.alignmentPower = airSpray.getAlignmentPower();
        this.seperationPower = airSpray.getSeperationPower();
        this.cohesionPower = airSpray.getCohesionPower();
        this.playerDirectionPower = airSpray.getPlayerDirectionPower();
        this.knockbackPower = airSpray.getKnockbackPower();
    }

    public void update() {
        if (this.range <= 0 || GeneralMethods.isSolid(this.location.getBlock())) {
            this.ticksLived = this.airSpray.getMaximumTick();
        }
        if (this.ticksLived > this.airSpray.getMaximumTick()) {
            return;
        }
        this.ticksLived++;
        this.location.add(this.direction.clone().normalize().multiply(this.speed));
        this.speed -= 0.01;
        this.range -= this.direction.length();
        final Vector newDirection = this.direction.clone()
                .add(seperationVector())
                .add(alignmentVector())
                .add(cohesionVector().multiply(this.cohesionPower))
                .add(this.player.getLocation().getDirection().multiply(this.playerDirectionPower));
        this.direction.add(newDirection.subtract(this.direction).multiply(0.75)).add(new Vector(0, -0.01, 0));
        this.pushPower += 0.005;
    }

    public void show() {
        if (this.ticksLived > this.airSpray.getMaximumTick()) {
            return;
        }
        AirAbility.playAirbendingParticles(BendingPlayer.getBendingPlayer(this.player), this.location, 1, 0.075F, 0.075F, 0.075F);
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, 1.5)) {
            if (entity instanceof LivingEntity living && !entity.equals(this.player)) {
                if (UtilityMethods.checkBlocks(living)) {
                    DamageHandler.damageEntity(entity, this.damage, this.airSpray);
                }
                final Vector alignment = alignmentVector();
                if (alignment.lengthSquared() > 0) {
                    entity.setVelocity(entity.getVelocity().add(alignment.normalize().multiply(this.knockbackPower)));
                }
            }
        }
    }

    public Vector seperationVector() {
        final Vector seperation = new Vector();
        int hitForeignSpray = 0;
        for (final AirSpray spray : CoreAbility.getAbilities(AirSpray.class)) {
            if (!spray.getPlayer().getWorld().equals(this.player.getWorld())) {
                continue;
            }
            for (final Boid boid : spray.getBoids()) {
                if (boid != this && boid.getLocation().distance(this.location) <= this.lineOfSightRange) {
                    seperation.add(calculatePushVector(boid, spray.getPlayer().equals(this.player) ? this.seperationPower : 25));
                    if (!spray.getPlayer().equals(this.player)) {
                        hitForeignSpray = 1;
                    }
                }
            }
        }
        if (hitForeignSpray == 1) {
            this.ticksLived += this.airSpray.getMaximumTick() / 3;
        }
        int hitBlock = 0;
        for (final Block block : GeneralMethods.getBlocksAroundPoint(this.location, this.lineOfSightRange - 0.5)) {
            if (GeneralMethods.isSolid(block) || block.isLiquid()) {
                seperation.add(calculatePushVector(block, 1));
                hitBlock = 1;
            }
        }
        if (hitBlock == 1) {
            this.ticksLived += this.airSpray.getMaximumTick() / 6;
        }
        return seperation;
    }

    public Vector alignmentVector() {
        final Vector alignment = new Vector();
        for (final Boid boid : this.airSpray.getBoids()) {
            if (boid.getLocation().distance(this.location) <= this.lineOfSightRange) {
                alignment.add(boid.getDirection());
            }
        }
        return alignment.lengthSquared() == 0 ? alignment : alignment.normalize().multiply(this.alignmentPower);
    }

    public Vector cohesionVector() {
        final Location sum = new Location(this.player.getWorld(), 0, 0, 0);
        int count = 0;
        for (final Boid boid : this.airSpray.getBoids()) {
            if (boid.getLocation().distance(this.location) <= this.lineOfSightRange) {
                sum.add(boid.getLocation());
                count++;
            }
        }
        if (count == 0) {
            return new Vector();
        }
        sum.setX(sum.getX() / count);
        sum.setY(sum.getY() / count);
        sum.setZ(sum.getZ() / count);
        final Vector vector = sum.toVector().subtract(this.location.toVector());
        return vector.lengthSquared() == 0 ? new Vector() : vector.normalize();
    }

    public Vector calculatePushVector(final Boid boid, final double power) {
        final double push = this.pushPower * (1 / (boid.getLocation().distanceSquared(this.location) + 0.1)) * power;
        return this.location.toVector().subtract(boid.getLocation().toVector()).multiply(push);
    }

    public Vector calculatePushVector(final Block block, final double power) {
        final double push = 0.75 * (1 / (block.getLocation().clone().add(0.5, 0.5, 0.5).distanceSquared(this.location) + 0.1)) * power;
        return this.location.toVector().subtract(block.getLocation().toVector()).multiply(push);
    }

    public int getTicksLived() {
        return this.ticksLived;
    }

    public Location getLocation() {
        return this.location;
    }

    public Vector getDirection() {
        return this.direction;
    }
}
