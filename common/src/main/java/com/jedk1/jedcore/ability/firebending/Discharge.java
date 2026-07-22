package com.jedk1.jedcore.ability.firebending;

import com.jedk1.jedcore.JCMethods;
import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LightningAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.prediction.hit.EntityHitboxProvider;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.colliders.Sphere;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

public class Discharge extends LightningAbility implements AddonAbility, EntityHitboxProvider {
    private final HashMap<Integer, Location> branches = new HashMap<>();
    private final List<Location> entityHitSamples = new ArrayList<>();
    private final Set<UUID> predictedContacts = new HashSet<>();
    private final Random rand;
    private Location location;
    private Vector direction;
    private boolean hit;
    private int spaces;
    private int nextBranchId = 1;
    private double branchSpace;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown, avatarCooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    private boolean slotSwapping;
    @Attribute("CollisionRadius")
    private double entityCollisionRadius;

    public Discharge(Player player) {
        super(player);
        this.rand = PredictionDeterminism.random(player == null ? null : player.getUniqueId(), Discharge.class.getName());

        if (!bPlayer.canBend(this) || hasAbility(player, Discharge.class) || !bPlayer.canLightningbend()) {
            return;
        }

        setFields();

        // Capture both values while prediction is applying the input pose.
        // Initializing location on the first delayed server progress tick made
        // the same seeded branch start from a different point than the client.
        location = player.getEyeLocation().clone();
        direction = location.getDirection().normalize();
        branches.put(nextBranchId++, location.clone());

        if (bPlayer.isAvatarState() || JCMethods.isSozinsComet(player.getWorld())) {
            this.cooldown = avatarCooldown;
        }

        start();
        if (!isRemoved()) {
            bPlayer.addCooldown(this);
        }
    }

    public void setFields() {
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.Discharge.Damage");
        knockback = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.Discharge.Knockback");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.Discharge.Cooldown");
        avatarCooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.Discharge.AvatarCooldown");
        duration = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Fire.Discharge.Duration");
        slotSwapping = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.Discharge.SlotSwapping");
        entityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.Discharge.EntityCollisionRadius");

        branchSpace = 0.2;
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!canBend()) {
            remove();
            return;
        }

        if (System.currentTimeMillis() < (getStartTime() + duration) && !hit) {
            advanceLocation();
        } else {
            remove();
        }
    }

    private boolean canBend() {
        if (!slotSwapping) {
            return bPlayer.canBendIgnoreCooldowns(this);
        } else {
            return bPlayer.canBendIgnoreBindsCooldowns(this);
        }
    }

    private void advanceLocation() {
        if (location == null) {
            Location origin = player.getEyeLocation().clone();
            location = origin.clone();
            branches.put(nextBranchId++, location.clone());
        }

        entityHitSamples.clear();
        final boolean authoritative = CooldownSync.isAuthoritative();
        spaces++;
        if (spaces % 3 == 0) {
            Location fork = branches.get(1);
            if (fork == null) {
                fork = branches.values().stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            }
            if (fork != null) {
                // Branch ids must never be derived from map size: removing an
                // older branch otherwise reuses a live id and silently replaces
                // that branch with null, making predicted Discharge vanish.
                branches.put(nextBranchId++, fork.clone());
            }
        }

        List<Integer> cleanup = new ArrayList<>();

        for (int i : branches.keySet()) {
            Location origin = branches.get(i);

            if (origin != null) {
                Location l = origin.clone();

                if (!isTransparent(l.getBlock())) {
                    cleanup.add(i);
                    continue;
                }

                l.add(createBranch(), createBranch(), createBranch());
                branchSpace += 0.001;

                for (int j = 0; j < 5; j++) {
                    entityHitSamples.add(l.clone());
                    playLightningbendingParticle(l.clone(), 0f, 0f, 0f);

                    if (rand.nextInt(3) == 0) {
                        playLightningbendingSound(location);
                    }

                    Vector vec = l.toVector();

                    final boolean collided = CollisionDetector.checkEntityCollisions(player,
                            new Sphere(l, entityCollisionRadius), (entity) -> {
                        if (RegionProtection.isRegionProtected(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(entity.getName()))) {
                            return true;
                        }

                        if (!authoritative && !predictedContacts.add(entity.getUniqueId())) {
                            return true;
                        }

                        DamageHandler.damageEntity(entity, damage, this);

                        Vector knockbackVector = entity.getLocation().toVector().subtract(vec).normalize().multiply(knockback);
                        GeneralMethods.setVelocity(this, entity, knockbackVector);

                        for (int k = 0; k < 5; k++) {
                            playLightningbendingParticle(entity.getLocation(), (float) Math.random(), (float) Math.random(), (float) Math.random());
                        }

                        playLightningbendingSound(location);
                        return true;
                    });

                    l = l.add(direction.clone().multiply(0.2));
                    if (collided && authoritative) {
                        branches.put(i, l);
                        // A predicted remote position may be stale. The server
                        // owns terminal collision; its removal packet will end
                        // the predicted instance after a confirmed contact.
                        hit = true;
                        return;
                    }
                }

                branches.put(i, l);
            }
        }

        for (int i : cleanup) {
            branches.remove(i);
        }

        cleanup.clear();
    }

    private double createBranch() {
        int i = rand.nextInt(3);

        switch (i) {
            case 0:
                return branchSpace;
            case 2:
                return -branchSpace;
            default:
                return 0.0;
        }
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

    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public List<Location> getLocations() {
        return new ArrayList<>(branches.values());
    }

    @Override
    public double getCollisionRadius() {
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Fire.Discharge.AbilityCollisionRadius");
    }

    @Override
    public List<Location> getEntityHitLocations() {
        if (entityHitSamples.isEmpty()) return getLocations();
        return entityHitSamples.stream().map(Location::clone).toList();
    }

    @Override
    public double getEntityHitRadius() {
        return entityCollisionRadius;
    }

    @Override
    public String getName() {
        return "Discharge";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Fire.Discharge.Description");
    }

    public HashMap<Integer, Location> getBranches() {
        return branches;
    }

    public Vector getDirection() {
        return direction;
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public boolean isHit() {
        return hit;
    }

    public void setHit(boolean hit) {
        this.hit = hit;
    }

    public int getSpaces() {
        return spaces;
    }

    public void setSpaces(int spaces) {
        this.spaces = spaces;
    }

    public double getBranchSpace() {
        return branchSpace;
    }

    public void setBranchSpace(double branchSpace) {
        this.branchSpace = branchSpace;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public long getAvatarCooldown() {
        return avatarCooldown;
    }

    public void setAvatarCooldown(long avatarCooldown) {
        this.avatarCooldown = avatarCooldown;
    }

    public long getDuration() {
        return duration;
    }

    public void setDuration(long duration) {
        this.duration = duration;
    }

    public boolean isSlotSwapping() {
        return slotSwapping;
    }

    public void setSlotSwapping(boolean slotSwapping) {
        this.slotSwapping = slotSwapping;
    }

    public double getEntityCollisionRadius() {
        return entityCollisionRadius;
    }

    public void setEntityCollisionRadius(double entityCollisionRadius) {
        this.entityCollisionRadius = entityCollisionRadius;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Fire.Discharge.Enabled");
    }
}
