package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionUtil;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.policies.removal.*;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;

import java.util.*;
import java.util.stream.Collectors;

public class MudSurge extends EarthAbility implements AddonAbility {
    public static int surgeInterval = 300;
    public static int mudPoolRadius = 2;
    private static List<String> mudTypes;
    private final List<Block> mudArea = new ArrayList<>();
    private final List<TempBlock> mudBlocks = new ArrayList<>();
    private final List<Player> blind = new ArrayList<>();
    private final List<Entity> affectedEntities = new ArrayList<>();
    private final List<TempFallingBlock> fallingBlocks = new ArrayList<>();
    private final Random rand = new Random();
    public boolean started = false;
    private int prepareRange;
    private int blindChance;
    private int blindTicks;
    private boolean multipleHits;
    private boolean instantSource;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private int waves;
    private int waterSearchRadius;
    private boolean wetSource;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute("CollisionRadius")
    private double collisionRadius;
    private CompositeRemovalPolicy removalPolicy;
    private Block source;
    private int wavesOnTheRun = 0;
    private boolean mudFormed = false;
    private boolean doNotSurge = false;
    private ListIterator<Block> mudAreaItr;

    public MudSurge(Player player) {
        super(player);

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, MudSurge.class)) {
            MudSurge ms = getAbility(player, MudSurge.class);
            if (!ms.hasStarted()) {
                ms.remove();
            } else {
                return;
            }
        }

        this.removalPolicy = new CompositeRemovalPolicy(this,
                new CannotBendRemovalPolicy(this.bPlayer, this, true, true),
                new IsOfflineRemovalPolicy(this.player),
                new IsDeadRemovalPolicy(this.player),
                new OutOfRangeRemovalPolicy(this.player, 25.0, () -> this.source.getLocation()),
                new SwappedSlotsRemovalPolicy<>(bPlayer, MudSurge.class)
        );

        setFields();

        if (getSource()) {
            start();
            if (!isRemoved()) {
                loadMudPool();
            }
        }
    }

    public static boolean isSurgeBlock(Block block) {
        if (block.getType() != Material.PACKED_MUD) {
            return false;
        }

        for (MudSurge surge : CoreAbility.getAbilities(MudSurge.class)) {
            if (surge.mudArea.contains(block)) {
                return true;
            }
        }

        return false;
    }

    // Returns true if the event should be cancelled.
    public static boolean onFallDamage(Player player) {
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null || !bPlayer.hasElement(Element.EARTH)) {
            return false;
        }

        boolean fallDamage = JedCoreConfig.getConfig(bPlayer).getBoolean("Abilities.Earth.MudSurge.AllowFallDamage");
        if (fallDamage) {
            return false;
        }

        Block block = player.getLocation().clone().subtract(0, 0.1, 0).getBlock();
        return isSurgeBlock(block);
    }

    public static void mudSurge(Player player) {
        if (!hasAbility(player, MudSurge.class))
            return;

        getAbility(player, MudSurge.class).startSurge();
    }

    public static int getSurgeInterval() {
        return surgeInterval;
    }

    public static void setSurgeInterval(int surgeInterval) {
        MudSurge.surgeInterval = surgeInterval;
    }

    public static int getMudPoolRadius() {
        return mudPoolRadius;
    }

    public static void setMudPoolRadius(int mudPoolRadius) {
        MudSurge.mudPoolRadius = mudPoolRadius;
    }

    public static boolean isMud(Block block) {
        return mudTypes.contains(block.getType().name());
    }

    public void setFields() {
        this.removalPolicy.load(JedCoreConfig.getConfig(this.bPlayer));

        prepareRange = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.MudSurge.SourceRange");
        blindChance = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.MudSurge.BlindChance");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.MudSurge.Damage");
        waves = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.MudSurge.Waves");
        waterSearchRadius = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.MudSurge.WaterSearchRadius");
        wetSource = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.MudSurge.WetSourceOnly");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.MudSurge.Cooldown");
        blindTicks = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.MudSurge.BlindTicks");
        multipleHits = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.MudSurge.MultipleHits");
        instantSource = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.MudSurge.InstantSource");
        collisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.MudSurge.CollisionRadius");
        mudTypes = JedCoreConfig.getConfig(this.bPlayer).getStringList("Abilities.Earth.MudSurge.MudBlocks");
    }

    @Override
    public void progress() {
        if (removalPolicy.shouldRemove()) {
            remove();
            return;
        }

        long lastSurgeTime = 0;
        if (mudFormed && started && System.currentTimeMillis() > lastSurgeTime + surgeInterval) {
            surge();
            affect();
            if (TempFallingBlock.getFromAbility(this).isEmpty()) {
                remove();
                return;
            }
            return;
        }

        if (!mudFormed) {
            createMudPool();
        }
    }

    private boolean getSource() {
        Block block = getMudSourceBlock(prepareRange);

        if (block != null) {
            if (isMud(block)) {
                boolean water = true;

                if (wetSource) {
                    water = false;
                    List<Block> nearby = GeneralMethods.getBlocksAroundPoint(block.getLocation(), waterSearchRadius);

                    for (Block b : nearby) {
                        if (b.getType() == Material.WATER) {
                            water = true;
                            break;
                        }
                    }
                }

                if (water) {
                    this.source = block;
                    return true;
                }
            }
        }

        return false;
    }

    public void setSource(Block source) {
        this.source = source;
    }

    private void startSurge() {
        started = true;
        this.bPlayer.addCooldown(this);

        // Clear out the policies that only apply while sourcing.
        this.removalPolicy.removePolicyType(IsDeadRemovalPolicy.class);
        this.removalPolicy.removePolicyType(OutOfRangeRemovalPolicy.class);
        this.removalPolicy.removePolicyType(SwappedSlotsRemovalPolicy.class);
    }

    private boolean hasStarted() {
        return this.started;
    }

    private Block getMudSourceBlock(int range) {
        Block testBlock = GeneralMethods.getTargetedLocation(player, range, ElementalAbility.getTransparentMaterials()).getBlock();
        if (isMud(testBlock))
            return testBlock;

        Location loc = player.getEyeLocation();
        Vector dir = player.getEyeLocation().getDirection().clone().normalize();

        for (int i = 0; i <= range; i++) {
            Block block = loc.clone().add(dir.clone().multiply(i == 0 ? 1 : i)).getBlock();
            if (RegionProtection.isRegionProtected(player, block.getLocation(), this))
                continue;

            if (isMud(block))
                return block;
        }

        return null;
    }

    private void createMud(Block block) {
        mudBlocks.add(new TempBlock(block, Material.PACKED_MUD.createBlockData()));
    }

    private void loadMudPool() {
        List<Location> area = GeneralMethods.getCircle(source.getLocation(), mudPoolRadius, 3, false, true, 0);

        for (Location l : area) {
            Block b = l.getBlock();

            if (isMud(b)) {
                if (isTransparent(b.getRelative(BlockFace.UP))) {
                    boolean water = true;

                    if (wetSource) {
                        water = false;
                        List<Block> nearby = GeneralMethods.getBlocksAroundPoint(l, waterSearchRadius);

                        for (Block block : nearby) {
                            if (block.getType() == Material.WATER) {
                                water = true;
                                break;
                            }
                        }
                    }

                    if (water) {
                        mudArea.add(b);
                        playMudbendingSound(b.getLocation());
                    }
                }
            }
        }

        Collections.shuffle(mudArea);
        mudAreaItr = mudArea.listIterator();
    }

    private void createMudPool() {
        if (instantSource) {
            for (Block b : mudArea) {
                createMud(b);
            }
            mudFormed = true;
            return;
        }
        if (!mudAreaItr.hasNext()) {
            mudFormed = true;
            return;
        }

        Block b = mudAreaItr.next();

        if (b != null)
            createMud(b);
    }

    private void revertMudPool() {
        for (TempBlock tb : mudBlocks)
            tb.revertBlock();

        mudBlocks.clear();
    }

    private void surge() {
        if (wavesOnTheRun >= waves) {
            doNotSurge = true;
            return;
        }

        if (doNotSurge)
            return;

        for (TempBlock tb : mudBlocks) {
            Vector direction = GeneralMethods.getDirection(tb.getLocation().add(0, 1, 0), GeneralMethods.getTargetedLocation(player, 30)).multiply(0.07);

            double x = rand.nextDouble() / 5;
            double z = rand.nextDouble() / 5;

            x = (rand.nextBoolean()) ? -x : x;
            z = (rand.nextBoolean()) ? -z : z;

            fallingBlocks.add(new TempFallingBlock(tb.getLocation().add(0.5, 1, 0.5), Material.PACKED_MUD.createBlockData(), direction.clone().add(new Vector(x, 0.2, z)), this));

            playMudbendingSound(tb.getLocation());
        }

        wavesOnTheRun++;
    }

    private void affect() {
        for (TempFallingBlock tfb : TempFallingBlock.getFromAbility(this)) {
            FallingBlock fb = tfb.getFallingBlock();
            if (fb.isDead()) {
                tfb.remove();
                continue;
            }

            for (Entity e : GeneralMethods.getEntitiesAroundPoint(fb.getLocation(), 1.5)) {
                if (fb.isDead()) {
                    tfb.remove();
                    continue;
                }
                if (RegionProtection.isRegionProtected(this, e.getLocation()) || ((e instanceof Player) && Commands.invincible.contains(e.getName()))) {
                    continue;
                }

                if (e instanceof LivingEntity) {
                    if (this.multipleHits || !this.affectedEntities.contains(e)) {
                        DamageHandler.damageEntity(e, damage, this);
                        if (!this.multipleHits) {
                            this.affectedEntities.add(e);
                        }
                    }

                    if (e instanceof Player) {
                        if (e.getEntityId() == player.getEntityId())
                            continue;

                        if (rand.nextInt(100) < blindChance && !blind.contains(e)) {
                            ((Player) e).addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, this.blindTicks, 2));
                        }

                        blind.add((Player) e);
                    }

                    GeneralMethods.setVelocity(this, e, fb.getVelocity().multiply(0.8));
                    tfb.remove();
                }
            }
        }
    }

    @Override
    public void remove() {
        revertMudPool();
        super.remove();
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
        return null;
    }

    @Override
    public List<Location> getLocations() {
        return fallingBlocks.stream().map(TempFallingBlock::getLocation).collect(Collectors.toList());
    }

    @Override
    public void handleCollision(Collision collision) {
        CollisionUtil.handleFallingBlockCollisions(collision, fallingBlocks);
    }

    @Override
    public double getCollisionRadius() {
        return collisionRadius;
    }

    public void setCollisionRadius(double collisionRadius) {
        this.collisionRadius = collisionRadius;
    }

    @Override
    public String getName() {
        return "MudSurge";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.MudSurge.Description");
    }

    public int getPrepareRange() {
        return prepareRange;
    }

    public void setPrepareRange(int prepareRange) {
        this.prepareRange = prepareRange;
    }

    public int getBlindChance() {
        return blindChance;
    }

    public void setBlindChance(int blindChance) {
        this.blindChance = blindChance;
    }

    public int getBlindTicks() {
        return blindTicks;
    }

    public void setBlindTicks(int blindTicks) {
        this.blindTicks = blindTicks;
    }

    public boolean isMultipleHits() {
        return multipleHits;
    }

    public void setMultipleHits(boolean multipleHits) {
        this.multipleHits = multipleHits;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public int getWaves() {
        return waves;
    }

    public void setWaves(int waves) {
        this.waves = waves;
    }

    public int getWaterSearchRadius() {
        return waterSearchRadius;
    }

    public void setWaterSearchRadius(int waterSearchRadius) {
        this.waterSearchRadius = waterSearchRadius;
    }

    public boolean isWetSource() {
        return wetSource;
    }

    public void setWetSource(boolean wetSource) {
        this.wetSource = wetSource;
    }

    public CompositeRemovalPolicy getRemovalPolicy() {
        return removalPolicy;
    }

    public void setRemovalPolicy(CompositeRemovalPolicy removalPolicy) {
        this.removalPolicy = removalPolicy;
    }

    public int getWavesOnTheRun() {
        return wavesOnTheRun;
    }

    public void setWavesOnTheRun(int wavesOnTheRun) {
        this.wavesOnTheRun = wavesOnTheRun;
    }

    public boolean isMudFormed() {
        return mudFormed;
    }

    public void setMudFormed(boolean mudFormed) {
        this.mudFormed = mudFormed;
    }

    public boolean isDoNotSurge() {
        return doNotSurge;
    }

    public void setDoNotSurge(boolean doNotSurge) {
        this.doNotSurge = doNotSurge;
    }

    @Override
    public boolean isStarted() {
        return started;
    }

    public void setStarted(boolean started) {
        this.started = started;
    }

    public List<Block> getMudArea() {
        return mudArea;
    }

    public ListIterator<Block> getMudAreaItr() {
        return mudAreaItr;
    }

    public void setMudAreaItr(ListIterator<Block> mudAreaItr) {
        this.mudAreaItr = mudAreaItr;
    }

    public List<TempBlock> getMudBlocks() {
        return mudBlocks;
    }

    public List<Player> getBlind() {
        return blind;
    }

    public List<Entity> getAffectedEntities() {
        return affectedEntities;
    }

    public List<TempFallingBlock> getFallingBlocks() {
        return fallingBlocks;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.MudSurge.Enabled");
    }
}
