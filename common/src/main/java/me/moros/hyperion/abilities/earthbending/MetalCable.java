/*
 *   Copyright 2016, 2017, 2020 Moros <https://github.com/PrimordialMoros>
 *
 * 	  This file is part of Hyperion.
 *
 *    Hyperion is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Hyperion is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with Hyperion.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.MetalAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.MainHand;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.DamageHandler;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.configuration.ConfigManager;
import me.moros.hyperion.util.MaterialCheck;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class MetalCable extends MetalAbility implements AddonAbility {
    private final List<Location> pointLocations = new ArrayList<>();
    private Location location;
    private Location origin;
    private final MetalCableRope rope = new MetalCableRope();
    private Vector flight = new Vector();
    private CableTarget target;
    private BlockDisplay grabbedBlock;
    private BlockData grabbedData;
    private Location blockCenter;
    private Vector blockMotion = new Vector();
    private Vector attachmentOffset = new Vector();
    private Vector extractionDirection = new Vector();
    private double extractionRemaining;
    private boolean launched;
    private double thrownDistance;

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.SPEED)
    private double blockSpeed;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute("RegenDelay")
    private long regenDelay;
    private double speed;
    private double collisionRadius;

    private boolean hasHit;
    private int ticks;

    public MetalCable(Player player) {
        super(player);

        if (player == null || bPlayer == null) return;
        for (final MetalCable active : getAbilities(player, MetalCable.class)) {
            if (!active.isRemoved() && !active.launched) {
                active.attemptLaunchTarget();
                return;
            }
        }

        if (bPlayer == null || !bPlayer.canBend(this)) {
            return;
        }

        damage = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.MetalCable.Damage");
        blockSpeed = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.MetalCable.BlockSpeed");
        cooldown = ConfigManager.getConfig(bPlayer).getLong("Abilities.Earth.MetalCable.Cooldown");
        range = ConfigManager.getConfig(bPlayer).getInt("Abilities.Earth.MetalCable.Range");
        regenDelay = ConfigManager.getConfig(bPlayer).getInt("Abilities.Earth.MetalCable.RegenDelay");
        speed = Math.max(0.1, ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.MetalCable.Speed"));
        collisionRadius = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.MetalCable.CollisionRadius");

        hasHit = false;

        if (launchCable()) {
            start();
            if (isStarted() && !isRemoved()) bPlayer.addCooldown(this);
            else remove();
        }
    }

    public static void attemptDestroy(final Player player) {
        for (final MetalCable ability : getAbilities(MetalCable.class)) {
            if (!ability.launched && ability.location != null && !player.equals(ability.getPlayer())
                    && player.getWorld().equals(ability.location.getWorld())
                    && player.getEyeLocation().distanceSquared(ability.location) <= 9) {
                ability.remove();
                return;
            }
        }
    }

    @Override
    public void progress() {
        if (isRemoved()) return;
        if (location == null || !player.isOnline() || player.isDead()
                || !player.getWorld().equals(location.getWorld())
                || !(launched ? bPlayer.canBendIgnoreBindsCooldowns(this) : bPlayer.canBendIgnoreCooldowns(this))) {
            remove();
            return;
        }
        if (launched) {
            if (++ticks > 80 || grabbedBlock == null || !grabbedBlock.isValid()
                    || thrownDistance > range) { remove(); return; }
            blockMotion.multiply(0.99).setY(blockMotion.getY() - 0.04);
            thrownDistance += blockMotion.length();
            advanceBlock();
            pointLocations.clear();
            if (!isRemoved()) pointLocations.add(blockCenter.clone());
            return;
        }
        if (player.getLocation().distanceSquared(location) > (range + 2.0) * (range + 2.0)) {
            remove();
            return;
        }
        if (!hasHit && !advanceHook()) return;
        if (hasHit) {
            if (grabbedBlock != null) {
                if (!grabbedBlock.isValid()) { remove(); return; }
                location = blockCenter.clone().add(attachmentOffset);
            } else if (target == null || !target.isValid(player)) {
                remove();
                return;
            } else {
                // Refresh the attachment BEFORE calculating the pull or drawing the rope.
                location = target.getLocation();
            }
            final double distance = player.getLocation().distance(location);
            if (grabbedBlock != null) {
                if (extractionRemaining > 0) {
                    // Pull straight out of the source face before steering: a diagonal pull
                    // otherwise clips the neighboring floor/wall blocks on its first tick.
                    final double step = Math.min(0.3, extractionRemaining);
                    blockMotion = extractionDirection.clone().multiply(step);
                    extractionRemaining -= step;
                } else if (player.isSneaking()) {
                    final Location hold = holdLocation();
                    final Vector desired = GeneralMethods.getDirection(blockCenter, hold);
                    desired.multiply(0.6);
                    if (desired.lengthSquared() > 0.64) desired.normalize().multiply(0.8);
                    blockMotion.multiply(0.55).add(desired.multiply(0.45));
                } else {
                    if (distance <= 3) { shatter(); return; }
                    blockMotion.multiply(0.55);
                    pull(player, location, 0.8);
                }
                if (!advanceBlock()) return;
            } else {
                Entity entityToMove = player;
                Location destination = location;
                if (target.getType() == CableTarget.Type.ENTITY && player.isSneaking()) {
                    entityToMove = target.getEntity();
                    destination = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(distance / 2));
                }
                if (distance > 3) pull(entityToMove, destination, 0.8);
                else if (target.getType() == CableTarget.Type.ENTITY) {
                    setTargetVelocity(entityToMove, new Vector());
                    remove();
                    return;
                } else if (distance > 1.5) {
                    pull(player, location, 0.35);
                } else {
                    GeneralMethods.setVelocity(this, player, new Vector(0, 0.5, 0));
                    remove();
                    return;
                }
            }
        }
        visualizeLine();
    }

    public void attemptLaunchTarget() {
        if (launched || isRemoved() || !hasHit || grabbedBlock == null
                && (target == null || target.getType() == CableTarget.Type.BLOCK)) return;
        AbilityActivationManager.markHandled(this);
        final Location eye = player.getEyeLocation();
        final Vector aim = eye.getDirection().normalize().multiply(range);
        final MetalCableCollision.Hit hit = MetalCableCollision.trace(eye, aim, 0, 0.15,
                entity -> canHit(entity) && (target == null || !entity.equals(target.getEntity())));
        final Location destination = hit == null ? eye.clone().add(aim) : hit.position();
        final Vector direction = GeneralMethods.getDirection(grabbedBlock == null ? location : blockCenter, destination)
                .normalize().multiply(blockSpeed).add(new Vector(0, 0.2, 0));
        if (grabbedBlock == null) {
            setTargetVelocity(target.getEntity(), direction);
            remove();
        } else {
            // Keep this ability alive to simulate the thrown display; future casts ignore it.
            launched = true;
            ticks = 0;
            blockMotion = direction;
            rope.remove();
            pointLocations.clear();
            pointLocations.add(blockCenter.clone());
        }
    }

    private void pull(Entity entity, Location destination, double strength) {
        setTargetVelocity(entity, GeneralMethods.getDirection(entity.getLocation(), destination).normalize().multiply(strength));
    }

    private void setTargetVelocity(final Entity entity, final Vector velocity) {
        if (entity instanceof Player) {
            GeneralMethods.setVelocity(this, entity, velocity);
        } else {
            entity.setVelocity(velocity);
        }
    }

    private boolean launchCable() {
        if (range <= 0) return false;
        final Location eye = player.getEyeLocation();
        final Vector aim = eye.getDirection().normalize().multiply(range);
        final MetalCableCollision.Hit hit = MetalCableCollision.trace(eye, aim, 0, 0.15, this::canHit);
        final Location destination = hit == null ? eye.clone().add(aim) : hit.position();
        origin = handLocation();
        location = origin.clone();
        flight = GeneralMethods.getDirection(origin, destination).normalize().multiply(speed);
        if (flight.lengthSquared() < 1.0E-8) return false;
        visualizeLine();
        // visualizeLine can reject the hand position before the ability starts.
        // Starting an already removed cable re-indexes it; remove() is then a
        // no-op, and that dead instance consumes every later click as a throw.
        if (isRemoved()) return false;
        playMetalbendingSound(location);
        return true;
    }

    private Location handLocation() {
        final Location hand = player.getMainHand() == MainHand.RIGHT
                ? GeneralMethods.getRightSide(player.getLocation(), 0.3)
                : GeneralMethods.getLeftSide(player.getLocation(), 0.3);
        hand.setY(player.getEyeLocation().getY() - 0.4);
        return hand;
    }

    private Location holdLocation() {
        final Location eye = player.getEyeLocation();
        final Location desired = eye.clone().add(eye.getDirection().multiply(2.5));
        desired.setY(Math.max(player.getLocation().getY() + 0.65, desired.getY()));
        final Vector direction = GeneralMethods.getDirection(eye, desired);
        final MetalCableCollision.Hit obstruction = MetalCableCollision.trace(eye, direction, 0.48, 0, null);
        return obstruction == null ? desired
                : obstruction.position().clone().subtract(direction.normalize().multiply(0.02));
    }

    private boolean advanceHook() {
        final double remaining = range - origin.distance(location);
        if (remaining <= 1.0E-6) { remove(); return false; }
        final Vector step = flight.clone();
        if (step.length() > remaining) step.normalize().multiply(remaining);
        final MetalCableCollision.Hit hit = MetalCableCollision.trace(location, step, 0, 0.22, this::canHit);
        if (hit == null) location.add(step);
        else if (hit.entity() != null) attachEntity(hit.entity(), hit.position());
        else if (hit.block() != null && !hit.block().isLiquid()) attachBlock(hit.block(), hit.position());
        else remove();
        return !isRemoved();
    }

    private boolean canHit(Entity entity) {
        return entity instanceof LivingEntity && !(entity instanceof ArmorStand) && !entity.isDead()
                && !entity.getUniqueId().equals(player.getUniqueId())
                && (!(entity instanceof Player other) || other.isOnline()
                && other.getGameMode() != com.projectkorra.projectkorra.platform.mc.GameMode.SPECTATOR
                && !Commands.invincible.contains(other.getName()));
    }

    private boolean advanceBlock() {
        final MetalCableCollision.Hit hit = MetalCableCollision.trace(blockCenter, blockMotion, 0.47, 0.47, this::canHit);
        if (hit != null) {
            blockCenter = hit.position().clone().subtract(blockMotion.clone().normalize().multiply(0.002));
            location = blockCenter.clone().add(attachmentOffset);
            if (hit.entity() instanceof LivingEntity entity
                    && !RegionProtection.isRegionProtected(this, entity.getLocation())) {
                DamageHandler.damageEntity(entity, damage, this);
            }
            if (launched || hit.entity() != null || hit.block() == null || hit.block().isLiquid()) {
                shatter();
                return false;
            }
            // A held block rests against obstacles; only a thrown block shatters on terrain.
            blockMotion.zero();
            extractionRemaining = 0;
            updateBlockDisplay();
            return true;
        }
        blockCenter.add(blockMotion);
        if (RegionProtection.isRegionProtected(this, blockCenter)) { remove(); return false; }
        location = blockCenter.clone().add(attachmentOffset);
        updateBlockDisplay();
        return true;
    }

    private void updateBlockDisplay() {
        // Keep the grabbed block upright, so the original face attachment never drifts.
        grabbedBlock.setTransformation(new Transformation(new Vector3f(-0.47F, -0.47F, -0.47F),
                new Quaternionf(), new Vector3f(0.94F), new Quaternionf()));
        final Location position = blockCenter.clone();
        position.setYaw(0);
        position.setPitch(0);
        grabbedBlock.teleport(position);
    }

    private void shatter() {
        if (grabbedData != null && blockCenter != null) {
            ParticleEffect.BLOCK_CRACK.display(blockCenter, 8, 0.25, 0.25, 0.25, 0.06, grabbedData);
            ParticleEffect.BLOCK_DUST.display(blockCenter, 4, 0.2, 0.2, 0.2, 0, grabbedData);
        }
        remove();
    }

    private void visualizeLine() {
        final Location hand = handLocation();
        final Vector line = GeneralMethods.getDirection(hand, location);
        if (line.lengthSquared() > (range + 2.0) * (range + 2.0)
                || RegionProtection.isRegionProtected(this, location)) { remove(); return; }
        // Ignore the final few centimetres occupied by the hook itself.
        if (line.lengthSquared() > 0.01) {
            line.multiply(Math.max(0, (line.length() - 0.05) / line.length()));
            if (MetalCableCollision.trace(hand, line, 0, 0, null) != null) { remove(); return; }
        }
        pointLocations.clear();
        pointLocations.addAll(rope.update(hand, location, hasHit ? line : flight, hasHit));
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Earth.MetalCable.Enabled");
    }

    @Override
    public String getName() {
        return "MetalCable";
    }

    @Override
    public String getDescription() {
        return ConfigManager.getConfig(bPlayer).getString("Abilities.Earth.MetalCable.Description");
    }

    @Override
    public String getAuthor() {
        return Hyperion.getAuthor();
    }

    @Override
    public String getVersion() {
        return Hyperion.getVersion();
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
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public List<Location> getLocations() {
        return pointLocations;
    }

    @Override
    public boolean isCollidable() {
        return location != null && !isRemoved();
    }

    @Override
    public double getCollisionRadius() {
        return collisionRadius;
    }

    @Override
    public void handleCollision(Collision collision) {
        collision.setRemovingSecond(collision.getAbilitySecond() instanceof MetalCable);
        super.handleCollision(collision);
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void remove() {
        if (isRemoved()) return;
        rope.remove();
        if (grabbedBlock != null) grabbedBlock.remove();
        pointLocations.clear();
        super.remove();
    }

    public void setHitBlock(Block block) {
        if (hasHit || isRemoved()) return;
        final Location from = location == null ? handLocation() : location;
        final Vector direction = GeneralMethods.getDirection(from, block.getBoundingBox().getCenter().toLocation(block.getWorld()));
        final MetalCableCollision.Hit hit = MetalCableCollision.trace(from, direction, 0, 0, null);
        if (hit != null && block.equals(hit.block())) attachBlock(block, hit.position());
    }

    private void attachBlock(Block block, Location contact) {
        if (RegionProtection.isRegionProtected(player, block.getLocation())) {
            remove();
            return;
        }
        location = contact.clone();
        if (player.isSneaking() && !MaterialCheck.isUnbreakable(block) && !TempBlock.isTempBlock(block)) {
            final BlockData data = block.getBlockData().clone();
            extractionDirection = outwardNormal(block, contact);
            extractionRemaining = 1.02;
            blockCenter = block.getLocation().add(0.5, 0.5, 0.5);
            // The displayed block is slightly inset to avoid scraping adjacent terrain.
            attachmentOffset = contact.toVector().subtract(blockCenter.toVector()).multiply(0.94);
            grabbedData = data;
            new TempBlock(block, Material.AIR.createBlockData(), Math.max(1, regenDelay), this);
            if (!block.isEmpty()) { remove(); return; }
            grabbedBlock = MetalCableRope.spawn(blockCenter, data);
            updateBlockDisplay();
            location = blockCenter.clone().add(attachmentOffset);
        } else {
            target = new CableTarget(block, contact);
        }
        hasHit = true;
    }

    private static Vector outwardNormal(Block block, Location contact) {
        final BoundingBox box = block.getBoundingBox();
        final double[] distances = {Math.abs(contact.getX() - box.getMinX()), Math.abs(contact.getX() - box.getMaxX()),
                Math.abs(contact.getY() - box.getMinY()), Math.abs(contact.getY() - box.getMaxY()),
                Math.abs(contact.getZ() - box.getMinZ()), Math.abs(contact.getZ() - box.getMaxZ())};
        int face = 0;
        for (int i = 1; i < distances.length; i++) if (distances[i] < distances[face]) face = i;
        return switch (face) {
            case 0 -> new Vector(-1, 0, 0);
            case 1 -> new Vector(1, 0, 0);
            case 2 -> new Vector(0, -1, 0);
            case 3 -> new Vector(0, 1, 0);
            case 4 -> new Vector(0, 0, -1);
            default -> new Vector(0, 0, 1);
        };
    }

    public void setHitEntity(Entity entity) {
        if (hasHit || isRemoved() || !canHit(entity)) return;
        attachEntity(entity, entity.getBoundingBox().getCenter().toLocation(entity.getWorld()));
    }

    private void attachEntity(Entity entity, Location contact) {
        if (RegionProtection.isRegionProtected(player, entity.getLocation())) {
            remove();
            return;
        }
        // Project the padded contact onto the actual body rather than floating beside it.
        final BoundingBox bounds = entity.getBoundingBox();
        location = new Location(entity.getWorld(),
                Math.max(bounds.getMinX(), Math.min(bounds.getMaxX(), contact.getX())),
                Math.max(bounds.getMinY(), Math.min(bounds.getMaxY(), contact.getY())),
                Math.max(bounds.getMinZ(), Math.min(bounds.getMaxZ(), contact.getZ())));
        target = new CableTarget(entity, location);
        hasHit = true;
    }

    public boolean hasRequiredInv() {
        Set<Material> materials = ConfigManager.getConfig(bPlayer)
                .getStringList("Abilities.Earth.MetalCable.RequiredItems").stream()
                .map(Material::getMaterial)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (materials.isEmpty()) return true;
        return materials.stream().anyMatch(player.getInventory()::contains);
    }

    public static class CableTarget {
        private final Type type;
        private final Entity entity;
        private final Block block;
        private final Material material;
        private final Vector offset;
        private final Location anchor;
        public CableTarget(Entity entity) {
            this(entity, entity.getBoundingBox().getCenter().toLocation(entity.getWorld()));
        }

        private CableTarget(Entity entity, Location contact) {
            block = null;
            material = null;
            this.entity = entity;
            offset = contact.toVector().subtract(entity.getLocation().toVector());
            anchor = null;
            type = Type.ENTITY;
        }

        public CableTarget(Block block) {
            this(block, block.getBoundingBox().getCenter().toLocation(block.getWorld()));
        }

        private CableTarget(Block block, Location contact) {
            entity = null;
            this.block = block;
            material = block.getType();
            anchor = contact.clone();
            offset = null;
            type = Type.BLOCK;
        }

        public Type getType() {
            return type;
        }

        public Entity getEntity() {
            return entity;
        }

        public Block getBlock() {
            return block;
        }

        private Location getLocation() {
            return type == Type.ENTITY ? entity.getLocation().add(offset) : anchor.clone();
        }

        public boolean isValid(Player p) {
            if (type == Type.ENTITY) {
                return entity != null && entity.isValid() && !entity.isDead()
                        && (!(entity instanceof Player other) || other.isOnline())
                        && entity.getWorld().equals(p.getWorld());
            } else {
                return block.getType() == material;
            }
        }

        private enum Type {ENTITY, BLOCK}
    }
}
