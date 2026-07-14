package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.policies.removal.*;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.earthbending.passive.DensityShift;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempFallingBlock;

public class EarthLine extends EarthAbility implements AddonAbility {

    private Location location;
    private Location endLocation;
    private Block sourceBlock;
    private Material sourceType;
    private boolean progressing;
    private boolean hitted;
    private int goOnAfterHit;
    private long removalTime = -1;

    private long useCooldown;
    private long prepareCooldown;
    @Attribute(Attribute.DURATION)
    private long maxDuration;
    @Attribute(Attribute.RANGE)
    private double range;
    @Attribute(Attribute.SELECT_RANGE)
    private double prepareRange;
    private double sourceKeepRange;
    @Attribute(Attribute.RADIUS)
    private int affectingRadius;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.KNOCKBACK)
    private double knockback;
    private boolean airBlocksUnder;
    private boolean allowChangeDirection;
    private CompositeRemovalPolicy removalPolicy;


    public EarthLine(Player player) {
        super(player);

        if (!isEnabled()) return;

        EarthLine existing = getAbility(player, EarthLine.class);
        if (existing != null && !existing.progressing) {
            existing.reselectSource();
            return;
        }

        if (!bPlayer.canBend(this)) {
            return;
        }
        goOnAfterHit = 1;

        setFields();
        if (prepare()) {
            start();
        }
    }

    private static Location getTargetLocation(Player player) {
        double range = JedCoreConfig.getConfig(BendingPlayer.getBendingPlayer(player)).getInt("Abilities.Earth.EarthLine.Range");
        Entity target = GeneralMethods.getTargetedEntity(player, range, player.getNearbyEntities(range, range, range));
        Location location;
        if (target == null) {
            location = GeneralMethods.getTargetedLocation(player, range);
        } else {
            location = ((LivingEntity) target).getEyeLocation();
        }
        return location;
    }

    public static void shootLine(Player player) {
        if (hasAbility(player, EarthLine.class)) {
            EarthLine el = getAbility(player, EarthLine.class);
            if (!el.progressing) {
                el.shootLine(getTargetLocation(player));
            }
        }
    }

    public static boolean prepareLine(Player player) {
        EarthLine existing = getAbility(player, EarthLine.class);
        if (existing != null && !existing.progressing) {
            return existing.reselectSource();
        }
        new EarthLine(player);
        return hasAbility(player, EarthLine.class);
    }

    private boolean reselectSource() {
        Block oldSource = sourceBlock;
        Material oldType = sourceType;
        if (oldSource != null && oldType != null) {
            oldSource.setType(oldType);
        }
        if (selectSource()) {
            if (oldSource != null && oldType != null && !oldSource.equals(sourceBlock)) {
                oldSource.setType(oldType);
            }
            return true;
        } else {
            sourceBlock = oldSource;
            sourceType = oldType;
            if (sourceBlock != null) {
                focusBlock();
                return true;
            }
        }
        return false;
    }

    public void setFields() {
        this.removalPolicy = new CompositeRemovalPolicy(this,
                new CannotBendRemovalPolicy(this.bPlayer, this, true, true),
                new IsOfflineRemovalPolicy(this.player),
                new IsDeadRemovalPolicy(this.player),
                new SwappedSlotsRemovalPolicy<>(bPlayer, EarthLine.class)
        );

        this.removalPolicy.load(JedCoreConfig.getConfig(this.bPlayer));

        useCooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthLine.Cooldown");
        prepareCooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthLine.PrepareCooldown");
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthLine.Range");
        prepareRange = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthLine.PrepareRange");
        sourceKeepRange = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthLine.SourceKeepRange");
        affectingRadius = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthLine.AffectingRadius");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthLine.Damage");
        knockback = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthLine.Knockback");
        allowChangeDirection = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthLine.AllowChangeDirection");
        maxDuration = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthLine.MaxDuration");
        airBlocksUnder = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthLine.AirBlocksUnderLine");
    }

    public boolean prepare() {
        if (hasAbility(player, EarthLine.class)) {
            EarthLine el = getAbility(player, EarthLine.class);
            if (!el.progressing) {
                el.remove();
            }
        }
        return selectSource();
    }

    private boolean selectSource() {
        Block block = getEarthSourceBlock(prepareRange);
        //Block block = BlockSource.getEarthSourceBlock(player, prepareRange, ClickType.SHIFT_DOWN);
        if (block != null && isEarthbendable(block.getType(), true, true, true)) {
            sourceBlock = block;
            focusBlock();
            return true;
        } else {
            return false;
        }
    }

    private void focusBlock() {
        if (sourceBlock.getType() == Material.SAND) {
            if (DensityShift.isPassiveSand(this.sourceBlock)) {
                DensityShift.revertSand(this.sourceBlock);
                this.sourceType = this.sourceBlock.getType();
            } else {
                sourceType = Material.SAND;
            }
            sourceBlock.setType(Material.SANDSTONE);
        } else if (sourceBlock.getType() == Material.STONE) {
            sourceType = sourceBlock.getType();
            sourceBlock.setType(Material.COBBLESTONE);
        } else {
            sourceType = sourceBlock.getType();
            sourceBlock.setType(Material.STONE);
        }
        location = sourceBlock.getLocation();
    }

    private void unfocusBlock() {
        sourceBlock.setType(sourceType);
    }

    private void breakSourceBlock() {
        sourceBlock.setType(sourceType);
        new RegenTempBlock(sourceBlock, Material.AIR, Material.AIR.createBlockData(), 5000L);
    }

    @Override
    public void remove() {
        if (sourceBlock != null && sourceType != null) {
            sourceBlock.setType(sourceType);
        }
        super.remove();
    }

    public void shootLine(Location endLocation) {
        if (useCooldown != 0 && bPlayer.getCooldown(this.getName()) < useCooldown)
            bPlayer.addCooldown(this, useCooldown);
        if (maxDuration > 0) removalTime = System.currentTimeMillis() + maxDuration;
        this.endLocation = endLocation;
        progressing = true;
        breakSourceBlock();
        playEarthbendingSound(sourceBlock.getLocation());
    }

    private boolean sourceOutOfRange() {
        if (sourceBlock == null) return true;

        return sourceBlock.getLocation().add(0.5, 0.5, 0.5).distanceSquared(player.getLocation()) > sourceKeepRange * sourceKeepRange || sourceBlock.getWorld() != player.getWorld();
    }

    public void progress() {
        if (!progressing) {
            if (sourceOutOfRange()) {
                unfocusBlock();
                remove();
            }
            return;
        }

        if (removalPolicy.shouldRemove()) {
            remove();
            return;
        }

        if (sourceBlock == null || RegionProtection.isRegionProtected(this, location)) {
            remove();
            return;
        }

        if (removalTime > -1 && System.currentTimeMillis() > removalTime) {
            remove();
            return;
        }

        if (sourceOutOfRange()) {
            remove();
            return;
        }

        if (RegionProtection.isRegionProtected(player, location, this)) {
            remove();
            return;
        }

        if (allowChangeDirection && player.isSneaking() && bPlayer.getBoundAbilityName().equalsIgnoreCase("EarthLine")) {
            endLocation = getTargetLocation(player);
        }

        double x1 = endLocation.getX();
        double z1 = endLocation.getZ();
        double x0 = sourceBlock.getX() + 0.5;
        double z0 = sourceBlock.getZ() + 0.5;
        Vector looking = new Vector(x1 - x0, 0.0D, z1 - z0);
        Vector push = new Vector(x1 - x0, 0.34999999999999998D, z1 - z0);
        if (location.distance(sourceBlock.getLocation()) < range) {
            Material cloneType = location.getBlock().getType();
            Location locationYUP = location.getBlock().getLocation().clone().add(0.5, 0.1, 0.5);

            playEarthbendingSound(location);

            Block lineBlock = location.getBlock();
            if (airBlocksUnder) {
                new RegenTempBlock(lineBlock, Material.AIR, Material.AIR.createBlockData(), 700L);
            }

            TempFallingBlock fallingBlock = new TempFallingBlock(locationYUP, cloneType.createBlockData(), new Vector(0.0, 0.35, 0.0), this);
            if (airBlocksUnder) {
                fallingBlock.setOnPlace(ignored -> RegenTempBlock.revert(lineBlock));
            }

            location.add(looking.normalize());

            if (!climb()) {
                remove();
                return;
            }

            if (hitted) {
                if (goOnAfterHit != 0) {
                    goOnAfterHit--;
                } else {
                    remove();
                    return;
                }
            } else {
                for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location.clone().add(0, 1, 0), affectingRadius)) {
                    if (RegionProtection.isRegionProtected(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(entity.getName()))) {
                        return;
                    }
                    if ((entity instanceof LivingEntity) && entity.getEntityId() != player.getEntityId()) {
                        GeneralMethods.setVelocity(this, entity, push.normalize().multiply(knockback));
                        DamageHandler.damageEntity(entity, damage, this);
                        hitted = true;
                    }
                }
            }
        } else {
            remove();
            return;
        }
        if (!isEarthbendable(player, location.getBlock()) && !isTransparent(location.getBlock())) {
            remove();
        }
    }

    private boolean climb() {
        Block above = location.getBlock().getRelative(BlockFace.UP);

        if (!isTransparent(above)) {
            // Attempt to climb since the current location has a block above it.
            location.add(0, 1, 0);
            above = location.getBlock().getRelative(BlockFace.UP);

            // The new location must be earthbendable and have something transparent above it.
            return isEarthbendable(location.getBlock()) && isTransparent(above);
        } else if (isTransparent(location.getBlock())) {
            // Attempt to fall since the current location is transparent and the above block was transparent.
            location.add(0, -1, 0);

            // The new location must be earthbendable and we already know the block above it is transparent.
            return isEarthbendable(location.getBlock());
        }

        return true;
    }

    @Override
    public long getCooldown() {
        return useCooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public String getName() {
        return "EarthLine";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.EarthLine.Description");
    }

    public Location getEndLocation() {
        return endLocation;
    }

    public void setEndLocation(Location endLocation) {
        this.endLocation = endLocation;
    }

    public Block getSourceBlock() {
        return sourceBlock;
    }

    public void setSourceBlock(Block sourceBlock) {
        this.sourceBlock = sourceBlock;
    }

    public Material getSourceType() {
        return sourceType;
    }

    public void setSourceType(Material sourceType) {
        this.sourceType = sourceType;
    }

    public boolean isProgressing() {
        return progressing;
    }

    public void setProgressing(boolean progressing) {
        this.progressing = progressing;
    }

    public int getGoOnAfterHit() {
        return goOnAfterHit;
    }

    public void setGoOnAfterHit(int goOnAfterHit) {
        this.goOnAfterHit = goOnAfterHit;
    }

    public long getRemovalTime() {
        return removalTime;
    }

    public void setRemovalTime(long removalTime) {
        this.removalTime = removalTime;
    }

    public long getUseCooldown() {
        return useCooldown;
    }

    public void setUseCooldown(long useCooldown) {
        this.useCooldown = useCooldown;
    }

    public long getPrepareCooldown() {
        return prepareCooldown;
    }

    public void setPrepareCooldown(long prepareCooldown) {
        this.prepareCooldown = prepareCooldown;
    }

    public long getMaxDuration() {
        return maxDuration;
    }

    public void setMaxDuration(long maxDuration) {
        this.maxDuration = maxDuration;
    }

    public double getRange() {
        return range;
    }

    public void setRange(double range) {
        this.range = range;
    }

    public double getPrepareRange() {
        return prepareRange;
    }

    public void setPrepareRange(double prepareRange) {
        this.prepareRange = prepareRange;
    }

    public double getSourceKeepRange() {
        return sourceKeepRange;
    }

    public void setSourceKeepRange(double sourceKeepRange) {
        this.sourceKeepRange = sourceKeepRange;
    }

    public int getAffectingRadius() {
        return affectingRadius;
    }

    public void setAffectingRadius(int affectingRadius) {
        this.affectingRadius = affectingRadius;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public boolean isAllowChangeDirection() {
        return allowChangeDirection;
    }

    public void setAllowChangeDirection(boolean allowChangeDirection) {
        this.allowChangeDirection = allowChangeDirection;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthLine.Enabled");
    }
}
