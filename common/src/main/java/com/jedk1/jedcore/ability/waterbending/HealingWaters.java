package com.jedk1.jedcore.ability.waterbending;

import com.jedk1.jedcore.JedCore;
import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.ability.HealingAbility;
import com.projectkorra.projectkorra.chiblocking.Smokescreen;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Server;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Damageable;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.ParticleEffect;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.util.WaterReturn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

public class HealingWaters extends HealingAbility implements AddonAbility {

    private static long time = 0;
    private static boolean enabled = true;

    public HealingWaters(Player player) {
        super(player);
    }

    public static void heal(Server server) {
        if (enabled) {
            if (System.currentTimeMillis() - time >= 1000) {
                time = System.currentTimeMillis();
                for (Player player : server.getOnlinePlayers()) {
                    BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
                    if (bPlayer != null && bPlayer.canBend(getAbility("HealingWaters"))) {
                        heal(player);
                    }
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void heal(Player player) {
        BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (inWater(player)) {
            if (player.isSneaking()) {
                Entity entity = GeneralMethods.getTargetedEntity(player, getRange(bPlayer), new ArrayList<>());
                if (entity instanceof LivingEntity && inWater(entity)) {
                    Location playerLoc = entity.getLocation();
                    playerLoc.add(0, 1, 0);
                    ParticleEffect.SPELL_MOB_AMBIENT.display(playerLoc, 3, Math.random(), Math.random(), Math.random(), 0.0);
                    ParticleEffect.WATER_WAKE.display(playerLoc, 25, 0, 0, 0, 0.05F);
                    giveHPToEntity((LivingEntity) entity);
                }
            } else {
                Location playerLoc = player.getLocation();
                playerLoc.add(0, 1, 0);
                ParticleEffect.SPELL_MOB_AMBIENT.display(playerLoc, 3, Math.random(), Math.random(), Math.random(), 0.0);
                ParticleEffect.WATER_WAKE.display(playerLoc, 25, 0, 0, 0, 0.05F);
                giveHP(player);
            }
        } else if (hasWaterSupply(player) && player.isSneaking()) {
            Entity entity = GeneralMethods.getTargetedEntity(player, getRange(bPlayer), new ArrayList<>());
            if (entity != null) {
                if (entity instanceof LivingEntity) {
                    Damageable dLe = (Damageable) entity;
                    if (dLe.getHealth() < dLe.getMaxHealth()) {
                        Location playerLoc = entity.getLocation();
                        playerLoc.add(0, 1, 0);
                        ParticleEffect.SPELL_MOB_AMBIENT.display(playerLoc, 3, Math.random(), Math.random(), Math.random(), 0.0);
                        ParticleEffect.WATER_WAKE.display(playerLoc, 25, 0, 0, 0, 0.05F);
                        giveHPToEntity((LivingEntity) entity);
                        entity.setFireTicks(0);
                        Random rand = new Random();
                        if (rand.nextInt(getDrainChance(bPlayer)) == 0)
                            drainWaterSupply(player);
                    }
                }
            } else {
                Location playerLoc = player.getLocation();
                playerLoc.add(0, 1, 0);
                ParticleEffect.SPELL_MOB_AMBIENT.display(playerLoc, 3, Math.random(), Math.random(), Math.random(), 0.0);
                ParticleEffect.WATER_WAKE.display(playerLoc, 25, 0, 0, 0, 0.05F);
                giveHP(player);
                player.setFireTicks(0);
                Random rand = new Random();
                if (rand.nextInt(getDrainChance(bPlayer)) == 0)
                    drainWaterSupply(player);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void giveHPToEntity(LivingEntity le) {
        if (!le.isDead() && le.getHealth() < le.getMaxHealth()) {
            applyHealingToEntity(le);
        }
        for (PotionEffect effect : le.getActivePotionEffects()) {
            if (isNegativeEffect(effect.getType())) {
                le.removePotionEffect(effect.getType());
            }
        }
    }

    private static void giveHP(Player player) {
        if (!player.isDead() && player.getHealth() < 20) {
            applyHealing(player);
        }
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (isNegativeEffect(effect.getType())) {
                if ((effect.getType() == PotionEffectType.BLINDNESS) && Smokescreen.getBlindedTimes().containsKey(player.getName())) {
                    return;
                }
                player.removePotionEffect(effect.getType());
            }
        }
    }


    private static boolean inWater(Entity entity) {
        Block block = entity.getLocation().getBlock();
        return isWater(block) && !TempBlock.isTempBlock(block);
    }

    private static boolean hasWaterSupply(Player player) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        return (heldItem.isSimilar(WaterReturn.waterBottleItem()) || heldItem.getType() == Material.WATER_BUCKET);
    }

    private static void drainWaterSupply(Player player) {
        ItemStack heldItem = player.getInventory().getItemInMainHand();
        ItemStack emptyBottle = new ItemStack(Material.GLASS_BOTTLE, 1);
        if (heldItem.isSimilar(WaterReturn.waterBottleItem())) {
            if (heldItem.getAmount() > 1) {
                heldItem.setAmount(heldItem.getAmount() - 1);
                HashMap<Integer, ItemStack> cantFit = player.getInventory().addItem(emptyBottle);
                for (int id : cantFit.keySet()) {
                    player.getWorld().dropItem(player.getEyeLocation(), cantFit.get(id));
                }
            } else {
                player.getInventory().setItemInMainHand(emptyBottle);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyHealing(Player player) {
        if (!RegionProtection.isRegionProtected(player, player.getLocation(), "HealingWaters"))
            if (player.getHealth() < player.getMaxHealth()) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 70, getPower(BendingPlayer.getBendingPlayer(player))));
                AirAbility.breakBreathbendingHold(player);
            }
    }

    @SuppressWarnings("deprecation")
    private static void applyHealingToEntity(LivingEntity le) {
        if (le.getHealth() < le.getMaxHealth()) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 70, 1));
            AirAbility.breakBreathbendingHold(le);
        }
    }

    public static int getPower(BendingPlayer bPlayer) {
        return JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Water.HealingWaters.Power");
    }

    public static double getRange(BendingPlayer bPlayer) {
        return JedCoreConfig.getConfig(bPlayer).getDouble("Abilities.Water.HealingWaters.Range");
    }

    public static int getDrainChance(BendingPlayer bPlayer) {
        return JedCoreConfig.getConfig(bPlayer).getInt("Abilities.Water.HealingWaters.DrainChance");
    }

    @Override
    public long getCooldown() {
        return 0;
    }

    @Override
    public Location getLocation() {
        return null;
    }

    @Override
    public String getName() {
        return "HealingWaters";
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
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
        return "* JedCore Addon *\n" + JedCoreConfig.getConfig(this.bPlayer).getString("Abilities.Water.HealingWaters.Description");
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isEnabled() {
        enabled = JedCoreConfig.getConfig(this.bPlayer).getBoolean("Abilities.Water.HealingWaters.Enabled");
        return enabled;
    }

    @Override
    public void progress() {
    }
}
