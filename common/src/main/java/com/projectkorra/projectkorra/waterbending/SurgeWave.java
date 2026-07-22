package com.projectkorra.projectkorra.waterbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.WaterAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.firebending.FireBlast;
import com.projectkorra.projectkorra.platform.mc.*;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.BlockSource;
import com.projectkorra.projectkorra.util.ClickType;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.plant.PlantRegrowth;
import com.projectkorra.projectkorra.waterbending.util.WaterReturn;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SurgeWave extends WaterAbility {

    private static final BlockFace[] LAVA_CHECK_FACES = {
            BlockFace.UP,
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
    };

    private boolean freezing;
    private boolean activateFreeze;
    private boolean progressing;
    private boolean canHitSelf;
    private boolean solidifyLava;
    private boolean useVelKnockback;
    private long time;
    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long cooldown;
    private long freezeCooldown;
    private long interval;
    @Attribute("IceRevertTime")
    private long iceRevertTime;
    private long obsidianDuration;
    private double currentRadius;
    @Attribute(Attribute.RADIUS)
    @DayNightFactor
    private double maxRadius;
    @Attribute(Attribute.RANGE)
    @DayNightFactor
    private double range;
    @Attribute(Attribute.SELECT_RANGE)
    @DayNightFactor
    private double selectRange;
    @Attribute(Attribute.KNOCKBACK)
    @DayNightFactor
    private double knockback;
    private double knockbackOthers;
    private double knockbackSpout;
    @Attribute(Attribute.KNOCKUP)
    private double knockup;
    private double knockupOthers;
    private double knockupSpout;
    @Attribute("Freeze" + Attribute.RADIUS)
    @DayNightFactor
    private double maxFreezeRadius;
    private Block sourceBlock;
    private Location location;
    private Location targetDestination;
    private Location frozenLocation;
    private Vector targetDirection;
    private Map<Block, Block> waveBlocks;
    private Map<Block, Material> frozenBlocks;
    private Map<Block, TempBlock> waveLayers;
    private Map<Block, TempBlock> frozenLayers;
    private Random random;

    private double decayAmount;
    private double decayMinimum;
    private long minimumAirBlastTime;

    public SurgeWave(final Player player) {
        super(player);
        this.random = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());

        SurgeWave wave = getAbility(player, SurgeWave.class);
        if (wave != null) {
            if (wave.progressing && !wave.freezing) {
                wave.freezing = true;
                return;
            }
        }

        this.canHitSelf = true;
        this.currentRadius = 1;
        this.cooldown = getConfig().getLong("Abilities.Water.Surge.Wave.Cooldown");
        this.freezeCooldown = getConfig().getLong("Abilities.Water.Surge.Wave.FreezeCooldown");
        this.interval = getConfig().getLong("Abilities.Water.Surge.Wave.Interval");
        this.maxRadius = getConfig().getDouble("Abilities.Water.Surge.Wave.Radius");
        this.knockback = getConfig().getDouble("Abilities.Water.Surge.Wave.Knockback");
        this.knockbackOthers = applyModifiers(getConfig().getDouble("Abilities.Water.Surge.Wave.KnockbackOthers"));
        this.useVelKnockback = getConfig().getBoolean("Abilities.Water.Surge.Wave.UseVelocityKnockback");
        this.knockbackSpout = applyModifiers(getConfig().getDouble("Abilities.Water.Surge.Wave.KnockbackSpout"));
        this.knockup = getConfig().getDouble("Abilities.Water.Surge.Wave.Knockup");
        this.knockupSpout = applyModifiers(getConfig().getDouble("Abilities.Water.Surge.Wave.KnockupSpout"));
        this.knockupOthers = applyModifiers(getConfig().getDouble("Abilities.Water.Surge.Wave.KnockupOthers"));
        this.maxFreezeRadius = getConfig().getDouble("Abilities.Water.Surge.Wave.MaxFreezeRadius");
        this.iceRevertTime = getConfig().getLong("Abilities.Water.Surge.Wave.IceRevertTime");
        this.range = getConfig().getDouble("Abilities.Water.Surge.Wave.Range");
        this.selectRange = getConfig().getDouble("Abilities.Water.Surge.Wave.SelectRange");
        this.solidifyLava = getConfig().getBoolean("Abilities.Water.Surge.Wave.SolidifyLava.Enabled");
        this.obsidianDuration = getConfig().getLong("Abilities.Water.Surge.Wave.SolidifyLava.Duration");
        this.decayAmount = getConfig().getDouble("Abilities.Water.Surge.DecayAmount");
        this.decayMinimum = getConfig().getDouble("Abilities.Water.Surge.DecayMinimum");
        this.minimumAirBlastTime = getConfig().getLong("Abilities.Water.Surge.MinimumSurgeWaveTime");

        this.waveBlocks = new HashMap<>();
        this.frozenBlocks = new HashMap<>();
        this.waveLayers = new HashMap<>();
        this.frozenLayers = new HashMap<>();

        if (this.prepare()) {
            wave = getAbility(player, SurgeWave.class);
            if (wave != null) {
                wave.remove();
            }

            this.knockback *= bPlayer.getSurgeWaveDecay();
            this.knockbackOthers *= bPlayer.getSurgeWaveDecay();
            this.knockup *= bPlayer.getSurgeWaveDecay();

            this.start();
            this.time = System.currentTimeMillis();
        }
    }

    public static boolean canThaw(final Block block) {
        if (TempBlockSync.hasAuthoritativeEffect(block, "Surge")) return false;
        for (final SurgeWave surgeWave : getAbilities(SurgeWave.class)) {
            if (surgeWave.frozenBlocks.containsKey(block)) {
                return false;
            }
        }
        return true;
    }

    public static void removeAllCleanup() {
        for (final SurgeWave surgeWave : getAbilities(SurgeWave.class)) {
            for (final Block block : new ArrayList<>(surgeWave.waveBlocks.keySet())) {
                surgeWave.retireWave(block);
            }

            for (final Block block : new ArrayList<>(surgeWave.frozenBlocks.keySet())) {
                surgeWave.retireFrozen(block);
            }
        }
    }

    public static boolean isBlockWave(final Block block) {
        for (final SurgeWave surgeWave : getAbilities(SurgeWave.class)) {
            if (surgeWave.waveBlocks.containsKey(block)) {
                return true;
            }
        }
        return false;
    }

    public static void thaw(final Block block) {
        for (final SurgeWave surgeWave : getAbilities(SurgeWave.class)) {
            if (surgeWave.frozenBlocks.containsKey(block)) {
                surgeWave.retireFrozen(block);
            }
        }
    }

    private boolean isChunkLoaded(final Block block) {
        return block != null && block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4);
    }

    private Block getLoadedBlockAt(final Location loc) {
        if (loc == null) {
            return null;
        }

        final World world = loc.getWorld();
        if (world == null) {
            return null;
        }

        final int x = loc.getBlockX();
        final int y = loc.getBlockY();
        final int z = loc.getBlockZ();

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        return world.getBlockAt(x, y, z);
    }

    private Block getLoadedBlockAt(final Location base, final Vector offset) {
        if (base == null || offset == null) {
            return null;
        }

        final World world = base.getWorld();
        if (world == null) {
            return null;
        }

        final int x = (int) Math.floor(base.getX() + offset.getX());
        final int y = (int) Math.floor(base.getY() + offset.getY());
        final int z = (int) Math.floor(base.getZ() + offset.getZ());

        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }

        return world.getBlockAt(x, y, z);
    }

    private boolean isWaveCandidate(final Block block) {
        if (block == null || !this.isChunkLoaded(block)) {
            return false;
        }

        final Material type = block.getType();

        return isAir(type)
                || isFire(type)
                || isPlant(block)
                || isWater(block)
                || this.isWaterbendable(this.player, block);
    }

    private void solidifyAdjacentLava(final Block block) {
        if (!this.solidifyLava || block == null) {
            return;
        }

        for (final BlockFace face : LAVA_CHECK_FACES) {
            final Block relative = block.getRelative(face);

            if (!this.isChunkLoaded(relative)) {
                continue;
            }

            if (relative.getType() != Material.LAVA) {
                continue;
            }

            final Levelled levelled = (Levelled) relative.getBlockData();
            final TempBlock tempBlock;

            if (levelled.getLevel() == 0) {
                tempBlock = new TempBlock(relative, Material.OBSIDIAN.createBlockData(), this);
            } else {
                tempBlock = new TempBlock(relative, Material.COBBLESTONE.createBlockData(), this);
            }

            tempBlock.setRevertTime(this.obsidianDuration);
            tempBlock.getBlock().getWorld().playSound(tempBlock.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 0.2F, 1);
        }
    }

    private boolean touchesWaveFast(final Entity entity, final Vector direction) {
        if (entity == null || this.location == null || direction == null) {
            return false;
        }

        if (!entity.getWorld().equals(this.location.getWorld())) {
            return false;
        }

        final Location entityLoc = entity.getLocation();

        final double dx = entityLoc.getX() - this.location.getX();
        final double dy = entityLoc.getY() - this.location.getY();
        final double dz = entityLoc.getZ() - this.location.getZ();

        final double axialDistance = Math.abs(
                dx * direction.getX()
                        + dy * direction.getY()
                        + dz * direction.getZ()
        );

        if (axialDistance > 2.25) {
            return false;
        }

        final double totalDistanceSquared = dx * dx + dy * dy + dz * dz;
        final double radialDistanceSquared = Math.max(0.0, totalDistanceSquared - axialDistance * axialDistance);

        final double hitRadius = this.currentRadius + 2.0;
        return radialDistanceSquared <= hitRadius * hitRadius;
    }

    private boolean addWater(final Block block) {
        if (block == null) {
            return false;
        }

        if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
            return false;
        }

        if (TempBlock.isTempBlock(block)) {
            return false;
        }

        final TempBlock layer = new TempBlock(block, Material.WATER.createBlockData(), this);
        this.waveLayers.put(block, layer);
        this.waveBlocks.put(block, block);
        layer.setRevertTask(() -> {
            if (SurgeWave.this.waveLayers.remove(block, layer)) {
                SurgeWave.this.waveBlocks.remove(block);
            }
        });
        return true;
    }

    private void cancelPrevious() {
        final SurgeWave oldWave = getAbility(this.player, SurgeWave.class);
        if (oldWave != null) {
            oldWave.remove();
        }
    }

    private void clearWave() {
        for (final Block block : new ArrayList<>(this.waveBlocks.keySet())) {
            this.retireWave(block);
        }
    }

    private void finalRemoveWater(final Block block) {
        if (block == null) {
            return;
        }

        if (this.waveBlocks.containsKey(block)) {
            this.retireWave(block);
        }
    }

    private void retireWave(final Block block) {
        if (block == null) return;
        this.waveBlocks.remove(block);
        final TempBlock layer = this.waveLayers.remove(block);
        if (layer != null) layer.revertBlock();
    }

    private void retireFrozen(final Block block) {
        if (block == null) return;
        this.frozenBlocks.remove(block);
        final TempBlock layer = this.frozenLayers.remove(block);
        if (layer != null) layer.revertBlock();
    }

    private void focusBlock() {
        this.location = this.sourceBlock.getLocation();
    }

    private void freeze() {
        if (bPlayer.isOnCooldown("SurgeFreeze")) {
            remove();
            return;
        }

        this.clearWave();
        if (!this.bPlayer.canIcebend()) {
            return;
        }

        double freezeradius = this.currentRadius;
        if (freezeradius > this.maxFreezeRadius) {
            freezeradius = this.maxFreezeRadius;
        }

        if (this.frozenLocation == null || this.frozenLocation.getWorld() == null) {
            return;
        }

        final List<Entity> trapped = GeneralMethods.getEntitiesAroundPoint(this.frozenLocation, freezeradius);

        final World world = this.frozenLocation.getWorld();
        final int centerX = this.frozenLocation.getBlockX();
        final int centerY = this.frozenLocation.getBlockY();
        final int centerZ = this.frozenLocation.getBlockZ();
        final int radius = (int) Math.ceil(freezeradius);
        final double radiusSquared = freezeradius * freezeradius;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int y = centerY - radius; y <= centerY + radius; y++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    final double dx = x + 0.5 - this.frozenLocation.getX();
                    final double dy = y + 0.5 - this.frozenLocation.getY();
                    final double dz = z + 0.5 - this.frozenLocation.getZ();

                    if (dx * dx + dy * dy + dz * dz > radiusSquared) {
                        continue;
                    }

                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }

                    final Block block = world.getBlockAt(x, y, z);

                    if (GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
                        continue;
                    } else if (TempBlock.isTempBlock(block)) {
                        continue;
                    }

                    boolean skipBlock = false;

                    for (final Entity entity : trapped) {
                        if (entity instanceof Player) {
                            if (Commands.invincible.contains(((Player) entity).getName())) {
                                return;
                            }
                            if (!getConfig().getBoolean("Properties.Water.FreezePlayerHead") && GeneralMethods.playerHeadIsInBlock((Player) entity, block)) {
                                skipBlock = true;
                                break;
                            }
                            if (!getConfig().getBoolean("Properties.Water.FreezePlayerFeet") && GeneralMethods.playerFeetIsInBlock((Player) entity, block)) {
                                skipBlock = true;
                                break;
                            }
                        }
                    }

                    if (skipBlock) {
                        continue;
                    }

                    if (!this.isChunkLoaded(block)) {
                        continue;
                    }

                    final Material oldType = block.getType();

                    if (!isAir(oldType) && oldType != Material.SNOW && !isWater(block) && !isPlant(block)) {
                        continue;
                    } else if (isPlant(block)) {
                        block.breakNaturally();
                    }

                    if (this.iceRevertTime != 0) {
                        final TempBlock tblock = new TempBlock(block, getIceData(), this).setCanSuffocate(false);
                        this.frozenLayers.put(block, tblock);
                        tblock.setRevertTask(() -> {
                            if (SurgeWave.this.frozenLayers.remove(block, tblock)) {
                                SurgeWave.this.frozenBlocks.remove(block);
                            }
                        });
                        tblock.setRevertTime(this.iceRevertTime + this.random.nextInt(1000));
                    }

                    this.frozenBlocks.put(block, oldType);

                    if (ThreadLocalRandom.current().nextInt(4) == 0) {
                        playWaterbendingSound(block.getLocation());
                    }
                }
            }
        }

        bPlayer.addCooldown("SurgeFreeze", freezeCooldown);
    }

    private Vector getDirection(final Location location, final Location destination) {
        double x1, y1, z1;
        double x0, y0, z0;

        x1 = destination.getX();
        y1 = destination.getY();
        z1 = destination.getZ();

        x0 = location.getX();
        y0 = location.getY();
        z0 = location.getZ();

        return new Vector(x1 - x0, y1 - y0, z1 - z0);
    }

    public void moveWater() {
        if (this.bPlayer.isOnCooldown("SurgeWave")) {
            return;
        }

        if (System.currentTimeMillis() - bPlayer.getLastSurgeWaveTime() < minimumAirBlastTime) {
            bPlayer.increaseSurgeWaveDecay(decayAmount, decayMinimum);
        }

        bPlayer.resetSurgeWave();

        this.bPlayer.addCooldown("SurgeWave", this.cooldown);

        if (this.sourceBlock != null) {
            if (!this.sourceBlock.getWorld().equals(this.player.getWorld())) {
                return;
            }

            final Entity target = GeneralMethods.getTargetedEntity(this.player, this.range);
            if (target == null) {
                this.targetDestination = this.player.getTargetBlock(getTransparentMaterialSet(), (int) this.range).getLocation();
            } else {
                this.targetDestination = ((LivingEntity) target).getEyeLocation();
            }

            if (this.targetDestination.distanceSquared(this.location) <= 1) {
                this.progressing = false;
                this.targetDestination = null;
            } else {
                this.progressing = true;
                this.targetDirection = this.getDirection(this.sourceBlock.getLocation(), this.targetDestination).normalize();
                this.targetDestination = this.location.clone().add(this.targetDirection.clone().multiply(this.range));

                if (isPlant(this.sourceBlock) || isSnow(this.sourceBlock)) {
                    new PlantRegrowth(this.player, this.sourceBlock);
                    this.sourceBlock.setType(Material.AIR, false);
                } else if (isCauldron(this.sourceBlock) || isTransformableBlock(this.sourceBlock)) {
                    updateSourceBlock(this.sourceBlock);
                }

                if (TempBlock.isTempBlock(this.sourceBlock)) {
                    final TempBlock tb = TempBlock.get(this.sourceBlock);
                    if (Torrent.getFrozenBlocks().containsKey(tb)) {
                        Torrent.massThaw(tb);
                    }
                }
                this.addWater(this.sourceBlock);
            }
        }
    }

    public boolean prepare() {
        this.cancelPrevious();
        final Block block = BlockSource.getWaterSourceBlock(this.player, this.selectRange, ClickType.SHIFT_DOWN, true, true, this.bPlayer.canPlantbend());
        if (block != null && !GeneralMethods.isRegionProtectedFromBuild(this, block.getLocation())) {
            this.sourceBlock = block;
            this.focusBlock();
            return true;
        }
        return false;
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        } else if (!isWaterbendable(this.sourceBlock) && !this.progressing) {
            this.remove();
            return;
        }

        if (System.currentTimeMillis() - this.time >= this.interval) {
            this.time = System.currentTimeMillis();

            if (!this.progressing && !this.bPlayer.getBoundAbilityName().equals(this.getName())) {
                this.remove();
                return;
            } else if (!this.progressing) {
                ParticleEffect.SMOKE_NORMAL.display(this.sourceBlock.getLocation().add(0.5, 0.5, 0.5), 4);
                return;
            }

            if (this.activateFreeze) {
                if (this.location.distanceSquared(this.player.getLocation()) > this.range * this.range) {
                    this.progressing = false;
                    this.remove();
                    return;
                }
            } else {
                final Vector direction = this.targetDirection;
                this.location = this.location.clone().add(direction);

                final Block blockl = this.getLoadedBlockAt(this.location);
                final Set<Block> blocks = new HashSet<>();

                if (blockl != null && !RegionProtection.isRegionProtected(this, this.location) && this.isWaveCandidate(blockl)) {
                    int sampleIndex = 0;

                    for (double i = 0; i <= this.currentRadius; i += 0.5) {
                        for (double angle = 0; angle < 360; angle += 10) {
                            final Vector vec = GeneralMethods.getOrthogonalVector(this.targetDirection, angle, i);
                            final Block block = this.getLoadedBlockAt(this.location, vec);

                            if (block == null) {
                                sampleIndex++;
                                continue;
                            }

                            if (blocks.contains(block)) {
                                sampleIndex++;
                                continue;
                            }

                            if (!this.isWaveCandidate(block)) {
                                sampleIndex++;
                                continue;
                            }

                            blocks.add(block);

                            if (isWater(block) && ThreadLocalRandom.current().nextInt(8) == 0) {
                                ParticleEffect.WATER_BUBBLE.display(
                                        block.getLocation().clone().add(0.5, 0.5, 0.5),
                                        1,
                                        ThreadLocalRandom.current().nextDouble(0, 0.5),
                                        ThreadLocalRandom.current().nextDouble(0, 0.5),
                                        ThreadLocalRandom.current().nextDouble(0, 0.5),
                                        0
                                );
                            }

                            if ((this.getStartTick() + sampleIndex + this.getRunningTicks()) % (int) (((this.currentRadius * this.currentRadius) + 3) * 3) == 0) {
                                playWaterbendingSound(block.getLocation());
                            }

                            sampleIndex++;
                        }
                    }

                    FireBlast.removeFireBlastsAroundPoint(this.location, this.currentRadius + 2.0);
                }

                final Iterator<Block> iterator = this.waveBlocks.keySet().iterator();
                while (iterator.hasNext()) {
                    final Block block = iterator.next();

                    if (!blocks.contains(block)) {
                        final TempBlock layer = this.waveLayers.remove(block);
                        iterator.remove();
                        if (layer != null) layer.revertBlock();
                    }
                }

                for (final Block block : blocks) {
                    if (!this.waveBlocks.containsKey(block)) {
                        if (this.addWater(block)) {
                            this.solidifyAdjacentLava(block);
                        }
                    }
                }

                if (this.waveBlocks.isEmpty()) {
                    this.location = this.location.subtract(direction);
                    this.remove();
                    this.progressing = false;
                    return;
                }

                for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.location, this.currentRadius + 2.25)) {
                    if (!this.touchesWaveFast(entity, direction)) {
                        continue;
                    }

                    if (entity instanceof LivingEntity && this.freezing && entity.getEntityId() != this.player.getEntityId()) {
                        this.activateFreeze = true;
                        this.frozenLocation = entity.getLocation();
                        this.freeze();
                        continue;
                    }

                    if (entity.getEntityId() != this.player.getEntityId() || this.canHitSelf) {
                        if (RegionProtection.isRegionProtected(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                            continue;
                        }

                        final Vector dir = direction.clone();
                        final boolean isOnSpout = CoreAbility.hasAbility(player, WaterSpout.class);
                        final Vector vel = useVelKnockback || isOnSpout ? entity.getVelocity() : new Vector(0, 0, 0);

                        if (entity.getEntityId() == this.player.getEntityId()) {
                            dir.setY(dir.getY() * (!isOnSpout ? knockup : knockupSpout));
                            final double kb = !isOnSpout ? this.knockback : knockbackSpout;
                            GeneralMethods.setVelocity(this, entity, vel.clone().add(dir.clone().multiply(this.getNightFactor(kb))));
                        } else {
                            dir.setY(dir.getY() * this.knockupOthers);
                            GeneralMethods.setVelocity(this, entity, vel.clone().add(dir.clone().multiply(this.getNightFactor(this.knockbackOthers))));
                        }

                        entity.setFallDistance(0);
                        if (entity.getFireTicks() > 0) {
                            entity.getWorld().playEffect(entity.getLocation(), Effect.EXTINGUISH, 0);
                        }
                        entity.setFireTicks(0);
                        AirAbility.breakBreathbendingHold(entity);
                    }
                }

                if (!this.progressing) {
                    this.remove();
                    return;
                }

                if (this.location.distanceSquared(this.targetDestination) < 1) {
                    this.progressing = false;
                    this.remove();
                    this.returnWater();
                    return;
                }

                if (this.currentRadius < this.maxRadius) {
                    this.currentRadius += 0.5;
                }
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
        this.thaw();
        this.returnWater();

        if (this.waveBlocks != null) {
            for (final Block block : new ArrayList<>(this.waveBlocks.keySet())) {
                this.finalRemoveWater(block);
            }
        }
    }

    public void returnWater() {
        if (this.location != null && this.player.isOnline()) {
            final Block block = this.getLoadedBlockAt(this.location);
            if (block != null) {
                new WaterReturn(this.player, block);
            }
        }
    }

    private void thaw() {
        if (this.frozenBlocks != null) {
            for (final Block block : new ArrayList<>(this.frozenBlocks.keySet())) {
                this.retireFrozen(block);
            }
        }
    }

    @Override
    public String getName() {
        return "Surge";
    }

    @Override
    public Location getLocation() {
        if (this.location != null) {
            return this.location;
        } else if (this.sourceBlock != null) {
            return this.sourceBlock.getLocation();
        }
        return this.player != null ? this.player.getLocation() : null;
    }

    public void setLocation(final Location location) {
        this.location = location;
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
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return this.progressing || this.activateFreeze;
    }

    @Override
    public List<Location> getLocations() {
        final ArrayList<Location> locations = new ArrayList<>();

        for (final Block block : this.waveBlocks.keySet()) {
            locations.add(block.getLocation());
        }

        for (final Block block : this.frozenBlocks.keySet()) {
            locations.add(block.getLocation());
        }

        return locations;
    }

    public boolean isFreezing() {
        return this.freezing;
    }

    public void setFreezing(final boolean freezing) {
        this.freezing = freezing;
    }

    public boolean isActivateFreeze() {
        return this.activateFreeze;
    }

    public void setActivateFreeze(final boolean activateFreeze) {
        this.activateFreeze = activateFreeze;
    }

    public boolean isProgressing() {
        return this.progressing;
    }

    public void setProgressing(final boolean progressing) {
        this.progressing = progressing;
    }

    public boolean isCanHitSelf() {
        return this.canHitSelf;
    }

    public void setCanHitSelf(final boolean canHitSelf) {
        this.canHitSelf = canHitSelf;
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

    public double getCurrentRadius() {
        return this.currentRadius;
    }

    public void setCurrentRadius(final double currentRadius) {
        this.currentRadius = currentRadius;
    }

    public double getMaxRadius() {
        return this.maxRadius;
    }

    public void setMaxRadius(final double maxRadius) {
        this.maxRadius = maxRadius;
    }

    public double getRange() {
        return this.range;
    }

    public void setRange(final double range) {
        this.range = range;
    }

    public double getKnockback() {
        return this.knockback;
    }

    public void setKnockback(final double knockback) {
        this.knockback = knockback;
    }

    public double getKnockup() {
        return this.knockup;
    }

    public void setKnockup(final double knockup) {
        this.knockup = knockup;
    }

    public double getMaxFreezeRadius() {
        return this.maxFreezeRadius;
    }

    public void setMaxFreezeRadius(final double maxFreezeRadius) {
        this.maxFreezeRadius = maxFreezeRadius;
    }

    public Block getSourceBlock() {
        return this.sourceBlock;
    }

    public void setSourceBlock(final Block sourceBlock) {
        this.sourceBlock = sourceBlock;
    }

    public Location getTargetDestination() {
        return this.targetDestination;
    }

    public void setTargetDestination(final Location targetDestination) {
        this.targetDestination = targetDestination;
    }

    public Location getFrozenLocation() {
        return this.frozenLocation;
    }

    public void setFrozenLocation(final Location frozenLocation) {
        this.frozenLocation = frozenLocation;
    }

    public Vector getTargetDirection() {
        return this.targetDirection;
    }

    public void setTargetDirection(final Vector targetDirection) {
        this.targetDirection = targetDirection;
    }

    public Map<Block, Block> getWaveBlocks() {
        return this.waveBlocks;
    }

    public Map<Block, Material> getFrozenBlocks() {
        return this.frozenBlocks;
    }
}
