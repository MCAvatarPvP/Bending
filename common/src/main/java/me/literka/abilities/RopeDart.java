package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Ray;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;

public class RopeDart extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private double shootPower;
    private double pullSpeed;
    private double pullSpeedOthers;
    @Attribute(Attribute.RANGE)
    private double maxRange;
    private double maxRangeRemove;
    private double minRangeRemove;
    private boolean pullYourselfToEnemy;

    private boolean retract;
    private Location origin;
    private Vector originVec;
    private Arrow arrow;
    private Location previousArrowLocation;
    private LivingEntity target;
    private double targetYOffset;
    private boolean oldChi;
    private boolean cooldownApplied;

    public RopeDart(Player player) {
        super(player);

        if (player.isSneaking()) return;
        if (bPlayer == null || bPlayer.isOnCooldown(getName())) return;

        oldChi = !bPlayer.getElements().contains(ChiRework.MODERN_CHI);

        if (oldChi) {
            cooldown = ChiRework.config().getLong("Abilities.RopeDart.OldCooldown");
            shootPower = ChiRework.config().getDouble("Abilities.RopeDart.OldShootPower");
            pullSpeed = ChiRework.config().getDouble("Abilities.RopeDart.OldPullSpeed");
            pullSpeedOthers = ChiRework.config().getDouble("Abilities.RopeDart.OldPullSpeedOthers");
            maxRange = ChiRework.config().getDouble("Abilities.RopeDart.OldMaxRange");
            maxRangeRemove = ChiRework.config().getDouble("Abilities.RopeDart.OldMaxRangeRemove");
            minRangeRemove = ChiRework.config().getDouble("Abilities.RopeDart.OldMinRangeRemove");
            pullYourselfToEnemy = ChiRework.config().getBoolean("Abilities.RopeDart.OldPullYourselfToEnemy");
        } else {
            cooldown = ChiRework.config().getLong("Abilities.RopeDart.Cooldown");
            shootPower = ChiRework.config().getDouble("Abilities.RopeDart.ShootPower");
            pullSpeed = ChiRework.config().getDouble("Abilities.RopeDart.PullSpeed");
            pullSpeedOthers = ChiRework.config().getDouble("Abilities.RopeDart.PullSpeedOthers");
            maxRange = ChiRework.config().getDouble("Abilities.RopeDart.MaxRange");
            maxRangeRemove = ChiRework.config().getDouble("Abilities.RopeDart.MaxRangeRemove");
            minRangeRemove = ChiRework.config().getDouble("Abilities.RopeDart.MinRangeRemove");
            pullYourselfToEnemy = ChiRework.config().getBoolean("Abilities.RopeDart.PullYourselfToEnemy");
        }

        origin = player.getEyeLocation();
        originVec = origin.getDirection().setY(0).normalize();

        arrow = player.launchProjectile(Arrow.class);
        arrow.setDamage(0);
        arrow.setShooter(player);
        GeneralMethods.setVelocity(this, arrow, player.getLocation().getDirection().multiply(shootPower));
        arrow.setPickupStatus(Arrow.PickupStatus.DISALLOWED);
        arrow.setMetadata("ropedart", new FixedMetadataValue(ChiRework.plugin, this));
        previousArrowLocation = arrow.getLocation().clone();

        retract = false;

        target = null;

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (target != null && (target.isDead() || (target instanceof Player t && (!t.isOnline() || t.getGameMode() == GameMode.SPECTATOR)))) {
            removeWithCooldown();
            return;
        }

        boolean isTargetAlive = target != null && !target.isDead();

        if (player.isSneaking()) {
            removeWithCooldown();
            return;
        }

        Location arrowLoc = arrow.getLocation();
        if (target == null) {
            findTargetAlongPath(previousArrowLocation, arrowLoc);
        }
        previousArrowLocation = arrowLoc.clone();
        if (!retract && origin.distanceSquared(arrowLoc) > maxRange * maxRange) {
            GeneralMethods.setVelocity(this, arrow, arrow.getVelocity().multiply(-1));
            retract = true;
        }

        Location playerLoc = player.getLocation();
        if (playerLoc.distanceSquared(arrowLoc) > maxRangeRemove * maxRangeRemove) {
            removeWithCooldown();
            return;
        }

        if (playerLoc.distanceSquared(arrowLoc) <= minRangeRemove * minRangeRemove
                && (retract || arrow.isInBlock() || arrow.isInWaterOrBubbleColumn()
                || arrow.isOnGround() || arrow.getVelocity().lengthSquared() < 0.01)) {
            removeWithCooldown();
            return;
        }

        if (retract && origin.distanceSquared(arrowLoc) <= 1) {
            removeWithCooldown();
            return;
        }

        Location targetLoc = isTargetAlive ? target.getLocation() : null;
        Location loc1 = (isTargetAlive
                ? (pullYourselfToEnemy ? targetLoc.clone() : playerLoc.clone().add(originVec))
                : arrowLoc.clone()).add(0, 0.05, 0);
        Location loc2 = isTargetAlive && !pullYourselfToEnemy ? targetLoc : playerLoc;

        if (player.getBoundingBox().overlaps(arrow.getBoundingBox()) && (arrow.isInBlock() || arrow.isInWaterOrBubbleColumn() || arrow.isOnGround())) {
            removeWithCooldown();
            return;
        }

        if (isTargetAlive && loc1.distanceSquared(loc2) <= minRangeRemove * minRangeRemove) {
            GeneralMethods.setVelocity(this, pullYourselfToEnemy ? player : target, new Vector(0, 0, 0));
            removeWithCooldown();
            return;
        }

        if (arrow.isInBlock() || arrow.isInWaterOrBubbleColumn() || isTargetAlive) {
            Vector vec = loc1.toVector().subtract(loc2.toVector());
            if (vec.lengthSquared() > 1)
                vec.normalize().multiply(isTargetAlive && !pullYourselfToEnemy ? pullSpeedOthers : pullSpeed);

            if (vec.lengthSquared() != 0) {
                if (!isTargetAlive || pullYourselfToEnemy) GeneralMethods.setVelocity(this, player, vec);
                else GeneralMethods.setVelocity(this, target, vec);
            }
        }

        spawnLine(
                isTargetAlive ? targetLoc.clone().add(0, targetYOffset, 0) : arrowLoc.clone().add(0, 0.05, 0),
                playerLoc.clone().add(0, 1, 0)
        );
    }

    private void spawnLine(Location start, Location end) {
        ParticleEffect effect = new ParticleEffect()
                .type(Particle.WAX_ON)
                .count(1)
                .force(true)
                .offset(0.05, 0.05, 0.05)
                .speed(9999999);
        Vector vec = end.toVector().subtract(start.toVector()).normalize();
        for (int i = 0; i < start.distance(end); i++) {
            Location l = start.clone().add(vec.clone().multiply(i));
            effect.location(l).spawn();
        }
    }

    /**
     * Projectile hit callbacks are server-only. Running the same swept-box
     * contact test in the ability keeps the predicted dart attached to the
     * same target instead of leaving a client-only arrow flying through it.
     */
    private void findTargetAlongPath(final Location from, final Location to) {
        if (from == null || to == null || from.getWorld() == null || from.getWorld() != to.getWorld()) return;
        final Vector delta = to.toVector().subtract(from.toVector());
        final double distance = delta.length();
        final AABB search = new AABB(from, to).expand(0.4);
        final Ray movement = distance < 1.0E-6 ? null : new Ray(from, delta.clone().normalize(), distance);

        for (final Entity entity : search.getEntities(candidate -> candidate instanceof LivingEntity
                && !candidate.isDead() && !candidate.getUniqueId().equals(player.getUniqueId()))) {
            final BoundingBox hitBox = entity.getBoundingBox().expand(0.35);
            final AABB collider = new AABB(entity.getWorld(), hitBox);
            if (movement == null ? collider.contains(to) : movement.intersects(collider)) {
                setTarget((LivingEntity) entity);
                return;
            }
        }
    }

    public void setTarget(LivingEntity target) {
        if (target == null || player.getUniqueId().equals(target.getUniqueId()) || this.target != null) return;
        this.target = target;
        this.targetYOffset = Math.min(arrow.getLocation().getY() - target.getLocation().getY(), target.getEyeHeight());
        arrow.remove();
    }

    public void removeWithCooldown() {
        if (!cooldownApplied && isStarted() && bPlayer != null) {
            bPlayer.addCooldown(this);
            cooldownApplied = true;
        }
        remove();
    }

    @Override
    public void remove() {
        super.remove();
        if (arrow != null) arrow.remove();
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
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.RopeDart.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "RopeDart";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.RopeDart.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.RopeDart.Instructions");
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return ChiRework.authors + ", __Skye (Concept)";
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }

    @Override
    public boolean isModern() {
        return true;
    }
}
