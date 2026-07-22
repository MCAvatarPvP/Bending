package com.jedk1.jedcore.ability.avatar.elementsphere;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.jedk1.jedcore.util.RegenTempBlock;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AvatarAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.prediction.action.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempFallingBlock;

import java.util.Arrays;
import java.util.Random;

public class ESEarth extends AvatarAbility implements AddonAbility {

    private final Random rand;
    static Material[] unbreakables = {Material.BEDROCK, Material.BARRIER, Material.NETHER_PORTAL, Material.END_PORTAL,
            Material.END_PORTAL_FRAME, Material.ENDER_CHEST, Material.CHEST, Material.TRAPPED_CHEST};
    private long revertDelay;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("Size")
    private int impactSize;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private TempFallingBlock tfb;

    public ESEarth(Player player) {
        super(player);
        this.rand = PredictionDeterminism.random(player == null ? null : player.getUniqueId(), getClass().getName());
        if (!hasAbility(player, ElementSphere.class)) {
            return;
        }
        ElementSphere currES = getAbility(player, ElementSphere.class);
        if (currES.getEarthUses() == 0) {
            return;
        }
        if (bPlayer.isOnCooldown("ESEarth")) {
            return;
        }
        if (RegionProtection.isRegionProtected(this, player.getTargetBlock(getTransparentMaterialSet(), 40).getLocation())) {
            return;
        }
        setFields();
        start();
        if (!isRemoved()) {
            bPlayer.addCooldown("ESEarth", getCooldown());
            currES.setEarthUses(currES.getEarthUses() - 1);
            Location location = player.getEyeLocation().clone().add(player.getEyeLocation().getDirection().multiply(1));
            tfb = new TempFallingBlock(location, Material.DIRT.createBlockData(), location.getDirection().multiply(3), this);
        }
    }

    public static void explodeEarth(TempFallingBlock tempfallingblock) {
        FallingBlock fb = tempfallingblock.getFallingBlock();
        ESEarth es = (ESEarth) tempfallingblock.getAbility();
        Player player = es.getPlayer();

        ParticleEffect.SMOKE_LARGE.display(fb.getLocation(), 0, 0, 0, 0.3F, 25);
        fb.getWorld().playSound(fb.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.5f);

        for (Location l : GeneralMethods.getCircle(fb.getLocation(), es.impactSize, 1, false, true, 0)) {
            //if (TempBlock.isTempBlock(l.getBlock())) {
            //	TempBlock.revertBlock(l.getBlock(), Material.AIR);
            //	TempBlock.removeBlock(l.getBlock());
            //}
            if (isBreakable(l.getBlock()) && !RegionProtection.isRegionProtected(player, l, "ElementSphere") && EarthAbility.isEarthbendable(player, l.getBlock())) {
                ParticleEffect.SMOKE_LARGE.display(l, 0, 0, 0, 0.1F, 2);
                //new RegenTempBlock(l.getBlock(), Material.AIR, (byte) 0, (long) rand.nextInt((int) es.revertDelay - (int) (es.revertDelay - 1000)) + (es.revertDelay - 1000));
                new RegenTempBlock(l.getBlock(), Material.AIR, Material.AIR.createBlockData(), (long) es.rand.nextInt((int) es.revertDelay - (int) (es.revertDelay - 1000)) + (es.revertDelay - 1000), false);
            }

            if (GeneralMethods.isSolid(l.getBlock().getRelative(BlockFace.DOWN)) && isBreakable(l.getBlock()) && ElementalAbility.isAir(l.getBlock().getType()) && es.rand.nextInt(20) == 0 && EarthAbility.isEarthbendable(player, l.getBlock().getRelative(BlockFace.DOWN))) {
                Material type = l.getBlock().getRelative(BlockFace.DOWN).getType();
                new RegenTempBlock(l.getBlock(), type, type.createBlockData(), (long) es.rand.nextInt((int) es.revertDelay - (int) (es.revertDelay - 1000)) + (es.revertDelay - 1000));
            }
        }

        tempfallingblock.remove();
    }

    public static boolean isBreakable(Block block) {
        return !Arrays.asList(unbreakables).contains(block.getType());
    }

    public void setFields() {
        revertDelay = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Avatar.ElementSphere.Earth.ImpactRevert");
        damage = JedCoreConfig.getConfig(this.bPlayer).getDouble("Abilities.Avatar.ElementSphere.Earth.Damage");
        impactSize = JedCoreConfig.getConfig(this.bPlayer).getInt("Abilities.Avatar.ElementSphere.Earth.ImpactCraterSize");
        cooldown = JedCoreConfig.getConfig(this.bPlayer).getLong("Abilities.Avatar.ElementSphere.Earth.Cooldown");
    }

    @Override
    public void progress() {
        if (player == null || !player.isOnline()) {
            tfb.remove();
            remove();
            return;
        }
        if (tfb.getFallingBlock().isDead()) {
            remove();
            return;
        }
        if (RegionProtection.isRegionProtected(this, tfb.getLocation())) {
            remove();
            return;
        }

        EarthAbility.playEarthbendingSound(tfb.getLocation());

        for (Entity entity : GeneralMethods.getEntitiesAroundPoint(tfb.getLocation(), 2.5)) {
            if (entity instanceof LivingEntity && !(entity instanceof ArmorStand) && entity.getEntityId() != player.getEntityId() && !RegionProtection.isRegionProtected(this, entity.getLocation()) && !((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                //explodeEarth(fb);
                DamageHandler.damageEntity(entity, damage, this);
            }
        }
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return tfb != null ? tfb.getLocation() : null;
    }

    @Override
    public String getName() {
        return "ElementSphereEarth";
    }

    @Override
    public boolean isHiddenAbility() {
        return true;
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
        return null;
    }

    public long getRevertDelay() {
        return revertDelay;
    }

    public void setRevertDelay(long revertDelay) {
        this.revertDelay = revertDelay;
    }

    public double getDamage() {
        return damage;
    }

    public void setDamage(double damage) {
        this.damage = damage;
    }

    public int getImpactSize() {
        return impactSize;
    }

    public void setImpactSize(int impactSize) {
        this.impactSize = impactSize;
    }

    public TempFallingBlock getTempFallingBlock() {
        return tfb;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        return JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Avatar.ElementSphere.Enabled");
    }
}
