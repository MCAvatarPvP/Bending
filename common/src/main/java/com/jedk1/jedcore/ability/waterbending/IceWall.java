package com.jedk1.jedcore.ability.waterbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.*;
import com.projectkorra.projectkorra.airbending.AirBlast;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.earthbending.EarthSmash;
import com.projectkorra.projectkorra.firebending.FireBlast;
import com.projectkorra.projectkorra.firebending.FireBlastCharged;
import com.projectkorra.projectkorra.firebending.lightning.Lightning;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.block.TempBlockSync;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.Torrent;
import com.projectkorra.projectkorra.waterbending.ice.IceBlast;

import java.util.*;

public class IceWall extends IceAbility implements AddonAbility {

    private static final Set<Block> AFFECTED_BLOCK_CACHE = new HashSet<>();
    public static List<IceWall> instances = new ArrayList<>();
    public static boolean stackable;
    public static boolean lifetimeEnabled;
    public static long lifetimeTime;
    private final Set<Block> affectedBlockSet = new HashSet<>();
    private final List<Block> lastBlocks = new ArrayList<>();
    private final List<TempBlock> tempBlocks = new ArrayList<>();
    public int torrentDamage;
    public int torrentFreezeDamage;
    public int iceBlastDamage;
    public int fireBlastDamage;
    public int fireBlastChargedDamage;
    public int lightningDamage;
    public int combustionDamage;
    public int earthSmashDamage;
    public int airBlastDamage;
    public boolean isWallDoneFor = false;
    public List<Block> affectedBlocks = new ArrayList<>();
    @Attribute(Attribute.HEIGHT)
    private int maxHeight;
    private int minHeight;
    private int width;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute("Health")
    private int maxHealth;
    private int minHealth;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    private double radius;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private boolean allowSnow;
    private boolean canBreakWalls;
    private double breakHitbox;
    private boolean rising = false;
    private long lastDamageTime = 0;
    private long lifetime = 0;
    private int wallHealth;
    private int tankedDamage;
    private boolean collapsePending;
    private boolean pendingCollapseForceful;
    private Player pendingCollapsePlayer;
    private CoreAbility pendingCollapseCause;

    public IceWall(Player player) {
        super(player);
        if (!bPlayer.canBendIgnoreCooldowns(this) || !bPlayer.canIcebend()) {
            return;
        }

        setFields();
        Block b = getSourceBlock(player, (int) (range * getNightFactor(player.getWorld())));

        if (b == null)
            return;

        else {
            for (IceWall iw : instances) {
                if (iw.affectedBlockSet.contains(b)) {
                    iw.collapse(player, false);
                    return;
                }
            }

            // A predicting client intentionally has no CoreAbility instance
            // for another player's wall. Paper will collapse that real
            // instance; do not reinterpret its visible packed ice as a source
            // and construct a second client-only wall over it.
            if (TempBlockSync.hasAuthoritativeEffect(b, this.getName())) {
                return;
            }

            if (isWaterbendable(b)) {
                if (!bPlayer.canBend(this)) {
                    return;
                }

                // Wall health influences when its TempBlocks are removed, so client and
                // server cannot draw independently from wall-clock-seeded RNG. Derive
                // the roll from the shared source/player instead.
                long seed = player.getUniqueId().getMostSignificantBits()
                        ^ player.getUniqueId().getLeastSignificantBits()
                        ^ ((long) b.getX() * 341873128712L)
                        ^ ((long) b.getY() * 132897987541L)
                        ^ ((long) b.getZ() * 42317861L);
                Random deterministic = new Random(seed);
                wallHealth = (int) (((deterministic.nextInt((maxHealth - minHealth) + 1)) + minHealth) * getNightFactor(player.getWorld()));
                loadAffectedBlocks(player, b);
                lifetime = System.currentTimeMillis() + lifetimeTime;
            }
        }
        start();
    }

    public static void removeDeadInstances() {
        for (int i = 0; i < instances.size(); i++) {
            IceWall iw = instances.get(i);
            if (iw.isWallDoneFor) {
                iw.clearAffectedBlocks();
                instances.remove(i);
            }
        }
    }

    public static void collisionDamage(Entity entity, double travelledDistance, Vector difference, Player instigator) {
        Location entityLocation = entity.getLocation();
        double maxDistanceSquared = 4;
        for (IceWall iw : IceWall.instances) {
            for (Block b : iw.affectedBlockSet) {
                Location blockLocation = b.getLocation();
                if (entityLocation.getWorld() == blockLocation.getWorld() && entityLocation.distanceSquared(blockLocation) < maxDistanceSquared) {
                    double damage = ((travelledDistance - 5.0) < 0 ? 0 : travelledDistance - 5.0) / (difference.length());
                    iw.damageWall(instigator, (int) damage);
                }
            }
        }
    }

