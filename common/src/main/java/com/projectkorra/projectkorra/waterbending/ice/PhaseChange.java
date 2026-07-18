package com.projectkorra.projectkorra.waterbending.ice;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.IceAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.block.data.type.Snow;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.prediction.TempBlockSync;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.SurgeWall;
import com.projectkorra.projectkorra.waterbending.SurgeWave;
import com.projectkorra.projectkorra.waterbending.Torrent;
import com.projectkorra.projectkorra.waterbending.WaterSpoutWave;
import com.projectkorra.projectkorra.waterbending.multiabilities.WaterArmsSpear;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class PhaseChange extends IceAbility {

    private static final long MELT_REVERT_MILLIS = 120_000L;

    private static Map<TempBlock, Player> PLAYER_BY_BLOCK = new HashMap<>();
    private static List<Block> BLOCKS = new ArrayList<>();
    private final List<PhaseChangeType> active_types = new ArrayList<>();
    private final CopyOnWriteArrayList<TempBlock> blocks = new CopyOnWriteArrayList<>();
    private final Random r;
    private final CopyOnWriteArrayList<Block> melted_blocks = new CopyOnWriteArrayList<>();
    private final Map<Block, TempBlock> meltLayers = new HashMap<>();
    @Attribute(Attribute.SELECT_RANGE)
    private double sourceRange = 8;

    // Freeze Variables.
    @Attribute("Freeze" + Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long freezeCooldown = 500;
    @Attribute("Freeze" + Attribute.RADIUS)
    @DayNightFactor
    private double freezeRadius = 3;
    @Attribute("FreezeDepth")
    @DayNightFactor
    private int depth = 1;
    @Attribute("Control" + Attribute.RADIUS)
    @DayNightFactor
    private double controlRadius = 25;

    // Melt Variables.
    private Location meltLoc;
    @Attribute("Melt" + Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long meltCooldown = 7000;
    private int meltRadius;
    @Attribute("Melt" + Attribute.RADIUS)
    @DayNightFactor
    private double meltMaxRadius = 7;
    @Attribute("Melt" + Attribute.SPEED)
    @DayNightFactor
    private double meltSpeed = 8;
    private double meltTicks = 0;
    private boolean allowMeltFlow;
    private boolean instantMelt;
    public PhaseChange(final Player player, final PhaseChangeType type) {
        super(player);
        this.r = PredictionDeterminism.random(player.getUniqueId(), getClass().getName());
        this.startNewType(type);
        this.start();
    }

    /**
     * Only works with PhaseChange frozen blocks!
     *
     * @param tb TempBlock being thawed
     * @return true when it is thawed successfully
     */
    public static boolean thaw(final TempBlock tb) {
        if (tb == null || !PLAYER_BY_BLOCK.containsKey(tb)) {
            return false;
        } else {
            final Player p = PLAYER_BY_BLOCK.get(tb);
            final PhaseChange pc = getAbility(p, PhaseChange.class);
            if (pc == null) {
                untrackFrozenIndexes(tb);
                return false;
            }
            if (tb.isReverted()) {
                pc.untrackFrozen(tb);
                return false;
            }
            if (pc.getFrozenBlocks().contains(tb)) {
                tb.revertBlock();
                return true;
            }
            return false;
        }
    }

    /**
     * Only works if the block is a {@link TempBlock} and PhaseChange frozen!
     *
     * @param b Block being thawed
     * @return false if not a {@link TempBlock}
     */
    public static boolean thaw(final Block b) {
        final List<TempBlock> layers = TempBlock.getAll(b);
        if (layers == null) return false;
        for (int index = layers.size() - 1; index >= 0; index--) {
            final TempBlock layer = layers.get(index);
            if (PLAYER_BY_BLOCK.containsKey(layer)) return thaw(layer);
        }
        return false;
    }

    public static Map<TempBlock, Player> getFrozenBlocksMap() {
        return PLAYER_BY_BLOCK;
    }

    public static List<Block> getFrozenBlocksAsBlock() {
        return BLOCKS;
    }

    /** Drops every old-world frozen-layer index without restoring a block. */
    public static void discardAllTracking() {
        PLAYER_BY_BLOCK.clear();
        BLOCKS.clear();
    }

    @Override
    public void progress() {
        // A client-side DISCARD intentionally skips revert callbacks. Purge
        // those retired handles here so PhaseChange's indexes cannot keep a
        // dead layer alive after an explicit server authority handoff.
        for (final TempBlock block : this.blocks) {
            if (block.isReverted()) untrackFrozen(block);
        }

        if (!this.player.isOnline() || this.player.isDead()) {
            this.remove();
            return;
        }

        if (this.active_types.contains(PhaseChangeType.FREEZE)) {
            if (this.blocks.isEmpty()) {
                this.active_types.remove(PhaseChangeType.FREEZE);
                return;
            }

            for (final TempBlock tb : this.blocks) {
                if (tb.getLocation().getWorld() != this.player.getWorld()) {
                    tb.revertBlock();
                } else if (tb.getLocation().distanceSquared(this.player.getLocation()) > (this.controlRadius * this.controlRadius)) {
                    tb.revertBlock();
                }
            }
        }

        if (this.active_types.contains(PhaseChangeType.MELT)) {
            if (!this.player.isSneaking() || !this.bPlayer.canBend(this)) {
                this.active_types.remove(PhaseChangeType.MELT);
                this.bPlayer.addCooldown("PhaseChangeMelt", this.meltCooldown);
                this.meltRadius = 1;
                this.meltTicks = 0;
                return;
            }
            if (this.meltRadius >= this.meltMaxRadius) {
                this.meltRadius = 1;
            }
            final Location l = GeneralMethods.getTargetedLocation(this.player, this.sourceRange);
            this.resetMeltLocation(l);
            if (!instantMelt) this.meltArea(l, this.meltRadius);
            else this.meltArea(l, (int) this.meltMaxRadius);
        }

        if (this.active_types.contains(PhaseChangeType.CUSTOM)) {
            for (final TempBlock tb : this.blocks) {
                if (tb.getLocation().getWorld() != this.player.getWorld()) {
                    tb.revertBlock();
                } else if (tb.getLocation().distanceSquared(this.player.getLocation()) > (this.controlRadius * this.controlRadius)) {
                    tb.revertBlock();
                }
            }
        }

        if (this.active_types.isEmpty()) {
            this.remove();
        }
    }

    public void startNewType(final PhaseChangeType type) {
        if (type == PhaseChangeType.MELT) {
            if (this.bPlayer.isOnCooldown("PhaseChangeMelt")) {
                return;
            }
        }

        this.active_types.add(type);
        this.setFields(type);
    }

    public void setFields(final PhaseChangeType type) {
        this.sourceRange = getConfig().getDouble("Abilities.Water.PhaseChange.SourceRange");
        switch (type) {
            case FREEZE:
                this.depth = (int) getConfig().getInt("Abilities.Water.PhaseChange.Freeze.Depth");
                this.controlRadius = getConfig().getDouble("Abilities.Water.PhaseChange.Freeze.ControlRadius");
                this.freezeCooldown = getConfig().getLong("Abilities.Water.PhaseChange.Freeze.Cooldown");
                this.freezeRadius = getConfig().getInt("Abilities.Water.PhaseChange.Freeze.Radius");

                this.recalculateAttributes();
                this.freezeArea(GeneralMethods.getTargetedLocation(this.player, this.sourceRange));
                return;
            case MELT:
                this.meltRadius = 1;
                this.meltCooldown = getConfig().getLong("Abilities.Water.PhaseChange.Melt.Cooldown");
                this.meltSpeed = getConfig().getDouble("Abilities.Water.PhaseChange.Melt.Speed");
                this.meltMaxRadius = getConfig().getDouble("Abilities.Water.PhaseChange.Melt.Radius");
                this.allowMeltFlow = getConfig().getBoolean("Abilities.Water.PhaseChange.Melt.AllowFlow");
                this.instantMelt = getConfig().getBoolean("Abilities.Water.PhaseChange.Melt.Instant");
                return;
            case CUSTOM:
                this.depth = (int) getConfig().getInt("Abilities.Water.PhaseChange.Freeze.Depth");
                this.controlRadius = getConfig().getDouble("Abilities.Water.PhaseChange.Freeze.ControlRadius");
                this.freezeCooldown = getConfig().getLong("Abilities.Water.PhaseChange.Freeze.Cooldown");
                this.freezeRadius = getConfig().getDouble("Abilities.Water.PhaseChange.Freeze.Radius");

                this.meltRadius = 1;
                this.meltCooldown = getConfig().getLong("Abilities.Water.PhaseChange.Melt.Cooldown");
                this.meltSpeed = getConfig().getDouble("Abilities.Water.PhaseChange.Melt.Speed");
                this.meltMaxRadius = getConfig().getDouble("Abilities.Water.PhaseChange.Melt.Radius");
                this.allowMeltFlow = getConfig().getBoolean("Abilities.Water.PhaseChange.Melt.AllowFlow");
        }
    }

    public void resetMeltLocation(final Location loc) {
        if (this.meltLoc == null) {
            this.meltLoc = loc;
            return;
        }

        if (this.meltLoc.getWorld().equals(loc.getWorld()) && this.meltLoc.distance(loc) < 1) {
            return;
        }

        if (!loc.equals(this.meltLoc)) {
            this.meltLoc = loc;
            this.meltRadius = 1;
        }
    }

    public ArrayList<BlockFace> getBlockFacesTowardsPlayer(final Location center) {
        final ArrayList<BlockFace> faces = new ArrayList<>();
        final Vector toPlayer = GeneralMethods.getDirection(center, this.player.getEyeLocation());
        final double[] vars = {toPlayer.getX(), toPlayer.getY(), toPlayer.getZ()};
        for (int i = 0; i < 3; i++) {
            if (vars[i] != 0) {
                faces.add(GeneralMethods.getBlockFaceFromValue(i, vars[i]));
            } else {
                continue;
            }
        }
        return faces;
    }

    public ArrayList<Block> getBlocksToFreeze(final Location center, final double radius) {
        final ArrayList<Block> blocks = new ArrayList<>();
        for (final Location l : GeneralMethods.getCircle(center, (int) radius, this.depth, false, true, 0)) {
            final Block b = l.getBlock();
            loop:
            for (int i = 1; i <= this.depth; i++) {
                for (final BlockFace face : this.getBlockFacesTowardsPlayer(center)) {
                    if (ElementalAbility.isAir(b.getRelative(face, i).getType())) {
                        blocks.add(b);
                        break loop;
                    }
                }
            }
        }
        return blocks;
    }

    public void freezeArea(final Location center, final double radius, final PhaseChangeType type) {
        if (type == PhaseChangeType.FREEZE) {
            if (this.bPlayer.isOnCooldown("PhaseChangeFreeze")) {
                return;
            }
        }

        if (this.depth > 1) {
            center.subtract(0, this.depth - 1, 0);
        }

        final ArrayList<Block> toFreeze = this.getBlocksToFreeze(center, radius);
        for (final Block b : toFreeze) {
            this.freeze(b);
        }

        if (!this.blocks.isEmpty()) {
            if (type == PhaseChangeType.FREEZE) {
                this.bPlayer.addCooldown("PhaseChangeFreeze", this.freezeCooldown);
            }
        }
    }

    public void freezeArea(final Location center, final double radius) {
        this.freezeArea(center, radius, PhaseChangeType.FREEZE);
    }

    public void freezeArea(final Location center, final PhaseChangeType type) {
        this.freezeArea(center, this.freezeRadius, type);
    }

    public void freezeArea(final Location center) {
        this.freezeArea(center, this.freezeRadius);
    }

    public void freeze(final Block b) {
        if (b.getWorld() != this.player.getWorld()) {
            return;
        }

        if (b.getLocation().distanceSquared(this.player.getLocation()) > this.controlRadius * this.controlRadius) {
            return;
        }

        if (RegionProtection.isRegionProtected(this.player, b.getLocation(), this)) {
            return;
        }

        TempBlock tb = TempBlock.get(b);
        // The world view is the merged visual top on both sides: the client's
        // predicted layer (or server overlay) and Paper's physical top. Using
        // only the local registry ignored remote/server-only overlays that are
        // intentionally represented without a client TempBlock handle.
        final BlockData visibleData = b.getBlockData();
        if (!isWater(visibleData)) {
            return;
        }

        // A predicting client intentionally has no common TempBlock handle
        // for another player's server layer. Metadata still proves that this
        // is temporary water, so preserve the legacy no-adoption rule.
        if (tb == null && TempBlockSync.hasAuthoritativeLayer(b)) {
            return;
        }

        if (tb != null) {
            // Legacy refreeze ended PhaseChange's own temporary melt, revealing
            // the ice captured beneath it. Stacking a second ICE layer here
            // lets the old WATER reappear later and is the source of the rare
            // client-water/server-ice ghost. Every unrelated TempBlock water
            // layer remains ineligible.
            final TempBlock meltLayer = this.meltLayers.get(b);
            if (tb != meltLayer || tb.isReverted() || tb.getAbility().orElse(null) != this
                    || tb.getBlockData().getMaterial() != Material.WATER) {
                return;
            }
            tb.revertBlock();
            this.meltLayers.remove(b, tb);
            this.melted_blocks.remove(b);
            playIcebendingSound(b.getLocation());
            return;
        }

        tb = new TempBlock(b, Material.ICE.createBlockData(), this);
        tb.setCanSuffocate(false);
        trackFrozen(tb);
        playIcebendingSound(b.getLocation());
    }

    private void trackFrozen(final TempBlock block) {
        if (!this.blocks.contains(block)) this.blocks.add(block);
        PLAYER_BY_BLOCK.put(block, this.player);
        if (!BLOCKS.contains(block.getBlock())) BLOCKS.add(block.getBlock());
        block.setRevertTask(() -> untrackFrozen(block));
    }

    private void untrackFrozen(final TempBlock block) {
        this.blocks.remove(block);
        untrackFrozenIndexes(block);
    }

    private static void untrackFrozenIndexes(final TempBlock block) {
        PLAYER_BY_BLOCK.remove(block);
        boolean coordinateStillFrozen = false;
        for (TempBlock remaining : PLAYER_BY_BLOCK.keySet()) {
            if (remaining.getBlock().equals(block.getBlock())) {
                coordinateStillFrozen = true;
                break;
            }
        }
        if (!coordinateStillFrozen) BLOCKS.remove(block.getBlock());
    }

    public void meltArea(final Location center, final int radius) {
        final List<Block> ice = new ArrayList<Block>();
        for (final Location l : GeneralMethods.getCircle(center, radius, 3, !instantMelt, true, 0)) {
            if (isRegisteredMeltable(l.getBlock())) {
                ice.add(l.getBlock());
            }
        }

        this.meltTicks += this.meltSpeed / 20;

        for (int i = 0; i < this.meltTicks % (this.meltSpeed); i++) {
            if (ice.size() == 0) {
                this.meltRadius++;
                return;
            }

            final Block b = ice.get(this.r.nextInt(ice.size()));
            this.melt(b);
            ice.remove(b);
        }
    }

    public void meltArea(final Location center) {
        this.meltArea(center, this.meltRadius);
    }

    public void melt(final Block b) {
        if (b.getWorld() != this.player.getWorld()) {
            return;
        }
        if (b.getLocation().distanceSquared(this.player.getLocation()) > this.controlRadius * this.controlRadius) {
            return;
        }
        if (RegionProtection.isRegionProtected(this.player, b.getLocation(), this)) {
            return;
        }
        if (SurgeWall.getWallBlocks().containsKey(b)) {
            return;
        }
        if (SurgeWave.isBlockWave(b)) {
            return;
        }
        if (!SurgeWave.canThaw(b)) {
            SurgeWave.thaw(b);
            return;
        }
        if (!Torrent.canThaw(b)) {
            Torrent.thaw(b);
            return;
        }
        if (WaterArmsSpear.canThaw(b)) {
            WaterArmsSpear.thaw(b);
            return;
        }
        if (WaterSpoutWave.canThaw(b)) {
            WaterSpoutWave.thaw(b);
            return;
        }
        if (!TempBlock.isTempBlock(b) && TempBlockSync.hasAuthoritativeLayer(b)) {
            // Fabric cannot inspect or retire another player's common handle.
            // Known Torrent/Surge/WaterArms/WaterSpout layers were delegated
            // above; every other remote TempBlock is left to Paper's exact
            // lifecycle instead of inventing a conflicting local layer.
            return;
        }
        if (TempBlock.isTempBlock(b)) {
            // A registered PhaseChange layer can be buried beneath a newer
            // overlap. Retire that exact layer first; never infer ownership
            // from whichever layer happens to be physically on top.
            if (thaw(b)) {
                playWaterbendingSound(b.getLocation());
                return;
            }

            final BlockData visibleData = b.getBlockData();

            if (!isIce(visibleData.getMaterial()) && !isSnow(visibleData.getMaterial())) {
                return;
            }

            final TempBlock tb = TempBlock.get(b);
            if (isSnow(visibleData.getMaterial())) {
                final BlockData melted = meltedData(visibleData, b.getWorld());
                if (melted == null) return;
                // Legacy snow melting replaced the visible temporary layer;
                // retaining every thinner layer creates an ever-growing stack.
                tb.revertBlock();
                this.trackMelted(b, new TempBlock(b, melted, MELT_REVERT_MILLIS, this));
                playWaterbendingSound(b.getLocation());
                return;
            }

            // Generic temporary ice may be thawed only when it captured water
            // beneath itself. Ability-specific ice was already delegated to
            // its owner above. Other foreign temporary ice remains untouched.
            if (isIce(visibleData.getMaterial())
                    && ElementalAbility.isWater(tb.getState().getBlockData().getMaterial())) {
                tb.revertBlock();
                playWaterbendingSound(b.getLocation());
            }
            return;
        } else if (isWater(b)) {
            // Figure out what to do here also.
        } else if (isMeltableIce(b)) {
            if (b.getWorld().getEnvironment() == World.Environment.NETHER) {
                if (this.allowMeltFlow) {
                    b.setType(Material.AIR);
                } else {
                    this.trackMelted(b, new TempBlock(b, Material.AIR.createBlockData(), MELT_REVERT_MILLIS, this));
                }
            } else {
                if (this.allowMeltFlow) {
                    b.setType(Material.WATER);
                    b.setBlockData(GeneralMethods.getWaterData(0));
                } else {
                    this.trackMelted(b, new TempBlock(b, Material.WATER.createBlockData(), MELT_REVERT_MILLIS, this));
                }
            }

            if (this.allowMeltFlow) this.melted_blocks.addIfAbsent(b);
        } else if (b.getType() == Material.SNOW_BLOCK || b.getType() == Material.SNOW) {
            final BlockData melted = meltedData(b.getBlockData(), b.getWorld());
            if (melted == null) return;
            this.trackMelted(b, new TempBlock(b, melted, MELT_REVERT_MILLIS, this));
        }
        playWaterbendingSound(b.getLocation());
    }

    private void trackMelted(final Block block, final TempBlock layer) {
        if (block == null || layer == null || layer.isReverted()) return;
        this.meltLayers.put(block, layer);
        this.melted_blocks.addIfAbsent(block);
        layer.setRevertTask(() -> {
            if (this.meltLayers.remove(block, layer)) this.melted_blocks.remove(block);
        });
    }

    private static BlockData meltedData(final BlockData visibleData, final World world) {
        if (visibleData == null) return null;
        if (visibleData.getMaterial() == Material.SNOW) {
            if (!(visibleData instanceof Snow snow) || snow.getLayers() <= snow.getMinimumLayers()) {
                return Material.AIR.createBlockData();
            }
            final Snow thinner = snow.clone();
            thinner.setLayers(snow.getLayers() - 1);
            return thinner;
        }
        if (visibleData.getMaterial() == Material.SNOW_BLOCK) {
            return Material.AIR.createBlockData();
        }
        if (isIce(visibleData.getMaterial())) {
            return world != null && world.getEnvironment() == World.Environment.NETHER
                    ? Material.AIR.createBlockData() : Material.WATER.createBlockData();
        }
        return null;
    }

    private static boolean isRegisteredMeltable(final Block block) {
        if (!TempBlock.isTempBlock(block)) return isMeltableIce(block) || isSnow(block);
        final Material material = block.getBlockData().getMaterial();
        return isIce(material) || isSnow(material);
    }

    public void revertFrozenBlocks() {
        if (this.active_types.contains(PhaseChangeType.FREEZE) || this.active_types.contains(PhaseChangeType.CUSTOM)) {
            for (final TempBlock tb : this.blocks) {
                tb.revertBlock();
                if (tb.isReverted()) untrackFrozen(tb);
            }
            this.blocks.clear();
        }
    }

    public void revertMeltedBlocks() {
        // Melt layers have their own bounded TempBlock lifetime. Ability
        // removal must not immediately roll them back (or leave the no-expiry
        // variants behind forever); the client lifecycle is the visual answer.
        this.meltLayers.clear();
        this.melted_blocks.clear();
    }

    @Override
    public void remove() {
        super.remove();
        this.revertFrozenBlocks();
        this.revertMeltedBlocks();
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
    public long getCooldown() {
        return 0;
    }

    @Override
    public String getName() {
        return "PhaseChange";
    }

    @Override
    public Location getLocation() {
        return null;
    }

    public int getDepth() {
        return this.depth;
    }

    public void setDepth(final int value) {
        this.depth = value;
    }

    public double getSourceRange() {
        return this.sourceRange;
    }

    public void setSourceRange(final double value) {
        this.sourceRange = value;
    }

    public double getFreezeControlRadius() {
        return this.controlRadius;
    }

    public void setFreezeControlRadius(final double value) {
        this.controlRadius = value;
    }

    public double getMeltRadius() {
        return this.meltRadius;
    }

    public CopyOnWriteArrayList<TempBlock> getFrozenBlocks() {
        return this.blocks;
    }

    public CopyOnWriteArrayList<Block> getMeltedBlocks() {
        return this.melted_blocks;
    }

    public List<PhaseChangeType> getActiveTypes() {
        return this.active_types;
    }

    public enum PhaseChangeType {
        FREEZE, MELT, CUSTOM;

        @Override
        public String toString() {
            if (this == FREEZE) {
                return "Freeze";
            } else if (this == MELT) {
                return "Melt";
            } else if (this == CUSTOM) {
                return "Custom";
            }
            return "";
        }
    }
}
