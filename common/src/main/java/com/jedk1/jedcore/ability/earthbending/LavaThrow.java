package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.LavaAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.Levelled;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

public class LavaThrow extends LavaAbility implements AddonAbility {
    private final ConcurrentHashMap<Location, Location> blasts = new ConcurrentHashMap<>();
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.SELECT_RANGE)
    private int sourceRange;
    private long sourceRegen;
    @Attribute("MaxShots")
    private int shotMax;
    @Attribute(Attribute.FIRE_TICK)
    private int fireTicks;
    private Location location;
    private int shots;
    private final Random random;

    public LavaThrow(Player player) {
        super(player);
        this.random = PredictionDeterminism.random(player == null ? null : player.getUniqueId(), getClass().getName());

        if (hasAbility(player, LavaThrow.class)) {
            LavaThrow.createBlast(player);
            return;
        }

        if (!bPlayer.canBend(this) || !bPlayer.canLavabend()) {
            return;
        }

        setFields();

        location = player.getLocation();
        location.setPitch(0);
        location = location.toVector().add(location.getDirection().multiply(sourceRange)).toLocation(location.getWorld());

        sourceRange = Math.round(sourceRange / 2F);

        if (prepare()) {
            start();
            if (!isRemoved()) {
                createBlast();
            }
        }
    }

    public static Block getRandomSourceBlock(Location location, int radius) {
        return getRandomSourceBlock(location, radius, new Random());
    }

    private static Block getRandomSourceBlock(Location location, int radius, final Random rand) {
        List<Integer> checked = new ArrayList<>();
        List<Block> blocks = GeneralMethods.getBlocksAroundPoint(location, radius);

        for (int i = 0; i < blocks.size(); i++) {
            int index = rand.nextInt(blocks.size());

            while (checked.contains(index)) {
                index = rand.nextInt(blocks.size());
            }

            checked.add(index);

            Block block = blocks.get(index);

            if (!LavaAbility.isLava(block)) {
                continue;
            }

            return block;
        }

        return null;
    }

    public static void createBlast(Player player) {
        if (hasAbility(player, LavaThrow.class)) {
            LavaThrow lt = getAbility(player, LavaThrow.class);

            if (lt.shots < lt.shotMax) {
                lt.createBlast();
            }
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.LavaThrow.Cooldown");
        range = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.LavaThrow.Range");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.LavaThrow.Damage");
        sourceRange = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.LavaThrow.SourceGrabRange");
        sourceRegen = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.LavaThrow.SourceRegenDelay");
        shotMax = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.LavaThrow.MaxShots");
        fireTicks = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.LavaThrow.FireTicks");
    }

    @Override
    public void progress() {
        if (player == null || player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (player.getWorld() != location.getWorld()) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (shots >= shotMax) {
            bPlayer.addCooldown(this);
        }

        handleBlasts();

        if (blasts.isEmpty()) {
            bPlayer.addCooldown(this);
            remove();
        }
    }

    private boolean prepare() {
        Block block = getRandomSourceBlock(location, 3, this.random);

        return block != null;
    }

    public void createBlast() {
        // TODO: This is just the worst. Fix it so it's not hidden distance selection.
        Block source = getRandomSourceBlock(location, 3, this.random);

        if (source != null) {
            shots++;

            Location origin = source.getLocation().clone().add(0, 2, 0);
            double viewRange = range + origin.distance(player.getEyeLocation());
            Location viewTarget = GeneralMethods.getTargetedLocation(player, viewRange, Material.WATER, Material.LAVA);
            Vector direction = viewTarget.clone().subtract(origin).toVector().normalize();
            Location head = origin.clone();

            head.setDirection(direction);
            blasts.put(head, origin);

            new RegenTempBlock(source.getRelative(BlockFace.UP), Material.LAVA, Material.LAVA.createBlockData(bd -> ((Levelled) bd).setLevel(0)), 200);
            new RegenTempBlock(source, Material.AIR, Material.AIR.createBlockData(), sourceRegen, false);
        }
    }

    public void handleBlasts() {
        for (Location l : blasts.keySet()) {
            Location head = l.clone();
            Location origin = blasts.get(l);

            if (l.distance(origin) > range) {
                blasts.remove(l);
                continue;
            }

            if (RegionProtection.isRegionProtected(this, l)) {
                blasts.remove(l);
                continue;
            }

            if (GeneralMethods.isSolid(l.getBlock())) {
                blasts.remove(l);
                continue;
            }

            head = head.add(head.getDirection().multiply(1));
            new RegenTempBlock(l.getBlock(), Material.LAVA, Material.LAVA.createBlockData(bd -> ((Levelled) bd).setLevel(0)), 200);
            ParticleEffect.LAVA.display(head, 1, Math.random(), Math.random(), Math.random(), 0);

            boolean hit = false;

            for (Entity entity : GeneralMethods.getEntitiesAroundPoint(l, 2.0D)) {
                if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !RegionProtection.isRegionProtected(this, entity.getLocation()) && !((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                    DamageHandler.damageEntity(entity, damage, this);
                    blasts.remove(l);

                    hit = true;
                    entity.setFireTicks(this.fireTicks);
                }
            }

            if (!hit) {
                blasts.remove(l);
                blasts.put(head, origin);
            }
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
    public String getName() {
        return "LavaThrow";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.LavaThrow.Description");
    }

    public int getRange() {
        return range;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public int getSourceRange() {
        return sourceRange;
    }

    public void setSourceRange(int sourceRange) {
        this.sourceRange = sourceRange;
    }

    public long getSourceRegen() {
        return sourceRegen;
    }

    public void setSourceRegen(long sourceRegen) {
        this.sourceRegen = sourceRegen;
    }

    public int getShotMax() {
        return shotMax;
    }

    public void setShotMax(int shotMax) {
        this.shotMax = shotMax;
    }

    public int getFireTicks() {
        return fireTicks;
    }

    public void setFireTicks(int fireTicks) {
        this.fireTicks = fireTicks;
    }

    public int getShots() {
        return shots;
    }

    public void setShots(int shots) {
        this.shots = shots;
    }

    public ConcurrentHashMap<Location, Location> getBlasts() {
        return blasts;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.LavaThrow.Enabled");
    }
}