    public static boolean checkExplosions(Location location, Entity entity) {
        double maxDistanceSquared = 4;
        for (IceWall iw : IceWall.instances) {
            for (Block b : iw.affectedBlockSet) {
                Location blockLocation = b.getLocation();
                if (location.getWorld() == blockLocation.getWorld() && location.distanceSquared(blockLocation) < maxDistanceSquared) {

                    for (Entity e : GeneralMethods.getEntitiesAroundPoint(location, 3)) {
                        if (e instanceof LivingEntity) {
                            DamageHandler.damageEntity(e, 7, iw);
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isIceWallBlock(Block block) {
        return AFFECTED_BLOCK_CACHE.contains(block);
    }

    public static void removeAll() {
        Iterator<IceWall> it = instances.iterator();
        while (it.hasNext()) {
            it.next().remove();
            it.remove();
        }
    }

    public static void progressAll() {
		/*
		for (IceWall iw : IceWall.instances) {
			iw.progress();
		}
		*/

        ListIterator<IceWall> iwli = IceWall.instances.listIterator();

        while (iwli.hasNext()) {
            IceWall iw = iwli.next();
            for (Torrent t : getAbilities(Torrent.class)) {
                if (t.getLocation() == null) continue;
                for (int i = 0; i < t.getLaunchedBlocks().size(); i++) {
                    TempBlock tb = t.getLaunchedBlocks().get(i);

                    for (Block ice : iw.affectedBlockSet) {
                        Location iceLocation = ice.getLocation();
                        Location tbLocation = tb.getLocation();
                        if (iceLocation.getWorld() == tbLocation.getWorld() && iceLocation.distanceSquared(tbLocation) <= 4) {
                            if (t.isFreeze())
                                iw.damageWall(t.getPlayer(), (int) (iw.torrentFreezeDamage * getNightFactor(ice.getWorld())));
                            else
                                iw.damageWall(t.getPlayer(), (int) (iw.torrentDamage * getNightFactor(ice.getWorld())));

                            if (!iw.isWallDoneFor)
                                t.setFreeze(false);
                        }
                    }
                }
            }

            for (IceBlast ib : getAbilities(IceBlast.class)) {
                if (ib.getLocation() == null) continue;
                for (Block ice : iw.affectedBlockSet) {
                    if (ib.source == null)
                        break;

                    Location iceLocation = ice.getLocation();
                    Location sourceLocation = ib.source.getLocation();
                    if (iceLocation.getWorld() == sourceLocation.getWorld() && iceLocation.distanceSquared(sourceLocation) <= 4) {
                        iw.damageWall(ib.getPlayer(), (int) (iw.iceBlastDamage * getNightFactor(ice.getWorld())));

                        if (!iw.isWallDoneFor)
                            ib.remove();
                    }
                }
            }

            for (FireBlastCharged fb : getAbilities(FireBlastCharged.class)) {
                if (fb.getLocation() == null) continue;
                Location fireBlastChargedLocation = fb.getLocation();
                for (Block ice : iw.affectedBlockSet) {
                    Location iceLocation = ice.getLocation();
                    if (iceLocation.getWorld() == fireBlastChargedLocation.getWorld() && fireBlastChargedLocation.distanceSquared(iceLocation) <= 2.25) {
                        iw.damageWall(fb.getPlayer(), iw.fireBlastChargedDamage);

                        if (!iw.isWallDoneFor) fb.remove();
                    }
                }
            }

            for (FireBlast fb : getAbilities(FireBlast.class)) {
                if (fb.getLocation() == null) continue;
                Location fireBlastLocation = fb.getLocation();
                for (Block ice : iw.affectedBlockSet) {
                    Location iceLocation = ice.getLocation();
                    if (iceLocation.getWorld() == fireBlastLocation.getWorld() && fireBlastLocation.distanceSquared(iceLocation) <= 2.25) {
                        iw.damageWall(fb.getPlayer(), iw.fireBlastDamage);

                        if (!iw.isWallDoneFor) fb.remove();
                    }
                }
            }

            for (EarthSmash es : getAbilities(EarthSmash.class)) {
                if (es.getLocation() == null) continue;
                if (es.getState() == EarthSmash.State.SHOT) {
                    for (Block ice : iw.affectedBlockSet) {
                        Location iceLocation = ice.getLocation();
                        for (int j = 0; j < es.getBlocks().size(); j++) {
                            Block b = es.getBlocks().get(j);
                            Location blockLocation = b.getLocation();
                            if (iceLocation.getWorld() == blockLocation.getWorld() && blockLocation.distanceSquared(iceLocation) <= 4) {
                                iw.damageWall(es.getPlayer(), iw.earthSmashDamage);

                                if (!iw.isWallDoneFor) {
                                    for (Block block : es.getBlocksIncludingInner()) {
                                        if (block != null && !ElementalAbility.isAir(block.getType())) {
                                            ParticleEffect.BLOCK_CRACK.display(block.getLocation(), 5, 0, 0, 0, 0, block.getBlockData().clone());
                                        }
                                    }
                                    es.remove();
                                }
                            }
                        }
                    }
                }
            }

            for (Lightning l : getAbilities(Lightning.class)) {
                for (Lightning.Arc arc : l.getArcs()) {
                    for (Block ice : iw.affectedBlockSet) {
                        Location iceLocation = ice.getLocation();
                        for (Location loc : arc.getPoints()) {
                            if (iceLocation.getWorld() == loc.getWorld() && loc.distanceSquared(iceLocation) <= 2.25) {
                                iw.damageWall(l.getPlayer(), (int) (FireAbility.getDayFactor(iw.lightningDamage, ice.getWorld())));

                                if (!iw.isWallDoneFor)
                                    l.remove();
                            }
                        }
                    }
                }
            }

            for (AirBlast ab : getAbilities(AirBlast.class)) {
                if (ab.getLocation() == null) continue;
                Location airBlastLocation = ab.getLocation();
                for (Block ice : iw.affectedBlockSet) {
                    Location iceLocation = ice.getLocation();
                    if (iceLocation.getWorld() == airBlastLocation.getWorld() && airBlastLocation.distanceSquared(iceLocation) <= 2.25) {
                        iw.damageWall(ab.getPlayer(), iw.airBlastDamage);

                        if (!iw.isWallDoneFor) ab.remove();
                    }
                }
            }

            for (CoreAbility ca : getAbilities(getAbility("Combustion").getClass())) {
                if (ca.getLocation() == null) continue;
                Location combustionLocation = ca.getLocation();
                for (Block ice : iw.affectedBlockSet) {
                    Location iceLocation = ice.getLocation();
                    if (iceLocation.getWorld() == combustionLocation.getWorld() && combustionLocation.distanceSquared(iceLocation) <= 2.25) {
                        iw.damageWall(ca.getPlayer(), iw.combustionDamage);
                        if (!iw.isWallDoneFor) ca.remove();
                    }
                }
            }
        }

        IceWall.removeDeadInstances();
    }

    public void setFields() {
        setMaxHeight(JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.MaxHeight"));
        setMinHeight(JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.MinHeight"));
        setWidth(JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.Width"));
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.Range");
        maxHealth = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.MaxWallHealth");
        minHealth = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.MinWallHealth");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Water.IceWall.Damage");
        radius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Water.IceWall.Radius");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Water.IceWall.Cooldown");
        allowSnow = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.IceWall.AllowSnow");
        canBreakWalls = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.IceWall.CanBreak");
        breakHitbox = Math.max(0, JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Water.IceWall.BreakHitbox"));
        stackable = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.IceWall.Stackable");
        lifetimeEnabled = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.IceWall.LifeTime.Enabled");
        lifetimeTime = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Water.IceWall.LifeTime.Duration");
        torrentDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.Torrent");
        torrentFreezeDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.TorrentFreeze");
        iceBlastDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.IceBlast");
        fireBlastDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.Fireblast");
        fireBlastChargedDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.FireblastCharged");
        lightningDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.Lightning");
        combustionDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.Combustion");
        earthSmashDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.EarthSmash");
        airBlastDamage = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Water.IceWall.WallDamage.AirBlast");
    }

    public Block getSourceBlock(Player player, int range) {
        if (canBreakWalls) {
            Block wallBlock = getTargetedIceWallBlock(player, range);
            if (wallBlock != null) {
                return wallBlock;
            }
        }

        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection().normalize();
        Location point = eyeLocation.clone();
        Block bendableSource = null;

        for (int i = 0; i <= range; i++) {
            Block b = point.getBlock();

            if (isIceWallBlock(b)) {
                return b;
            }

            if (bendableSource == null && isBendable(b)) {
                bendableSource = b;
            }

            point.add(direction);
        }

        return bendableSource;
    }

    private Block getTargetedIceWallBlock(Player player, int range) {
        Location eyeLocation = player.getEyeLocation();
        Vector direction = eyeLocation.getDirection().normalize();
        double maxDistanceSquared = breakHitbox * breakHitbox;
        Block closestBlock = null;
        double closestDistanceAlongRay = Double.MAX_VALUE;

        for (IceWall wall : instances) {
            for (Block candidate : wall.affectedBlockSet) {
                Location blockLocation = candidate.getLocation();
                if (blockLocation.getWorld() != eyeLocation.getWorld()) {
                    continue;
                }

                Vector toBlock = blockLocation.toVector().add(new Vector(0.5, 0.5, 0.5)).subtract(eyeLocation.toVector());
                double distanceAlongRay = toBlock.dot(direction);
                if (distanceAlongRay < 0 || distanceAlongRay > range) {
                    continue;
                }

                double perpendicularDistanceSquared = toBlock.lengthSquared() - (distanceAlongRay * distanceAlongRay);
                if (perpendicularDistanceSquared <= maxDistanceSquared && distanceAlongRay < closestDistanceAlongRay) {
                    closestDistanceAlongRay = distanceAlongRay;
                    closestBlock = candidate;
                }
            }
        }

        final Block authoritative = TempBlockSync.getAuthoritativeEffectAlongRay(
                player, this.getName(), range, breakHitbox, false);
        if (authoritative != null) {
            final Vector toBlock = authoritative.getLocation().toVector()
                    .add(new Vector(0.5, 0.5, 0.5)).subtract(eyeLocation.toVector());
            final double distanceAlongRay = toBlock.dot(direction);
            if (distanceAlongRay < closestDistanceAlongRay) {
                closestBlock = authoritative;
            }
        }

        return closestBlock;
    }

    public boolean isBendable(Block b) {
        return isWater(b) || isIce(b.getType()) || (allowSnow && isSnow(b.getType()));
    }

    public void loadAffectedBlocks(Player player, Block block) {
        Vector direction = player.getEyeLocation().getDirection().normalize();

        double ox, oy, oz;
        ox = -direction.getZ();
        oy = 0;
        oz = direction.getX();

        Vector orth = new Vector(ox, oy, oz);
        orth = orth.normalize();

        Location origin = block.getLocation();

        World world = origin.getWorld();

        int width = (int) (getWidth() * getNightFactor(world));
        int minHeight = (int) (getMinHeight() * getNightFactor(world));
        int maxHeight = (int) (getMaxHeight() * getNightFactor(world));

        int height = minHeight;
        boolean increasingHeight = true;
        for (int i = -(width / 2); i < width / 2; i++) {
            Block b = world.getBlockAt(origin.clone().add(orth.clone().multiply((double) i)));

            if (ElementalAbility.isAir(b.getType())) {
                while (ElementalAbility.isAir(b.getType())) {
                    if (b.getY() < 0)
                        return;

                    b = b.getRelative(BlockFace.DOWN);
                }
            }

            if (!ElementalAbility.isAir(b.getRelative(BlockFace.UP).getType())) {
                while (!ElementalAbility.isAir(b.getRelative(BlockFace.UP).getType())) {
                    if (b.getY() > b.getWorld().getMaxHeight())
                        return;

                    b = b.getRelative(BlockFace.UP);
                }
            }

            if (!stackable && isIceWallBlock(b)) {
                continue;
            }

            if (isBendable(b)) {
                addAffectedBlock(b);
                for (int h = 1; h <= height; h++) {
                    Block up = b.getRelative(BlockFace.UP, h);
                    if (ElementalAbility.isAir(up.getType())) {
                        addAffectedBlock(up);
                    }
                }

                if (height < maxHeight && increasingHeight)
                    height++;

                if (i == 0)
                    increasingHeight = false;

                if (!increasingHeight && height > minHeight)
                    height--;

                lastBlocks.add(b);
            }
        }

        bPlayer.addCooldown(this);
        rising = true;
        instances.add(this);
    }

    @Override
    public void progress() {
        if (rising) {
            if (lastBlocks.isEmpty()) {
                rising = false;
                if (collapsePending) {
                    final Player requestedPlayer = pendingCollapsePlayer == null
                            ? player : pendingCollapsePlayer;
                    final boolean requestedForceful = pendingCollapseForceful;
                    final CoreAbility requestedCause = pendingCollapseCause;
                    collapsePending = false;
                    pendingCollapseForceful = false;
                    pendingCollapsePlayer = null;
                    pendingCollapseCause = null;
                    AbilityExecutionContext.run(requestedCause,
                            () -> remove(requestedPlayer, requestedForceful));
                }
                return;
            }

            List<Block> theseBlocks = new ArrayList<>(lastBlocks);

            lastBlocks.clear();

            for (Block b : theseBlocks) {
                TempBlock tb = new TempBlock(b, getIceData());
                tempBlocks.add(tb);

                playIcebendingSound(b.getLocation());

                Block up = b.getRelative(BlockFace.UP);

                if (affectedBlockSet.contains(up))
                    lastBlocks.add(up);
            }
        }

        if (System.currentTimeMillis() > lifetime && lifetimeEnabled) {
            collapse(player, false);
        }
    }

    public void damageWall(Player player, int damage) {
        long noDamageTicks = 1000;
        if (System.currentTimeMillis() < lastDamageTime + noDamageTicks)
            return;

        lastDamageTime = System.currentTimeMillis();
        tankedDamage += damage;
        if (tankedDamage >= wallHealth) {
            collapse(player, true);
        }
    }

    public void collapse(Player player, boolean forceful) {
        if (isRemoved()) return;
        if (rising) {
            // Dropping this request makes a one-tick client/server rise skew
            // permanent: one side breaks the wall while the other silently
            // keeps it. Finish constructing the same wall, then collapse it.
            collapsePending = true;
            pendingCollapseForceful |= forceful;
            if (player != null) pendingCollapsePlayer = player;
            pendingCollapseCause = AbilityExecutionContext.current();
            return;
        }

        remove(player, forceful);
    }

    public void remove(Player player, boolean forceful) {
        for (TempBlock tb : tempBlocks) {
            if (tb != null) {
                tb.revertBlock();

                ParticleEffect.BLOCK_CRACK.display(tb.getLocation(), 5, 0, 0, 0, 0, Material.PACKED_ICE.createBlockData());
                tb.getLocation().getWorld().playSound(tb.getLocation(), Sound.BLOCK_GLASS_BREAK, 5f, 5f);

                for (Entity e : GeneralMethods.getEntitiesAroundPoint(tb.getLocation(), radius)) {
                    if (e.getEntityId() != player.getEntityId()) {
                        if (e instanceof LivingEntity) {
                            DamageHandler.damageEntity(e, damage * getNightFactor(player.getWorld()), this);
                            if (forceful) {
                                ((LivingEntity) e).setNoDamageTicks(0);
                            }
                        }
                    }
                }
            }
        }

        tempBlocks.clear();

        isWallDoneFor = true;
        remove();
    }

    public void remove() {
        clearAffectedBlocks();

        for (TempBlock tb : tempBlocks) {
            if (tb != null) {
                tb.revertBlock();
            }
        }

        tempBlocks.clear();

        instances.remove(this);
        super.remove();
    }

    private void addAffectedBlock(Block block) {
        if (affectedBlockSet.add(block)) {
            affectedBlocks.add(block);
            AFFECTED_BLOCK_CACHE.add(block);
        }
    }

    private void clearAffectedBlocks() {
        for (Block block : affectedBlockSet) {
            AFFECTED_BLOCK_CACHE.remove(block);
        }

        affectedBlockSet.clear();
        affectedBlocks.clear();
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
    public String getName() {
        return "IceWall";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Water.IceWall.Description");
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public int getMaxHealth() {
        return maxHealth;
    }

    public void setMaxHealth(int maxHealth) {
        this.maxHealth = maxHealth;
    }

    public int getMinHealth() {
        return minHealth;
    }

    public void setMinHealth(int minHealth) {
        this.minHealth = minHealth;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public boolean isRising() {
        return rising;
    }

    public void setRising(boolean rising) {
        this.rising = rising;
    }

    public long getLastDamageTime() {
        return lastDamageTime;
    }

    public void setLastDamageTime(long lastDamageTime) {
        this.lastDamageTime = lastDamageTime;
    }

    public long getLifetime() {
        return lifetime;
    }

    public void setLifetime(long lifetime) {
        this.lifetime = lifetime;
    }

    public int getWallHealth() {
        return wallHealth;
    }

    public void setWallHealth(int wallHealth) {
        this.wallHealth = wallHealth;
    }

    public int getTankedDamage() {
        return tankedDamage;
    }

    public void setTankedDamage(int tankedDamage) {
        this.tankedDamage = tankedDamage;
    }

    public List<Block> getLastBlocks() {
        return lastBlocks;
    }

    public List<TempBlock> getTempBlocks() {
        return tempBlocks;
    }

    public int getMaxHeight() {
        return maxHeight;
    }

    public void setMaxHeight(int maxHeight) {
        this.maxHeight = maxHeight;
    }

    public int getMinHeight() {
        return minHeight;
    }

    public void setMinHeight(int minHeight) {
        this.minHeight = minHeight;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.IceWall.Enabled");
    }
}
