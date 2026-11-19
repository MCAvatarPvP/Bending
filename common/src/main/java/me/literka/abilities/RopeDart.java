package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Arrow;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.colliders.AABB;
import java.util.UUID;
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
    private UUID deflectedBy;
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

        origin.getWorld().playSound(origin, Sound.ITEM_TRIDENT_THROW, 1, 2);
        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        Location playerLoc = player.getLocation();
        if (player.isSneaking() || playerLoc.getWorld() != origin.getWorld()) {
            removeWithCooldown();
            return;
        }

        if (target == null) {
            if (arrow == null || arrow.isDead() || arrow.getWorld() != playerLoc.getWorld()) {
                removeWithCooldown();
                return;
            }
            Location tip = arrow.getLocation();
            findTargetAlongPath(previousArrowLocation, tip);
            if (target == null) {
                previousArrowLocation = tip.clone();
                progressArrow(playerLoc, tip);
                return;
            }
        }

        if (!canAttach(target) || playerLoc.distanceSquared(target.getLocation()) > maxRangeRemove * maxRangeRemove) {
            removeWithCooldown();
            return;
        }
        Location targetLoc = target.getLocation();
        Location destination = pullYourselfToEnemy ? targetLoc.clone() : playerLoc.clone().add(originVec);
        LivingEntity pulled = pullYourselfToEnemy ? player : target;
        Vector velocity = destination.add(0, 0.05, 0).toVector().subtract(pulled.getLocation().toVector());
        if (velocity.lengthSquared() <= minRangeRemove * minRangeRemove) {
            GeneralMethods.setPredictedVelocity(this, pulled, new Vector());
            removeWithCooldown();
            return;
        }
        if (velocity.lengthSquared() > 1) velocity.normalize().multiply(pullYourselfToEnemy ? pullSpeed : pullSpeedOthers);
        GeneralMethods.setPredictedVelocity(this, pulled, velocity);
        spawnLine(targetLoc.clone().add(0, targetYOffset, 0), playerLoc.clone().add(0, 1, 0));
    }

    private void progressArrow(Location playerLoc, Location tip) {
        double fromCaster = playerLoc.distanceSquared(tip);
        if (fromCaster > maxRangeRemove * maxRangeRemove) {
            removeWithCooldown();
            return;
        }
        if (!retract && origin.distanceSquared(tip) > maxRange * maxRange) {
            GeneralMethods.setVelocity(this, arrow, arrow.getVelocity().multiply(-1));
            retract = true;
        }
        boolean anchored = arrow.isInBlock() || arrow.isInWaterOrBubbleColumn();
        if ((fromCaster <= minRangeRemove * minRangeRemove
                    && (retract || anchored || arrow.isOnGround() || arrow.getVelocity().lengthSquared() < 0.01))
                || (retract && origin.distanceSquared(tip) <= 1)
                || ((anchored || arrow.isOnGround()) && player.getBoundingBox().overlaps(arrow.getBoundingBox()))) {
            removeWithCooldown();
            return;
        }
        Location anchor = tip.clone().add(0, 0.05, 0);
        if (anchored) {
            Vector velocity = anchor.toVector().subtract(playerLoc.toVector());
            if (velocity.lengthSquared() > 1) velocity.normalize().multiply(pullSpeed);
            if (velocity.lengthSquared() > 0) GeneralMethods.setPredictedVelocity(this, player, velocity);
        }
        spawnLine(anchor, playerLoc.clone().add(0, 1, 0));
    }

    private void spawnLine(Location start, Location end) {
        ParticleEffect effect = new ParticleEffect()
                .type(Particle.WAX_ON)
                .count(1)
                .force(true)
                .offset(0.05, 0.05, 0.05)
                .speed(0);
        Vector vec = end.toVector().subtract(start.toVector()).normalize();
        for (int i = 0; i < start.distance(end); i++) {
            Location l = start.clone().add(vec.clone().multiply(i));
            effect.location(l).spawn();
        }
    }

    /** Native contacts and swept bending hitboxes attach to the same owned dart. */
    private void findTargetAlongPath(Location from, Location to) {
        if (from == null || from.getWorld() != to.getWorld()) return;
        Vector delta = to.toVector().subtract(from.toVector());
        double length = delta.length();
        double wall = Double.POSITIVE_INFINITY;
        if (length > 0) {
            var block = from.getWorld().rayTraceBlocks(from, delta.clone().normalize(), length, FluidCollisionMode.NEVER, false);
            if (block != null) wall = block.getHitPosition().subtract(from.toVector()).length() / length;
        }
        var candidates = new AABB(from, to).expand(0.35).getEntities(entity -> entity instanceof LivingEntity);
        LivingEntity nearest = null;
        double first = Double.POSITIVE_INFINITY;
        boolean stillDeflected = false;
        for (Entity entity : candidates) {
            var living = (LivingEntity) entity;
            if (!canAttach(living)) continue;
            double entry = entity.getCombatBoundingBox().expand(0.35).segmentEntry(from.toVector(), delta);
            if (!Double.isFinite(entry) || entry >= wall) continue;
            if (living.getUniqueId().equals(deflectedBy)) {
                stillDeflected = true;
                continue;
            }
            if (entry < first || (entry == first && living.getUniqueId().compareTo(nearest.getUniqueId()) < 0)) {
                nearest = living;
                first = entry;
            }
        }
        if (!stillDeflected) deflectedBy = null;
        if (nearest == null) return;
        if (nearest instanceof Player defender) {
            Deflect deflect = getAbility(defender, Deflect.class);
            if (deflect != null) {
                var hit = new com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent();
                hit.setEntity(arrow);
                hit.setHitEntity(defender);
                deflect.onProjectileHit(hit);
                deflectedBy = defender.getUniqueId();
                return;
            }
        }
        attach(nearest, from.getY() + first * delta.getY());
    }

    private boolean canAttach(LivingEntity candidate) {
        return candidate != null && !candidate.isDead() && candidate.getWorld() == player.getWorld()
                && !candidate.getUniqueId().equals(player.getUniqueId())
                && (!(candidate instanceof Player other) || (other.isOnline() && other.getGameMode() != GameMode.SPECTATOR && player.canSee(other)));
    }

    public static RopeDart forProjectile(Arrow projectile) {
        if (!(projectile.getShooter() instanceof Player shooter)) return null;
        RopeDart dart = getAbility(shooter, RopeDart.class);
        return dart != null && dart.arrow != null && dart.arrow.getUniqueId().equals(projectile.getUniqueId()) ? dart : null;
    }

    public void deflected(Player defender) {
        deflectedBy = defender.getUniqueId();
    }

    public void setTarget(LivingEntity candidate) {
        if (arrow != null && canAttach(candidate)) attach(candidate, arrow.getLocation().getY());
    }

    private void attach(LivingEntity candidate, double hitY) {
        target = candidate;
        targetYOffset = Math.clamp(hitY - candidate.getLocation().getY(), 0, candidate.getEyeHeight());
        arrow.remove();
        arrow = null;
    }

    public boolean cancelsOnHit() {
        return ChiRework.config().getBoolean(oldChi ? "Abilities.RopeDart.OldCancelOnHit" : "Abilities.RopeDart.CancelOnHit");
    }

    public boolean cancelsVelocity(String name, boolean projectile) {
        String setting = projectile ? "CancelVelocityAbilities" : "CancellingAbilities";
        return ChiRework.config().getStringList("Abilities.RopeDart." + (oldChi ? "Old" : "") + setting)
                .stream().anyMatch(name::equalsIgnoreCase);
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
        if (target != null) return target.getLocation().clone().add(0, targetYOffset, 0);
        return arrow == null ? null : arrow.getLocation();
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
