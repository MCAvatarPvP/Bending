package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ComboAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.ability.util.ComboManager.AbilityInformation;
import com.projectkorra.projectkorra.ability.util.ComboUtil;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.authority.AuthoritativeEffects;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pulls surrounding earth into a close shell, then bursts it on sneak release. */
public class EarthShell extends EarthAbility implements ComboAbility {
    private static final String CONFIG = "Abilities.Earth.EarthShell.";
    private static final double BOUNDARY_EPSILON = 1.0E-6;
    private final List<Placement> placements = new ArrayList<>();
    private final List<TempBlock> shell = new ArrayList<>();
    private final List<TempBlock> sourceHoles = new ArrayList<>();
    private Location center;
    private String holdingAbility;
    private BoundingBox interior;
    private int nextPlacement;
    private int formationTicks;
    private int layerInterval;
    private int baseY;
    private boolean placedEarth;
    private boolean excavateSources;
    private long airStunDuration;
    private double shardHitRadius;

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.RANGE)
    private double burstRange;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;

    public EarthShell(final Player player) {
        super(player);
        if (player == null || bPlayer == null || hasAbility(player, EarthShell.class)) return;
        if (!isEnabled() || !bPlayer.canBendIgnoreBinds(this)) {
            return;
        }

        center = player.getLocation().clone();
        holdingAbility = bPlayer.getBoundAbilityName();
        excavateSources = bPlayer.areSourceHolesOn();
        cooldown = Math.max(0, getConfig().getLong(CONFIG + "Cooldown", 7000));
        duration = Math.max(50, getConfig().getLong(CONFIG + "Duration", 8000));
        burstRange = Math.max(0, getConfig().getDouble(CONFIG + "BurstRange", 12));
        airStunDuration = Math.max(0, getConfig().getLong(CONFIG + "AirStunDuration", 3000));
        shardHitRadius = Math.max(0.1, Math.min(2.5, getConfig().getDouble(CONFIG + "ShardHitRadius", 0.85)));
        knockback = Math.max(0, getConfig().getDouble(CONFIG + "Knockback", 4));
        layerInterval = Math.max(1, getConfig().getInt(CONFIG + "LayerIntervalTicks", 2));
        final int sourceRadius = Math.max(2, Math.min(8, getConfig().getInt(CONFIG + "SourceRadius", 4)));
        final double maxHeightAboveGround = Math.max(0, Math.min(8,
                getConfig().getDouble(CONFIG + "MaxHeightAboveGround", 2)));
        final Block ground = findGround(maxHeightAboveGround);
        if (ground == null) return;
        // Anchor the walls to the ground while the roof follows the caster's height.
        baseY = (int) Math.floor(ground.getBoundingBox().getMaxY() + BOUNDARY_EPSILON);

        // Keep standing headroom even when the initial input exposes a crouched pose.
        final BoundingBox body = player.getBoundingBox();
        // Touching a wall face must not expand the interior into the wall's cell.
        final int minX = (int) Math.floor(body.getMinX() + BOUNDARY_EPSILON);
        final int maxX = (int) Math.floor(body.getMaxX() - BOUNDARY_EPSILON);
        final int minZ = (int) Math.floor(body.getMinZ() + BOUNDARY_EPSILON);
        final int maxZ = (int) Math.floor(body.getMaxZ() - BOUNDARY_EPSILON);
        final int roofY = (int) Math.ceil(Math.max(body.getMaxY(), center.getY() + 1.8));
        if (roofY >= player.getWorld().getMaxHeight()) {
            return;
        }
        interior = new BoundingBox(new Vector(minX, baseY, minZ), new Vector(maxX + 1, roofY, maxZ + 1));

        final List<Block> destinations = new ArrayList<>();
        for (int y = baseY; y <= roofY; y++) {
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    final boolean edgeX = x < minX || x > maxX;
                    final boolean edgeZ = z < minZ || z > maxZ;
                    // A closed perimeter below a rounded cap, with the cap's corners omitted.
                    if (y < roofY ? !edgeX && !edgeZ : edgeX && edgeZ) continue;
                    final Block block = center.getWorld().getBlockAt(x, y, z);
                    // Walls complete this side of the shell without being bent or
                    // replaced, including non-earth materials and other abilities' walls.
                    if (GeneralMethods.isSolid(block)) continue;
                    if (!canPlace(block)) {
                        return;
                    }
                    destinations.add(block);
                }
            }
        }

        final List<Block> sources = new ArrayList<>();
        for (int x = center.getBlockX() - sourceRadius; x <= center.getBlockX() + sourceRadius; x++) {
            for (int z = center.getBlockZ() - sourceRadius; z <= center.getBlockZ() + sourceRadius; z++) {
                // Never excavate any of the cells supporting the caster.
                if (x >= minX && x <= maxX && z >= minZ && z <= maxZ) continue;
                if (Math.hypot(x + 0.5 - center.getX(), z + 0.5 - center.getZ()) > sourceRadius) continue;
                for (int y = ground.getY(); y >= ground.getY() - 2 && y >= center.getWorld().getMinHeight(); y--) {
                    final Block source = center.getWorld().getBlockAt(x, y, z);
                    if (usableSource(source) && replaceable(source.getRelative(BlockFace.UP))) {
                        sources.add(source);
                        break;
                    }
                }
            }
        }
        // When source holes are off, the same nearby earth can supply multiple
        // shell blocks. Requiring 21-36 separate surface blocks silently rejected
        // ordinary terrain despite no source material being removed.
        if (!excavateSources && sources.isEmpty()) sources.add(ground);
        if (sources.isEmpty() || excavateSources && sources.size() < destinations.size()) {
            return;
        }
        if (destinations.isEmpty()) return;
        // Stable order on Paper and the predicting client, with earth taken from nearby first.
        sources.sort(Comparator.comparingDouble(block -> block.getLocation().distanceSquared(center)));
        for (int i = 0; i < destinations.size(); i++) {
            final Block source = sources.get(i % sources.size());
            placements.add(new Placement(destinations.get(i), source, source.getBlockData().clone()));
        }
        start();
        if (isStarted() && !isRemoved()) {
            // The combo consumes the preparatory Shockwave charge, so releasing
            // the shell cannot also fire a normal Shockwave.
            final Shockwave shockwave = getAbility(player, Shockwave.class);
            if (shockwave != null) shockwave.remove();
            formLayer();
        }
    }

    public static boolean release(final Player player) {
        final EarthShell active = getAbility(player, EarthShell.class);
        if (active == null) return false;
        // Apply this edge immediately, before a second sneak input can overtake it.
        AbilityActivationManager.markHandled(active);
        active.release();
        return true;
    }

    @Override
    public Object createNewComboInstance(final Player player) {
        return new EarthShell(player);
    }

    @Override
    public ArrayList<AbilityInformation> getCombination() {
        final List<String> configured = ConfigManager.defaultConfig.get().getStringList(CONFIG + "Combination");
        return ComboUtil.generateCombinationFromList(this, configured.isEmpty()
                ? List.of("Shockwave:SHIFT_DOWN", "EarthBlast:LEFT_CLICK") : configured);
    }

    private boolean usableSource(final Block block) {
        return (!TempBlock.isTempBlock(block) || isBendableEarthTempBlock(block)) && !block.getState().hasBlockEntity()
                && GeneralMethods.isSolid(block) && isEarthbendable(block)
                && !RegionProtection.isRegionProtected(this, block.getLocation());
    }

    private Block findGround(final double maxHeight) {
        final int firstY = (int) Math.floor(center.getY() - BOUNDARY_EPSILON);
        final int lastY = Math.max(center.getWorld().getMinHeight(),
                (int) Math.floor(center.getY() - maxHeight) - 1);
        for (int y = firstY; y >= lastY; y--) {
            final Block block = center.getWorld().getBlockAt(center.getBlockX(), y, center.getBlockZ());
            // Do not source through water or a non-earth floor to earth underneath it.
            if (block.isLiquid()) return null;
            if (!GeneralMethods.isSolid(block)) continue;
            final double height = center.getY() - block.getBoundingBox().getMaxY();
            return usableSource(block) && height <= maxHeight + BOUNDARY_EPSILON ? block : null;
        }
        return null;
    }

    private boolean replaceable(final Block block) {
        return !TempBlock.isTempBlock(block) && !block.getState().hasBlockEntity()
                && !block.isLiquid() && !GeneralMethods.isSolid(block);
    }

    private boolean canPlace(final Block block) {
        if (!replaceable(block)
                || RegionProtection.isRegionProtected(this, block.getLocation())) return false;
        final BoundingBox cell = new BoundingBox(block.getLocation().add(0.001, 0.001, 0.001).toVector(),
                block.getLocation().add(0.999, 0.999, 0.999).toVector());
        return block.getWorld().getNearbyEntities(cell, entity -> entity instanceof LivingEntity
                && !entity.isDead()).isEmpty();
    }

    private boolean canContinue() {
        if (!player.isOnline() || player.isDead() || !isEnabled()
                || !bPlayer.canBendIgnoreBindsCooldowns(this)
                || !holdingAbility.equals(bPlayer.getBoundAbilityName()) || !center.getWorld().equals(player.getWorld())
                || System.currentTimeMillis() - getStartTime() >= duration) return false;
        final BoundingBox body = player.getBoundingBox();
        return body.getMinX() >= interior.getMinX() - BOUNDARY_EPSILON
                && body.getMaxX() <= interior.getMaxX() + BOUNDARY_EPSILON
                && body.getMinZ() >= interior.getMinZ() - BOUNDARY_EPSILON
                && body.getMaxZ() <= interior.getMaxZ() + BOUNDARY_EPSILON
                && body.getMinY() >= baseY - 0.1 && body.getMaxY() <= interior.getMaxY()
                && !RegionProtection.isRegionProtected(this, center);
    }

    @Override
    public void progress() {
        if (isRemoved() || !isStarted()) return;
        if (!canContinue()) {
            remove();
            return;
        }
        if (!player.isSneaking()) {
            release();
            return;
        }
        if (++formationTicks % layerInterval == 0) formLayer();
    }

    private void formLayer() {
        if (nextPlacement >= placements.size()) return;
        final int layerY = placements.get(nextPlacement).destination().getY();
        while (nextPlacement < placements.size() && placements.get(nextPlacement).destination().getY() == layerY) {
            final Placement placement = placements.get(nextPlacement++);
            if (!canPlace(placement.destination()) || !usableSource(placement.source())
                    || !placement.source().getBlockData().getAsString().equals(placement.data().getAsString())) {
                remove();
                return;
            }
            final TempBlock block = new TempBlock(placement.destination(), placement.data(), this).setCanSuffocate(false);
            shell.add(block);
            if (block.isReverted()) {
                remove();
                return;
            }
            placedEarth = true;
            if (excavateSources && bPlayer.areSourceHolesOn()) {
                sourceHoles.add(new TempBlock(placement.source(), Material.AIR.createBlockData(), this));
            }
            final Location from = placement.source().getLocation().add(0.5, 1, 0.5);
            final Vector step = placement.destination().getLocation().add(0.5, 0.5, 0.5)
                    .toVector().subtract(from.toVector()).multiply(0.25);
            for (int i = 0; i < 4; i++) {
                ParticleEffect.BLOCK_DUST.display(from.add(step), 3, 0.12, 0.12, 0.12, placement.data());
            }
        }
        playEarthbendingSound(center);
    }

    public void release() {
        if (isRemoved() || !isStarted()) return;
        if (!canContinue() || !placedEarth) {
            remove();
            return;
        }
        final List<EarthShellBurst.Piece> pieces = new ArrayList<>();
        for (final TempBlock block : shell) {
            if (!block.isReverted()) {
                pieces.add(new EarthShellBurst.Piece(block.getBlock().getLocation().add(0.5, 0.5, 0.5),
                        block.getBlock().getBlockData().clone()));
            }
        }
        // Restore the shell and source terrain before checking or pushing targets.
        remove();
        EarthShellBurst.play(player, center, pieces, burstRange, shardHitRadius, this::hit);
        playEarthbendingSound(center);
    }

    private boolean hit(final LivingEntity entity, final Location contact) {
        if (RegionProtection.isRegionProtected(this, contact)
                || RegionProtection.isRegionProtected(this, entity.getLocation())
                || GeneralMethods.isObstructed(contact, entity.getEyeLocation())) return false;
        final Vector direction = entity.getLocation().toVector().subtract(center.toVector()).setY(0);
        if (direction.lengthSquared() < 0.0001) direction.copy(player.getLocation().getDirection()).setY(0);
        if (direction.lengthSquared() < 0.0001) direction.setX(1);
        direction.normalize().multiply(knockback).setY(entity.getVelocity().getY());
        GeneralMethods.setVelocity(this, entity, direction);
        if (entity instanceof Player target) {
            AuthoritativeEffects.run(() -> disableAirMoves(target));
        }
        return true;
    }

    private void disableAirMoves(final Player target) {
        final BendingPlayer air = BendingPlayer.getBendingPlayer(target);
        if (airStunDuration <= 0 || air == null || !air.hasElement(Element.AIR)) return;
        // Preserve existing cooldowns even when interrupting the scooter adds its own.
        final long until = System.currentTimeMillis() + airStunDuration;
        final long blastUntil = Math.max(until, cooldownEnd(air, "AirBlast"));
        final long scooterUntil = Math.max(until, cooldownEnd(air, "AirScooter"));
        for (final AirBlast blast : new ArrayList<>(getAbilities(target, AirBlast.class))) blast.remove();
        final AirScooter scooter = getAbility(target, AirScooter.class);
        if (scooter != null) {
            scooter.stunned = true;
            scooter.remove();
        }
        extendCooldown(air, "AirBlast", blastUntil);
        extendCooldown(air, "AirScooter", scooterUntil);
    }

    private static long cooldownEnd(final BendingPlayer bender, final String ability) {
        final var existing = bender.getCooldowns().get(ability);
        return existing == null ? 0 : existing.getCooldown();
    }

    private static void extendCooldown(final BendingPlayer bender, final String ability, final long until) {
        if (cooldownEnd(bender, ability) < until) {
            bender.addCooldown(ability, until - System.currentTimeMillis());
        }
    }

    @Override
    public void remove() {
        if (isRemoved()) return;
        for (final TempBlock block : shell) block.revertBlock();
        for (final TempBlock block : sourceHoles) block.revertBlock();
        shell.clear();
        sourceHoles.clear();
        if (placedEarth) bPlayer.addCooldown(this);
        super.remove();
    }

    @Override public String getName() { return "EarthShell"; }
    @Override public Location getLocation() { return center; }
    @Override public long getCooldown() { return cooldown; }
    @Override public boolean isSneakAbility() { return true; }
    @Override public boolean isHarmlessAbility() { return false; }

    private record Placement(Block destination, Block source, BlockData data) { }
}
