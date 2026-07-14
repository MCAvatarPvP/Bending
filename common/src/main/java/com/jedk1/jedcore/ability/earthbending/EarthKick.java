package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.collision.CollisionDetector;
import com.jedk1.jedcore.collision.CollisionUtil;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.BlockUtil;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import com.projectkorra.projectkorra.util.colliders.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static java.util.stream.Collectors.toList;

public class EarthKick extends EarthAbility implements AddonAbility {
    private final List<TempFallingBlock> temps = new ArrayList<>();
    private final Random rand = new Random();
    private BlockData materialData;
    private Location location;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute("MaxShots")
    private int earthBlocks;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("CollisionRadius")
    private double entityCollisionRadius;
    private Block block;

    public EarthKick(Player player) {
        super(player);

        if (bPlayer == null) return;
        if (!bPlayer.canBend(this)) {
            return;
        }

        setFields();
        location = player.getLocation();
        if ((player.getLocation().getPitch() > -5) && prepare()) {
            if (RegionProtection.isRegionProtected(this, block.getLocation())) {
                return;
            }
            launchBlocks();
            start();
        }
    }

    public void setFields() {
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Earth.EarthKick.Cooldown");
        earthBlocks = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Earth.EarthKick.EarthBlocks");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthKick.Damage");
        entityCollisionRadius = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthKick.EntityCollisionRadius");

        if (entityCollisionRadius < 1.0) {
            entityCollisionRadius = 1.0;
        }
    }

    private boolean prepare() {
        block = player.getTargetBlock(getTransparentMaterialSet(), 2);
        if (!isEarthbendable(player, block)) {
            return false;
        }

        if (block != null && !isMetal(block)) {
            materialData = block.getBlockData().clone();
            location.setX(block.getX() + 0.5);
            location.setY(block.getY());
            location.setZ(block.getZ() + 0.5);

            return true;
        }

        return false;
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
            remove();
            return;
        }

        bPlayer.addCooldown(this);
        track();

        if (temps.isEmpty()) {
            remove();
        }
    }

    private void launchBlocks() {
        if (EarthAbility.getMovedEarth().containsKey(block)) {
            block.setType(Material.AIR);
        }
        if (block.getType() != Material.AIR) {
            TempBlock air = new TempBlock(block, Material.AIR);
            air.setRevertTime(5000L);
        }

        location.setPitch(0);
        Vector direction = location.getDirection();
        location.add(direction.clone().multiply(1.0));

        if (!ElementalAbility.isAir(location.getBlock().getType())) {
            location.setY(location.getY() + 1.0);
        }

        ParticleEffect.CRIT.display(location, 10, Math.random(), Math.random(), Math.random(), 0.1);

        int yaw = Math.round(location.getYaw());

        playEarthbendingSound(location);

        for (int i = 0; i < earthBlocks; i++) {
            location.setYaw(yaw + (rand.nextInt((20 - -20) + 1) + -20));
            location.setPitch(rand.nextInt(25) - 45);

            Vector v = location.clone().add(0, 0.8, 0).getDirection().normalize();
            Location location1 = location.clone().add(new Vector(v.getX() * 2, v.getY(), v.getZ() * 2));
            Vector dir = location1.setDirection(location.getDirection()).getDirection();

            temps.add(new TempFallingBlock(location, materialData, dir, this));
        }
    }

    public void track() {
        List<TempFallingBlock> destroy = new ArrayList<>();

        for (TempFallingBlock tfb : temps) {
            FallingBlock fb = tfb.getFallingBlock();

            if (fb == null || fb.isDead()) {
                destroy.add(tfb);
                continue;
            }

            for (int i = 0; i < 2; i++) {
                ParticleEffect.BLOCK_CRACK.display(fb.getLocation(), 1, 0.0, 0.0, 0.0, 0.1, materialData);
                ParticleEffect.BLOCK_CRACK.display(fb.getLocation(), 1, 0.0, 0.0, 0.0, 0.2, materialData);
            }

            AABB collider = BlockUtil.getFallingBlockBoundsFull(fb, entityCollisionRadius);

            CollisionDetector.checkEntityCollisions(player, collider, (entity) -> {
                DamageHandler.damageEntity(entity, damage, this);
                return false;
            });
        }

        temps.removeAll(destroy);
    }

    @Override
    public long getCooldown() {
        return cooldown;
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
        return temps.stream().map(TempFallingBlock::getLocation).collect(toList());
    }

    @Override
    public double getCollisionRadius() {
        return JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Earth.EarthKick.AbilityCollisionRadius");
    }

    @Override
    public void handleCollision(Collision collision) {
        CollisionUtil.handleFallingBlockCollisions(collision, temps);
    }

    @Override
    public String getName() {
        return "EarthKick";
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Earth.EarthKick.Description");
    }

    public List<TempFallingBlock> getTemps() {
        return temps;
    }

    public BlockData getMaterialData() {
        return materialData;
    }

    public void setMaterialData(BlockData materialData) {
        this.materialData = materialData;
    }

    public int getEarthBlocksQuantity() {
        return earthBlocks;
    }

    public void setEarthBlocksQuantity(int earthBlocks) {
        this.earthBlocks = earthBlocks;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public double getEntityCollisionRadius() {
        return entityCollisionRadius;
    }

    public void setEntityCollisionRadius(double entityCollisionRadius) {
        this.entityCollisionRadius = entityCollisionRadius;
    }

    public Block getBlock() {
        return block;
    }

    public void setBlock(Block block) {
        this.block = block;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Earth.EarthKick.Enabled");
    }
}
